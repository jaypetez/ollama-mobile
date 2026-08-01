import com.android.build.api.dsl.CommonExtension
import internal.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Enables Compose and adds the shared Compose dependency set.
 *
 * Compose-compiler tuning (stability config, metrics and reports) is
 * configured in the consuming module's own build script rather than here:
 * build-logic compiles against Gradle's embedded Kotlin, which is older than
 * the project Kotlin that owns `ComposeCompilerGradlePluginExtension`.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val extension = extensions.findByName("android") as? CommonExtension
            ?: error("ollamamobile.android.compose must be applied after an Android plugin")

        extension.buildFeatures.compose = true

        dependencies {
            val bom = platform(libs.findLibrary("compose-bom").get())
            add("implementation", bom)
            add("androidTestImplementation", bom)

            add("implementation", libs.findLibrary("compose-foundation").get())
            add("implementation", libs.findLibrary("compose-material3").get())
            add("implementation", libs.findLibrary("compose-ui").get())
            add("implementation", libs.findLibrary("compose-ui-graphics").get())
            add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("kotlinx-collections-immutable").get())

            add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
            add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())

            add("androidTestImplementation", libs.findLibrary("compose-ui-test-junit4").get())
        }
    }
}
