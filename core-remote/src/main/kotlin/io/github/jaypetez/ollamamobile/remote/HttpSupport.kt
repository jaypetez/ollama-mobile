package io.github.jaypetez.ollamamobile.remote

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * Runs [Call] on the caller's (I/O) dispatcher, cancelling it promptly when the
 * coroutine is cancelled.
 *
 * ## Why `execute()` and not `enqueue()`
 *
 * `LanOnlyGuard` reports a policy violation by throwing `AppErrorException`
 * from inside the interceptor chain. OkHttp's async path catches a
 * non-`IOException`, hands the callback an `IOException("canceled due to …")`
 * — and then **rethrows the original on its own dispatcher thread**, where
 * nothing can catch it. On Android an uncaught exception on a background thread
 * kills the process, so `enqueue()` would turn "offline mode blocked a request"
 * into a crash. Executing synchronously keeps the typed error on the caller's
 * stack, which is the entire reason the guard throws a typed error.
 *
 * ## Why the watcher coroutine
 *
 * `execute()` blocks its thread, so the coroutine machinery cannot interrupt
 * it, and `Job.invokeOnCompletion` is no help: a job that is cancelling does
 * not *complete* until its body returns, and the body is the blocked call. That
 * is a deadlock — the handler waits for the call, the call waits for the
 * handler — and it shows up as a discovery sweep that keeps 32 sockets open for
 * a full timeout after the user has left the screen.
 *
 * A child coroutine suspended in [awaitCancellation] has no such problem: it is
 * resumed the instant the scope is cancelled, and closing the socket from there
 * makes the blocked `execute()` return. The flag stops the watcher from
 * cancelling a call that has already produced a response — for a streaming
 * response the body is still being read after this function returns, and
 * cancelling then would truncate the stream on its way out.
 */
internal suspend fun Call.awaitResponse(): Response {
    val call = this
    return coroutineScope {
        val answered = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!answered.get()) call.cancel()
            }
        }
        try {
            call.execute().also { answered.set(true) }
        } finally {
            watcher.cancel()
        }
    }
}
