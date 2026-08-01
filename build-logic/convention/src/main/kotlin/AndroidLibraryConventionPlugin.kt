import com.android.build.api.dsl.LibraryExtension
import internal.configureAndroidCommon
import internal.configureBenchmarkBuildType
import internal.intVersion
import internal.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        // Kotlin is built into AGP 9; applying org.jetbrains.kotlin.android
        // here is a hard error.
        pluginManager.apply("ollamamobile.quality")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            configureBenchmarkBuildType(this)

            defaultConfig {
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }

            buildFeatures {
                buildConfig = true
            }

            // Library modules never ship an applicationId; targetSdk on
            // libraries is deprecated and only affects lint.
            @Suppress("UnstableApiUsage")
            testOptions.targetSdk = libs.intVersion("targetSdk")
            lint.targetSdk = libs.intVersion("targetSdk")
        }
    }
}
