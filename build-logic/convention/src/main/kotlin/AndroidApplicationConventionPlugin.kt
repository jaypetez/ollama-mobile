import com.android.build.api.dsl.ApplicationExtension
import internal.Abis
import internal.configureAndroidCommon
import internal.NativeSource
import internal.intVersion
import internal.libs
import internal.version
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

        val nativeSource = NativeSource.from(this)

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)

            // The application module packages the .so files produced by
            // :core-llm, so it — not :core-llm — is where AGP runs
            // stripDebugSymbols and extractNativeDebugMetadata, and both need
            // llvm-strip / llvm-objcopy out of the NDK.
            //
            // Without this, AGP falls back to its own default NDK version. That
            // version is not the one in libs.versions.toml, is therefore not
            // installed, and AGP degrades to a *warning* rather than an error:
            //   "Unable to strip the following libraries, packaging them as
            //    they are"
            // followed by `mergeReleaseNativeDebugMetadata NO-SOURCE`. The
            // release then silently ships tens of megabytes of unstripped
            // libraries and carries no native symbols at all — which is only
            // noticed when someone tries to symbolicate a native crash and
            // finds there is nothing to symbolicate with.
            //
            // Set only when there is native code to strip, so the default
            // `nativeSource=none` path still builds on a machine with no NDK.
            if (nativeSource.enabled) {
                ndkVersion = libs.version("ndk")
            }

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
