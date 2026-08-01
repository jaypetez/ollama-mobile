package io.github.jaypetez.ollamamobile.common

import com.google.common.truth.Truth.assertWithMessage
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Repository-wide invariants, enforced across every module's production source.
 *
 * These three rules are what make `LanOnlyGuard` a *control* rather than a
 * suggestion. The guard is installed on exactly one `OkHttpClient`; any second
 * client, any custom trust decision, or any hand-rolled socket is a path to the
 * network the guard does not see. Reviewing for that by eye works until the
 * afternoon it does not, so it is asserted here instead.
 *
 * The scope is deliberately the *whole repository* rather than this module. As
 * modules land, they land already covered; a rule scoped to `:core-common`
 * would pass forever while `:core-remote` quietly opened a second client.
 */
@RunWith(JUnit4::class)
class ArchitectureTest {
    @Test
    fun `only HttpClientModule constructs an OkHttpClient`() {
        val offenders = productionFiles()
            .filter { it.name != HTTP_CLIENT_MODULE }
            .filter { OKHTTP_CONSTRUCTION.containsMatchIn(it.code()) }

        assertWithMessage(
            "A second OkHttpClient is a second connection pool and a path to the network that " +
                "LanOnlyGuard does not police. Derive from the shared client with newBuilder() instead.",
        ).that(offenders.map { it.relativePath() }).isEmpty()
    }

    @Test
    fun `no custom X509TrustManager or HostnameVerifier exists anywhere`() {
        val offenders = productionFiles()
            .filter { TRUST_OVERRIDE.containsMatchIn(it.code()) }

        assertWithMessage(
            "Certificate pinning against a server the user typed in at runtime is meaningless, and " +
                "every custom TrustManager in the wild is one refactor away from being an accept-all. " +
                "Use the platform trust manager; pin per server with OkHttp's CertificatePinner if a " +
                "pin is genuinely needed.",
        ).that(offenders.map { it.relativePath() }).isEmpty()
    }

    @Test
    fun `no bare java-net Socket usage`() {
        val offenders = productionFiles().filter { file ->
            val code = file.code()
            SOCKET_IMPORT.containsMatchIn(code) || SOCKET_CONSTRUCTION.containsMatchIn(code)
        }

        assertWithMessage(
            "A raw socket bypasses the Dns, Interceptor and EventListener layers of LanOnlyGuard " +
                "entirely. Go through the shared OkHttpClient; if a raw connection is unavoidable, " +
                "classify the peer with LanOnlyGuard.classify() first and say so here.",
        ).that(offenders.map { it.relativePath() }).isEmpty()
    }

    @Test
    fun `the scope actually covers the modules that exist today`() {
        // A rule that silently matches nothing is worse than no rule. If the
        // scan ever stops finding sources — a Konsist upgrade, a layout change
        // — these assertions catch it instead of the suite going green empty.
        val modules = productionFiles().mapNotNull { it.moduleName() }.toSet()

        assertWithMessage("Konsist found no production sources at all")
            .that(productionFiles())
            .isNotEmpty()
        assertWithMessage("Konsist scope is missing a module that has sources today")
            .that(modules)
            .containsAtLeast("core-model", "core-common")
    }

    private fun productionFiles(): List<KoFileDeclaration> = SCOPE_FILES

    /**
     * The file's source with comments removed.
     *
     * Necessary, not fastidious: the KDoc on `ServerRef` explains *why* there is
     * no trust-all `X509TrustManager`, and a rule that fires on the prose
     * forbidding a thing punishes documenting it.
     */
    private fun KoFileDeclaration.code(): String = text
        .replace(BLOCK_COMMENT, " ")
        .replace(LINE_COMMENT, "")

    private fun KoFileDeclaration.normalisedPath(): String = path.replace('\\', '/')

    private fun KoFileDeclaration.relativePath(): String =
        normalisedPath().substringAfterLast("/ollama-mobile/", normalisedPath())

    private fun KoFileDeclaration.moduleName(): String? =
        normalisedPath().substringBefore("/src/main/", "").substringAfterLast('/').takeIf { it.isNotEmpty() }

    private companion object {
        const val HTTP_CLIENT_MODULE = "HttpClientModule"

        /**
         * Scanned once: Konsist parses every Kotlin file in the repository, so
         * doing it per test would quadruple the cost of this class.
         */
        val SCOPE_FILES: List<KoFileDeclaration> = Konsist
            .scopeFromProject()
            .files
            .filter { it.path.replace('\\', '/').contains("/src/main/") }

        /**
         * The negative lookbehind keeps `provideOkHttpClient(` and friends from
         * matching: only an actual construction expression counts.
         */
        val OKHTTP_CONSTRUCTION = Regex("""(?<![A-Za-z0-9_])OkHttpClient\s*(\.\s*Builder\s*)?\(""")

        val TRUST_OVERRIDE = Regex("""X509TrustManager|X509ExtendedTrustManager|HostnameVerifier|TrustManagerFactory""")

        val SOCKET_IMPORT = Regex("""^\s*import\s+java\.net\.(Server)?Socket\s*$""", RegexOption.MULTILINE)

        val SOCKET_CONSTRUCTION = Regex("""(?<![A-Za-z0-9_])(Server)?Socket\s*\(""")

        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Anchored at a line start or after whitespace so the `//` inside a
         * `http://` literal does not swallow the rest of the line.
         */
        val LINE_COMMENT = Regex("""(^|\s)//[^\n]*""", RegexOption.MULTILINE)
    }
}
