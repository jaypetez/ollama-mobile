package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.model.asException
import io.github.jaypetez.ollamamobile.remote.auth.AuthInterceptor
import io.github.jaypetez.ollamamobile.remote.auth.resolveCredential
import io.github.jaypetez.ollamamobile.remote.health.RequestHistory
import io.github.jaypetez.ollamamobile.remote.health.RequestOutcome
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import io.github.jaypetez.ollamamobile.remote.tls.PinnedTrust
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * The HTTP plumbing both protocol clients sit on.
 *
 * Extracted rather than duplicated because everything in it is a decision that
 * has to be identical on the two surfaces — which client the call is derived
 * from, when the read timeout is removed, where the credential is attached, how
 * a failure is classified, what lands in [RequestHistory]. Two copies would
 * drift, and the copy that drifted would be the one nobody was looking at.
 *
 * ## Per-call derivation from the shared client
 *
 * [sharedClient] is the single app-wide `OkHttpClient` from `:core-common`,
 * carrying `LanOnlyGuard`, the API inspector and the default timeouts. A
 * Konsist test fails the build if any other production class constructs one, so
 * every adjustment here goes through `newBuilder()` — which keeps the
 * connection pool, the dispatcher and every interceptor instead of starting a
 * second, unpoliced stack.
 *
 * **`readTimeout = 0`, for streaming calls only.** The read timeout is the gap
 * allowed *between bytes*. A model that has to be paged in from disk and then
 * thinks before its first token legitimately emits nothing for ninety seconds,
 * and the shared client's 60-second default would abort that generation and
 * report a timeout — indistinguishable, to the user, from the model failing.
 * One-shot calls keep the default, because for `GET /api/tags` a long silence
 * really is a fault.
 */
@Singleton
internal class RemoteHttp
    @Inject
    constructor(
        private val sharedClient: OkHttpClient,
        private val secretResolver: SecretResolver,
        private val retryPolicy: RetryPolicy,
        private val history: RequestHistory,
        private val clock: WallClock,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /** One non-streaming request, retried where the policy allows, always recorded. */
        suspend fun <T> request(
            server: ServerRef,
            method: String,
            path: String,
            kind: RequestKind,
            body: RequestBody? = null,
            decode: (String) -> T,
        ): AppResult<T> = withContext(io) {
            val url = ServerUrls.resolveOrNull(server, path)
                ?: return@withContext AppResult.Failure(ServerUrls.malformed(server))
            val client = clientFor(server, streaming = false)

            when (val outcome = retryPolicy.withRetry(kind) { attempt(server, client, method, url, body, decode) }) {
                is RetryPolicy.Attempt.Success -> AppResult.Success(outcome.value)
                is RetryPolicy.Attempt.Failure -> AppResult.Failure(outcome.error)
            }
        }

        /**
         * A streaming POST, parsed by [parse] — `asNdjsonFlow` for the native
         * endpoints, `asSseFlow` for the `/v1` ones. Both close the body on every exit path.
         *
         * Failures are thrown as [AppErrorException]; the callers turn them
         * into a terminal `StreamEvent.Failed` so nothing escapes the boundary
         * untyped.
         *
         * ## Cancelling has to close the socket, not just the coroutine
         *
         * The watcher below covers the *whole* call, headers and body, and that
         * is the difference between a cancelled generation stopping and a
         * cancelled generation merely being ignored. `readTimeout` is zero on a
         * stream — see the class KDoc — so a parser waiting for the next token
         * is parked in a blocking socket read with no deadline. Coroutine
         * cancellation is cooperative and cannot interrupt that read, and the
         * `use` block that would close the body does not get to run until the
         * read returns. The result is a thread and a socket held for as long as
         * the server feels like holding them, on every screen the user backs
         * out of mid-answer — and the server, which stops generating when its
         * client goes away, never learns that it has.
         *
         * `Call.cancel()` closes the socket, which makes the blocked read throw
         * and unwinds everything. It has to be issued from a coroutine that is
         * actually resumable, hence [awaitCancellation] in a child rather than
         * a `finally` on this one; [finished] keeps it from firing on a call
         * that has already run to completion.
         */
        fun <D> stream(
            server: ServerRef,
            path: String,
            body: RequestBody,
            parse: (ResponseBody) -> Flow<D>,
        ): Flow<D> = flow {
            val url = ServerUrls.resolveOrNull(server, path) ?: throw ServerUrls.malformed(server).asException()
            val client = clientFor(server, streaming = true)
            val startedAt = clock.nowMillis()
            val call = client.newCall(
                Request
                    .Builder()
                    .url(url)
                    .post(body)
                    .build(),
            )

            coroutineScope {
                val finished = AtomicBoolean(false)
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) call.cancel()
                    }
                }
                try {
                    val response = call.awaitResponse()
                    failOnErrorStatus(server, response, url, startedAt)
                    // Recorded at the headers rather than at the end: a stream
                    // has no length, and the number worth keeping is
                    // time-to-first-byte.
                    history.record(server.id, "POST", url, startedAt, RequestOutcome.Answered(response.code))
                    emitAll(parse(response.body))
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }
        }.flowOn(io)

        private suspend fun clientFor(server: ServerRef, streaming: Boolean): OkHttpClient {
            val credential = server.resolveCredential(secretResolver)
            val builder = sharedClient.newBuilder().addInterceptor(AuthInterceptor.forServer(server, credential))
            if (streaming) builder.readTimeout(Duration.ZERO)
            return PinnedTrust.apply(builder, server).build()
        }

        @Suppress("TooGenericExceptionCaught") // Rethrown unless it is one of the three known families.
        private suspend fun <T> attempt(
            server: ServerRef,
            client: OkHttpClient,
            method: String,
            url: HttpUrl,
            body: RequestBody?,
            decode: (String) -> T,
        ): RetryPolicy.Attempt<T> {
            val startedAt = clock.nowMillis()
            return try {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .method(method, body)
                            .build(),
                    ).awaitResponse()
                    .use { response -> classify(server, method, url, startedAt, response, decode) }
            } catch (cancellation: CancellationException) {
                history.record(server.id, method, url, startedAt, RequestOutcome.Cancelled)
                throw cancellation
            } catch (failure: Throwable) {
                if (failure !is IOException && failure !is AppErrorException && failure !is SerializationException) {
                    throw failure
                }
                val error = RemoteError.fromThrowable(failure)
                history.record(server.id, method, url, startedAt, RequestOutcome.Failed(error))
                RetryPolicy.Attempt.Failure(error)
            }
        }

        private fun <T> classify(
            server: ServerRef,
            method: String,
            url: HttpUrl,
            startedAt: Long,
            response: Response,
            decode: (String) -> T,
        ): RetryPolicy.Attempt<T> {
            val text = response.body.string()
            history.record(
                serverId = server.id,
                method = method,
                url = url,
                startedAtMillis = startedAt,
                outcome = RequestOutcome.Answered(response.code),
                responseBytes = text.length.toLong(),
            )
            return if (response.isSuccessful) {
                RetryPolicy.Attempt.Success(decode(text))
            } else {
                RetryPolicy.Attempt.Failure(RemoteError.fromHttp(response.code, text))
            }
        }

        private fun failOnErrorStatus(server: ServerRef, response: Response, url: HttpUrl, startedAt: Long) {
            if (response.isSuccessful) return
            val text = response.use { it.body.string() }
            val error = RemoteError.fromHttp(response.code, text)
            history.record(server.id, "POST", url, startedAt, RequestOutcome.Failed(error))
            throw error.asException()
        }
    }
