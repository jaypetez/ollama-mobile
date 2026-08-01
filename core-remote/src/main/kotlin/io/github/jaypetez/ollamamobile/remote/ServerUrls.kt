package io.github.jaypetez.ollamamobile.remote

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ServerRef
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns the string a user typed into an [HttpUrl], and joins API paths onto it.
 *
 * This is a separate object with its own tests because every one of the four
 * shapes below has been a real bug in some other Ollama client, and all four
 * are indistinguishable from "it works on my network" until someone deploys
 * differently:
 *
 *  * **A trailing slash.** `http://host:11434/` naively concatenated with
 *    `/api/tags` gives `//api/tags`, which a reverse proxy will happily route
 *    somewhere else, or 404.
 *  * **An explicit port.** `HttpUrl` fills in 80/443 when no port is written,
 *    so "did the user give a port?" cannot be answered after parsing. It has to
 *    be answered against the raw text, or every bare host silently becomes
 *    port 80 instead of Ollama's 11434.
 *  * **An IPv6 literal.** `http://::1:11434` is not parseable and never will be
 *    — the literal has to be bracketed, and the user typing it into a text
 *    field will not do that.
 *  * **A path prefix.** `https://example.org/ollama` is how the server is
 *    reached behind nginx. The prefix must survive, so the request goes to
 *    `/ollama/api/tags` and not to `/api/tags`.
 */
object ServerUrls {
    /** Ollama's default listen port. Applied only when the scheme is `http`. */
    const val DEFAULT_PORT: Int = 11434

    /**
     * Parses [raw] into a base URL, or returns null when it cannot be one.
     *
     * Normalisation, in order: add a scheme if missing, bracket a bare IPv6
     * literal, then apply the default port when the user wrote none.
     */
    fun parseOrNull(raw: String): HttpUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val schemeless = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
        val scheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> "http"

            trimmed.startsWith("https://", ignoreCase = true) -> "https"

            // A scheme we do not speak (ws://, file://) is a typo, not a server.
            trimmed.contains("://") -> return null

            else -> defaultScheme(schemeless)
        }

        val authority = schemeless.substringBefore('/')
        val remainder = schemeless.removePrefix(authority)
        val parsed = "$scheme://${bracketIpv6(authority)}$remainder".toHttpUrlOrNull() ?: return null

        return if (hasExplicitPort(authority) || scheme == "https") {
            parsed
        } else {
            // Only http gets 11434 imposed. An https deployment is behind a
            // proxy by definition, and that proxy is on 443.
            parsed.newBuilder().port(DEFAULT_PORT).build()
        }
    }

    /** As [parseOrNull], for a configured server. */
    fun baseUrlOrNull(server: ServerRef): HttpUrl? = parseOrNull(server.baseUrl)

    /**
     * Joins an API [path] onto [server]'s base URL, preserving any path prefix.
     *
     * The join is done on the encoded path rather than with `addPathSegment`
     * so that a trailing slash on the base and a leading slash on the path
     * cannot combine into an empty segment.
     */
    fun resolveOrNull(server: ServerRef, path: String): HttpUrl? =
        baseUrlOrNull(server)?.let { base -> resolveOrNull(base, path) }

    /** As [resolveOrNull], against an already parsed [base]. */
    fun resolveOrNull(base: HttpUrl, path: String): HttpUrl {
        val prefix = base.encodedPath.trimEnd('/')
        val suffix = path.trim('/')
        return base.newBuilder().encodedPath("$prefix/$suffix").build()
    }

    /** The typed failure for a base URL the user has to fix. */
    fun malformed(server: ServerRef): AppError = AppError.Unexpected(
        message = "\"${server.label}\" does not have a usable address.",
    )

    /**
     * `http` for anything that is unambiguously on a private network, `https`
     * otherwise.
     *
     * A Raspberry Pi on the LAN is plain HTTP and demanding TLS would make the
     * app useless for its most common deployment; a hostname on the public
     * internet defaulting to cleartext would be the opposite mistake.
     */
    private fun defaultScheme(authority: String): String {
        val host = hostOf(authority)
        return if (isPrivateLiteral(host)) "http" else "https"
    }

    private fun hostOf(authority: String): String {
        val bare = authority.substringBefore('/').substringAfterLast('@')
        return when {
            bare.startsWith("[") -> bare.substringAfter('[').substringBefore(']')

            // An unbracketed authority with several colons is a bare IPv6
            // literal, so there is no port to strip and `substringBefore(':')`
            // would return the empty string.
            bare.count { it == ':' } >= 2 -> bare

            else -> bare.substringBefore(':')
        }
    }

    @Suppress("MagicNumber") // RFC 1918 octets read better as themselves.
    private fun isPrivateLiteral(host: String): Boolean = when {
        host.equals("localhost", ignoreCase = true) -> true
        host.contains(':') -> isPrivateIpv6Literal(host)
        else -> isPrivateIpv4Literal(host.split('.'))
    }

    @Suppress("MagicNumber") // RFC 1918 octets read better as themselves.
    private fun isPrivateIpv4Literal(octets: List<String>): Boolean {
        if (octets.size != 4) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return numbers[0] == 127 ||
            numbers[0] == 10 ||
            (numbers[0] == 172 && numbers[1] in 16..31) ||
            (numbers[0] == 192 && numbers[1] == 168) ||
            (numbers[0] == 169 && numbers[1] == 254)
    }

    /**
     * IPv6 loopback, unique-local (`fc00::/7`) and link-local (`fe80::/10`).
     *
     * Without this, `::1` would default to `https` and port 443 — which is
     * exactly wrong for the loopback case that the embedded server occupies.
     */
    private fun isPrivateIpv6Literal(host: String): Boolean {
        val bare = host.removeSurrounding("[", "]").lowercase().substringBefore('%')
        if (bare == "::1") return true
        val firstGroup = bare.substringBefore(':')
        return firstGroup.startsWith("fd") ||
            firstGroup.startsWith("fc") ||
            firstGroup.startsWith("fe8") ||
            firstGroup.startsWith("fe9") ||
            firstGroup.startsWith("fea") ||
            firstGroup.startsWith("feb")
    }

    /**
     * True when [authority] carries a `:port` the user actually wrote.
     *
     * The IPv6 case is why this cannot be `contains(':')`: `::1` is all colons
     * and no port, and `[::1]:11434` has its port after the bracket.
     */
    private fun hasExplicitPort(authority: String): Boolean {
        val hostAndPort = authority.substringAfter('@')
        val afterBracket = hostAndPort.substringAfterLast(']', missingDelimiterValue = "")
        return if (hostAndPort.startsWith("[")) {
            afterBracket.startsWith(":")
        } else {
            hostAndPort.count { it == ':' } == 1
        }
    }

    /** Wraps a bare IPv6 literal in the brackets the URL grammar requires. */
    private fun bracketIpv6(authority: String): String {
        val credentials = authority.substringBeforeLast('@', missingDelimiterValue = "")
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.startsWith("[") || hostAndPort.count { it == ':' } < 2) return authority
        val prefix = if (credentials.isEmpty()) "" else "$credentials@"
        return "$prefix[$hostAndPort]"
    }
}
