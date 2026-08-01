package io.github.jaypetez.ollamamobile.remote

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.asException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The classification is a product decision, so it is asserted rather than
 * reviewed by eye. The queue-full case in particular has to be its own type:
 * every test here would still pass if 503 collapsed into a generic HTTP error,
 * except the first two.
 */
@RunWith(JUnit4::class)
class RemoteErrorTest {
    @Test
    fun `503 with a pending-requests body is QueueFull, not a generic Http error`() {
        val body = """{"error":"server busy, please try again.  maximum pending requests exceeded"}"""

        val error = RemoteError.fromHttp(code = 503, body = body)

        assertThat(error).isInstanceOf(AppError.Network.QueueFull::class.java)
        assertThat(error).isNotInstanceOf(AppError.Network.Http::class.java)
        // The server's own sentence survives: it is the only thing that tells
        // the user the queue, not the network, is the problem.
        assertThat(error.message).contains("pending requests")
    }

    @Test
    fun `503 that is not about the queue stays a plain Http error`() {
        val error = RemoteError.fromHttp(code = 503, body = "upstream connect error")

        assertThat(error).isInstanceOf(AppError.Network.Http::class.java)
        assertThat((error as AppError.Network.Http).code).isEqualTo(503)
        assertThat(error.body).isEqualTo("upstream connect error")
    }

    @Test
    fun `401 and 403 are distinguishable and say different things`() {
        val unauthorized = RemoteError.fromHttp(code = 401)
        val forbidden = RemoteError.fromHttp(code = 403)

        assertThat((unauthorized as AppError.Network.Http).code).isEqualTo(401)
        assertThat((forbidden as AppError.Network.Http).code).isEqualTo(403)
        assertThat(unauthorized.message).isNotEqualTo(forbidden.message)
        assertThat(unauthorized.message).ignoringCase().contains("credential")
    }

    @Test
    fun `the server body is preserved and used as the message`() {
        val body = """{"error":"model \"qwen3\" not found, try pulling it first"}"""

        val error = RemoteError.fromHttp(code = 404, body = body) as AppError.Network.Http

        assertThat(error.body).isEqualTo(body)
        assertThat(error.message).isEqualTo("""model "qwen3" not found, try pulling it first""")
    }

    @Test
    fun `an OpenAI style nested error object yields its message`() {
        val body = """{"error":{"message":"invalid model","type":"invalid_request_error","code":null}}"""

        val error = RemoteError.fromHttp(code = 400, body = body)

        assertThat(error.message).isEqualTo("invalid model")
    }

    @Test
    fun `a non-JSON body is kept verbatim rather than discarded`() {
        val error = RemoteError.fromHttp(code = 502, body = "<html><body>Bad Gateway</body></html>")

        assertThat(error.message).contains("Bad Gateway")
    }

    @Test
    fun `an oversized body is truncated instead of being carried whole`() {
        val error = RemoteError.fromHttp(code = 500, body = "x".repeat(RemoteError.MAX_BODY_CHARS * 2))

        val body = (error as AppError.Network.Http).body.orEmpty()
        assertThat(body.length).isAtMost(RemoteError.MAX_BODY_CHARS + 1)
    }

    @Test
    fun `an empty body leaves the default message and no body`() {
        val error = RemoteError.fromHttp(code = 500, body = "   ") as AppError.Network.Http

        assertThat(error.body).isNull()
        assertThat(error.message).contains("500")
    }

    @Test
    fun `a queue-full payload inside a 200 stream is still QueueFull`() {
        val error = RemoteError.fromStreamPayload("""{"error":"maximum pending requests exceeded"}""")

        assertThat(error).isInstanceOf(AppError.Network.QueueFull::class.java)
    }

    @Test
    fun `any other 200 stream payload keeps the server text`() {
        val error = RemoteError.fromStreamPayload("""{"error":"an unexpected error occurred"}""")

        assertThat(error).isInstanceOf(AppError.Network.Http::class.java)
        assertThat(error.message).isEqualTo("an unexpected error occurred")
    }

    @Test
    fun `a read timeout is Timeout and not Unreachable`() {
        val error = RemoteError.fromThrowable(SocketTimeoutException("timeout"))

        assertThat(error).isInstanceOf(AppError.Network.Timeout::class.java)
    }

    @Test
    fun `connection failures are Unreachable`() {
        assertThat(RemoteError.fromThrowable(ConnectException("Connection refused")))
            .isInstanceOf(AppError.Network.Unreachable::class.java)
        assertThat(RemoteError.fromThrowable(UnknownHostException("nas.local")))
            .isInstanceOf(AppError.Network.Unreachable::class.java)
        assertThat(RemoteError.fromThrowable(IOException("unexpected end of stream")))
            .isInstanceOf(AppError.Network.Unreachable::class.java)
    }

    @Test
    fun `SSL failures are Tls, even though they are also IOExceptions`() {
        assertThat(RemoteError.fromThrowable(SSLHandshakeException("bad cert")))
            .isInstanceOf(AppError.Network.Tls::class.java)
        assertThat(RemoteError.fromThrowable(SSLPeerUnverifiedException("hostname mismatch")))
            .isInstanceOf(AppError.Network.Tls::class.java)
    }

    @Test
    fun `cancellation is Cancelled and an already-typed error passes through`() {
        assertThat(RemoteError.fromThrowable(CancellationException("scope died")))
            .isInstanceOf(AppError.Network.Cancelled::class.java)

        val original = AppError.Network.QueueFull()
        assertThat(RemoteError.fromThrowable(original.asException())).isSameInstanceAs(original)
    }

    @Test
    fun `an unclassified throwable becomes Unexpected with its cause attached`() {
        val cause = IllegalStateException("no idea")

        val error = RemoteError.fromThrowable(cause)

        assertThat(error).isInstanceOf(AppError.Unexpected::class.java)
        assertThat(error.cause).isSameInstanceAs(cause)
    }
}
