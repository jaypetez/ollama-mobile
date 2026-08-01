import com.android.build.api.dsl.ApplicationExtension
import internal.Abis
import internal.configureAndroidCommon
import internal.intVersion
import internal.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        // NOTE: do NOT apply org.jetbrains.kotlin.android. Since AGP 9.0 Kotlin
        // support is built into the Android plugins, and applying the standalone
        // plugin is a hard error. See https://kotl.in/gradle/agp-built-in-kotlin
        pluginManager.apply("ollamamobile.quality")

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)

            defaultConfig {
                targetSdk = libs.intVersion("targetSdk")
                testInstrumentationRunner =
                    "io.github.jaypetez.ollamamobile.testing.OllamaMobileTestRunner"
                vectorDrawables.useSupportLibrary = true

                ndk {
                    // Overridden per build type below.
                    abiFilters += Abis.release
                }
            }

            buildFeatures {
                buildConfig = true
            }

            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                    isMinifyEnabled = false
                    ndk {
                        // x86_64 in debug only, so emulator instrumentation
                        // tests can exercise the JNI layer.
                        abiFilters.clear()
                        abiFilters += Abis.debug
                    }
                }
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    ndk {
                        abiFilters.clear()
                        abiFilters += Abis.release
                        debugSymbolLevel = "FULL"
                    }
                }
                create("benchmark") {
                    initWith(getByName("release"))
                    // Benchmarks need a release-like build that is still
                    // installable and profileable without a release key.
                    signingConfig = signingConfigs.getByName("debug")
                    matchingFallbacks += listOf("release")
                    isDebuggable = false
                    proguardFiles("benchmark-rules.pro")
                }
            }

            bundle {
                language.enableSplit = false
            }
        }
    }
}
