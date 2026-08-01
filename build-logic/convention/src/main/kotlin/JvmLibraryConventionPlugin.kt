import internal.configureJunit5
import internal.configureKotlinJvmTarget
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A pure-JVM Kotlin module: no Android SDK, no manifest, no resources.
 *
 * `:core-model` and `:core-llm-api` use this so they compile and test in
 * milliseconds and can be exercised with plain JUnit 5 — no Robolectric, no
 * emulator. Keeping the inference contract Android-free is also what lets
 * `:server` depend on it without dragging the app stack along.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("ollamamobile.quality")

        configureKotlinJvmTarget()
        configureJunit5()
    }
}
