package io.github.jaypetez.ollamamobile.storage

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * `fallbackToDestructiveMigration` is banned in this module.
 *
 * It converts "someone shipped a schema change without a migration" — a bug
 * caught by the first person to update the app — into "every conversation the
 * user ever had is gone", which is not recoverable and not noticed until they
 * look. A crash on open is loud, reportable and fixable; silent data loss is
 * none of those.
 *
 * This is a source scan rather than a runtime assertion because the builder
 * call is the thing being banned, and by the time a database instance exists
 * the flag has already been set. It is also the only form of the check that
 * fails at *review* time on a new call site rather than only on the upgrade
 * path that triggers it.
 */
class NoDestructiveMigrationTest {
    @Test
    fun `no source file calls fallbackToDestructiveMigration`() {
        val offenders = kotlinSources()
            .filter { stripComments(it.readText()).contains("fallbackToDestructiveMigration") }
            .map { it.name }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the schema for the current version is exported`() {
        // MigrationTestHelper can only verify an upgrade path against a
        // committed schema JSON, so a missing export is a migration test that
        // silently cannot exist.
        val schema = File(moduleRoot(), "schemas/$DATABASE_CLASS/${OllamaDatabase.VERSION}.json")

        assertThat(schema.isFile).isTrue()
        assertThat(schema.readText()).contains("\"version\": ${OllamaDatabase.VERSION}")
    }

    /**
     * The doc comment on `OllamaDatabase.build` explains why the call is
     * banned, so a naive text search matches the explanation. Stripping
     * comments keeps the ban on the code and off the prose.
     */
    private fun stripComments(source: String): String = source
        .replace(BLOCK_COMMENT, "")
        .replace(LINE_COMMENT, "")

    private fun kotlinSources(): List<File> {
        val sourceRoot = File(moduleRoot(), "src/main/kotlin")
        check(sourceRoot.isDirectory) { "Could not locate src/main/kotlin from ${moduleRoot()}" }
        return sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** The test working directory is normally the module directory; do not rely on it blindly. */
    private fun moduleRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            listOf(candidate, File(candidate, MODULE_NAME))
                .firstOrNull { File(it, MARKER).isDirectory }
                ?.let { return it }
            candidate = candidate.parentFile
        }
        error("Could not locate the :core-storage module directory from ${File("").absolutePath}")
    }

    private companion object {
        const val DATABASE_CLASS = "io.github.jaypetez.ollamamobile.storage.OllamaDatabase"
        const val MODULE_NAME = "core-storage"
        const val MARKER = "src/main/kotlin/io/github/jaypetez/ollamamobile/storage"
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//.*""")
    }
}
