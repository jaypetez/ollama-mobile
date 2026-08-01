package io.github.jaypetez.ollamamobile.remote.health

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ServerId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl

/** Wall-clock time, injected so a test can control it. */
fun interface WallClock {
    fun nowMillis(): Long
}

/** How one request ended. */
sealed interface RequestOutcome {
    /** A response arrived. [code] is the HTTP status, even when it was an error status. */
    data class Answered(
        val code: Int,
    ) : RequestOutcome

    /** No usable response. [error] is already the domain classification. */
    data class Failed(
        val error: AppError,
    ) : RequestOutcome

    /** The caller or the scope cancelled. Not a fault; recorded so a gap in the list is explained. */
    data object Cancelled : RequestOutcome
}

/**
 * One request the app made to one server.
 *
 * [path] and not a full URL, and no headers at all: the record is shown in a
 * diagnostics screen and copied into bug reports, and a query string or an
 * `Authorization` header would ride along. The type cannot hold a credential
 * because it has nowhere to put one.
 */
data class RequestRecord(
    val serverId: ServerId,
    val method: String,
    val path: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val outcome: RequestOutcome,
    /** Response bytes when they were counted; null for a stream, which has no length. */
    val responseBytes: Long? = null,
) {
    val isSuccess: Boolean
        get() = outcome is RequestOutcome.Answered && outcome.code in SUCCESS_CODES

    private companion object {
        val SUCCESS_CODES = 200..299
    }
}

/**
 * A bounded, in-memory record of the requests this app has sent to each server.
 *
 * ## Why this is not called "server logs"
 *
 * Because it is not, and the name would be a promise the product cannot keep.
 * **Ollama exposes no log endpoint.** There is no `/api/logs`, no way to ask a
 * server what it has been doing, and no protocol affordance that could be added
 * client-side to get one — the server's log lives in journald or in a Docker
 * log on a machine this app cannot read. A screen labelled "server logs" that
 * shows only the requests this phone happened to make is guaranteed to produce
 * the bug report "why are the server logs empty / why don't they show what my
 * other client did", and the answer would be "they were never server logs".
 *
 * So the name says exactly what the data is: the history of requests *we* made.
 * That is genuinely useful — it is where the timings, the status codes and the
 * failure classifications for the health screen come from — and it does not
 * imply access nobody has.
 *
 * Bounded per server rather than globally: one chatty server must not evict the
 * evidence of the one that is failing, which is the case the screen exists for.
 */
@Singleton
class RequestHistory
    @Inject
    constructor(
        private val clock: WallClock,
    ) {
        private val lock = Any()
        private val perServer = LinkedHashMap<ServerId, ArrayDeque<RequestRecord>>()
        private val mutableRecords = MutableStateFlow<Map<ServerId, List<RequestRecord>>>(emptyMap())

        /** Every server's history, newest last, for the diagnostics screen. */
        val records: StateFlow<Map<ServerId, List<RequestRecord>>> = mutableRecords.asStateFlow()

        /** Records a completed request. Returns the record so a caller can log it once. */
        fun record(
            serverId: ServerId,
            method: String,
            url: HttpUrl,
            startedAtMillis: Long,
            outcome: RequestOutcome,
            responseBytes: Long? = null,
        ): RequestRecord {
            val record = RequestRecord(
                serverId = serverId,
                method = method,
                // encodedPath and not toString(): a full URL can carry
                // `?api_key=` or `user:password@`, and this record is designed
                // to be pasted into a public issue.
                path = url.encodedPath,
                startedAtMillis = startedAtMillis,
                durationMillis = (clock.nowMillis() - startedAtMillis).coerceAtLeast(0L),
                outcome = outcome,
                responseBytes = responseBytes,
            )
            add(record)
            return record
        }

        /** The history for one server, oldest first. */
        fun forServer(serverId: ServerId): List<RequestRecord> = synchronized(lock) {
            perServer[serverId]?.toList().orEmpty()
        }

        /** Drops everything for one server. Called when the user forgets the server. */
        fun clear(serverId: ServerId) {
            synchronized(lock) {
                perServer.remove(serverId)
                publish()
            }
        }

        private fun add(record: RequestRecord) {
            synchronized(lock) {
                val queue = perServer.getOrPut(record.serverId) { ArrayDeque(CAPACITY_PER_SERVER) }
                queue.addLast(record)
                while (queue.size > CAPACITY_PER_SERVER) queue.removeFirst()
                publish()
            }
        }

        private fun publish() {
            mutableRecords.value = perServer.mapValues { (_, queue) -> queue.toList() }
        }

        companion object {
            /**
             * Enough to cover a debugging session, small enough that the whole
             * structure is a rounding error next to one conversation.
             */
            const val CAPACITY_PER_SERVER: Int = 100
        }
    }
