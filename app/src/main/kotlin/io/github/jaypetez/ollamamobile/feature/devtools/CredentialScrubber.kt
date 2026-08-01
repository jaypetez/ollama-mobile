package io.github.jaypetez.ollamamobile.feature.devtools

/**
 * Removes anything that looks like a credential from text that is about to be
 * rendered or shared.
 *
 * `ApiInspector` already redacts *headers* by name, and that is the primary
 * control. This is the second one, and it exists because the developer tools
 * are the one screen whose whole purpose is to show raw protocol text: a token
 * can also arrive in a request body, in a URL's userinfo, or in a log line
 * somebody wrote with `Timber.d("sending %s", request)`. Every one of those
 * ends up in a bug report the moment the export button is pressed.
 *
 * The rules are deliberately over-eager. A false positive costs a debugging
 * session; a false negative costs a leaked token in a public issue.
 */
object CredentialScrubber {
    const val REDACTION: String = "[REDACTED]"

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Authorization: Bearer <token>  /  authorization=<token>
        Regex("(?i)\\b(authorization|proxy-authorization|cookie|set-cookie)(\\s*[:=]\\s*)\\S.*") to "$1$2$REDACTION",
        // A bare bearer token anywhere.
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}") to "Bearer $REDACTION",
        // key/value forms in JSON, query strings and log lines.
        Regex(
            "(?i)\\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)" +
                "(\"?\\s*[:=]\\s*\"?)([^\\s\"',&}]+)",
        ) to "$1$2$REDACTION",
        // Credentials embedded in a URL: scheme://user:password@host
        Regex("://([^/@\\s:]+):([^/@\\s]+)@") to "://$1:$REDACTION@",
    )

    fun scrub(text: String): String = RULES.fold(text) { acc, (pattern, replacement) ->
        pattern.replace(acc, replacement)
    }

    fun scrubOrNull(text: String?): String? = text?.let(::scrub)
}
