import com.diffplug.gradle.spotless.SpotlessExtension
import internal.libs
import internal.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Formatting and static analysis, applied to every Kotlin module.
 *
 * Spotless (with the ktlint engine) is the *blocking* gate — one tool, one
 * Kotlin parser, deterministic output. detekt is configured at the root as a
 * separate, non-blocking job: its current release line is still an alpha built
 * against a different Kotlin than ours, and an alpha static analyser should not
 * be able to break the build.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.diffplug.spotless")
        pluginManager.apply("ollamamobile.module.graph")

        val ktlintVersion = libs.version("ktlint")

        extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
                targetExclude("**/build/**", "**/generated/**")
                ktlint(ktlintVersion).customRuleSets(
                    listOf(
                        "io.nlopez.compose.rules:ktlint:" +
                            libs.version("composeRules"),
                    ),
                )
                // No per-file licence header: the repository is MIT and the
                // root LICENSE covers it. Prepending a banner to every source
                // file adds noise without adding legal effect.
                trimTrailingWhitespace()
                endWithNewline()
            }
            kotlinGradle {
                target("*.gradle.kts")
                ktlint(ktlintVersion)
                trimTrailingWhitespace()
                endWithNewline()
            }
            format("misc") {
                target("*.md", "*.yml", "*.yaml", ".gitignore")
                targetExclude("**/build/**")
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }
}
