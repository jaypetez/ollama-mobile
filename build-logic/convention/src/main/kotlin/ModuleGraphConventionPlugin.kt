import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * Enforces the module-layering rules from docs/architecture/module-map.md.
 *
 * Each project checks only *its own* declared dependencies. That is what keeps
 * the check compatible with the configuration cache and Isolated Projects — a
 * root task walking `subprojects { configurations }` would be cross-project
 * configuration and would prevent the cache from being stored at all.
 */
class ModuleGraphConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val projectPath = path
        val declared = provider {
            configurations
                .filter { it.name in CHECKED_CONFIGURATIONS }
                .flatMap { config ->
                    config.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                        .map { it.path }
                }
                .distinct()
        }

        val check = tasks.register<CheckModuleGraphTask>("checkModuleGraph") {
            group = "verification"
            description = "Verifies this module's project dependencies obey the layering rules."
            consumer.set(projectPath)
            dependencies.set(declared)
        }

        tasks.matching { it.name == "check" }.configureEach { dependsOn(check) }
    }

    private companion object {
        val CHECKED_CONFIGURATIONS = setOf(
            "api",
            "implementation",
            "compileOnly",
            "runtimeOnly",
        )
    }
}

abstract class CheckModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val consumer: Property<String>

    @get:Input
    abstract val dependencies: ListProperty<String>

    @TaskAction
    fun check() {
        val from = consumer.get()
        val violations = dependencies.get().mapNotNull { to -> violation(from, to) }
        if (violations.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                buildString {
                    appendLine("Module graph violations in $from:")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("See docs/architecture/module-map.md for the layering rules.")
                },
            )
        }
    }

    private fun violation(from: String, to: String): String? = when {
        // 1. Nothing may depend on the application module.
        to == ":app" && from != ":app" ->
            "$from depends on :app. Core and server modules must never depend " +
                "on the application module; move the shared type down into " +
                ":core-model or :core-common."

        // 2. Only :core-llm may see the native engine implementation.
        to == ":core-llm" && from !in NATIVE_CONSUMERS ->
            "$from depends on :core-llm. Depend on :core-llm-api instead so the " +
                "module still compiles and tests with -Pollama.nativeSource=none."

        // 3. :server must stay off the app data stack so it can be hosted
        //    without pulling in Room, WorkManager and the download manager.
        from == ":server" && to in FORBIDDEN_FOR_SERVER ->
            ":server depends on $to. The server talks to InferenceGateway " +
                "(declared in :core-llm-api) and is bound at :app assembly."

        else -> null
    }

    private companion object {
        /** Only the app may wire the concrete native engine into the graph. */
        val NATIVE_CONSUMERS = setOf(":app", ":core-llm", ":benchmark")

        val FORBIDDEN_FOR_SERVER = setOf(
            ":core-data",
            ":core-storage",
            ":core-download",
            ":core-llm",
        )
    }
}
