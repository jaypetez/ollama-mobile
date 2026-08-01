import com.android.build.api.dsl.LibraryExtension
import internal.NativeSource
import internal.libs
import internal.version
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Wires llama.cpp into `:core-llm`.
 *
 * The mode is chosen by `-Pollama.nativeSource`:
 *
 *  * `build`    — configure `externalNativeBuild` against
 *                 `core-llm/src/main/cpp/CMakeLists.txt`, which in turn pulls in
 *                 `third_party/llama.cpp`. Requires the NDK and an initialised
 *                 submodule.
 *  * `prebuilt` — no CMake at all; `.so` files are picked up from
 *                 `core-llm/prebuilt/<abi>/`. This is what ordinary CI PR jobs
 *                 use so they never pay the llama.cpp compile cost.
 *  * `none`     — no native code. `BuildConfig.NATIVE_ENABLED` is false and
 *                 `StubLlamaEngine` is bound, so a fresh clone builds and runs
 *                 as a pure remote Ollama client with no NDK installed.
 *
 * `none` is the default. That is deliberate: it is what makes `./gradlew
 * :app:assembleDebug` work on a machine that has never seen the NDK, which in
 * turn is what keeps CodeQL, lint, and unit-test CI jobs fast.
 */
class AndroidNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val source = NativeSource.from(this)
        val requireNative = providers
            .gradleProperty("ollama.requireNative").orNull.toBoolean()

        if (requireNative && !source.enabled) {
            throw GradleException(
                "-Pollama.requireNative=true but -Pollama.nativeSource=none. " +
                    "Pass -Pollama.nativeSource=build (needs the NDK and " +
                    "`git submodule update --init third_party/llama.cpp`) or " +
                    "-Pollama.nativeSource=prebuilt.",
            )
        }

        extensions.configure<LibraryExtension> {
            defaultConfig {
                buildConfigField("boolean", "NATIVE_ENABLED", source.enabled.toString())
                buildConfigField("String", "NATIVE_SOURCE", "\"${source.name.lowercase()}\"")
            }

            when (source) {
                NativeSource.BUILD -> {
                    val cmakeLists = file("src/main/cpp/CMakeLists.txt")
                    if (!cmakeLists.exists()) {
                        throw GradleException(
                            "-Pollama.nativeSource=build but ${cmakeLists.path} is missing.",
                        )
                    }
                    val submodule = rootProject.file("third_party/llama.cpp/CMakeLists.txt")
                    if (!submodule.exists()) {
                        throw GradleException(
                            "third_party/llama.cpp is not initialised. Run:\n" +
                                "  git submodule update --init --depth 1 third_party/llama.cpp",
                        )
                    }

                    ndkVersion = libs.version("ndk")

                    defaultConfig {
                        externalNativeBuild {
                            cmake {
                                arguments += listOf(
                                    "-DANDROID_STL=c++_shared",
                                    "-DCMAKE_BUILD_TYPE=Release",
                                    // llama.cpp / ggml switches. See
                                    // docs/local-inference/native-build.md for
                                    // why each one is set the way it is.
                                    "-DBUILD_SHARED_LIBS=ON",
                                    "-DGGML_BACKEND_DL=ON",
                                    "-DGGML_CPU_ALL_VARIANTS=ON",
                                    "-DGGML_NATIVE=OFF",
                                    "-DGGML_OPENMP=OFF",
                                    "-DGGML_LLAMAFILE=OFF",
                                    "-DGGML_CPU_KLEIDIAI=ON",
                                    "-DLLAMA_BUILD_COMMON=ON",
                                    "-DLLAMA_BUILD_TESTS=OFF",
                                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                                    "-DLLAMA_BUILD_TOOLS=OFF",
                                    "-DLLAMA_BUILD_SERVER=OFF",
                                )
                                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                            }
                        }
                    }

                    externalNativeBuild {
                        cmake {
                            path = cmakeLists
                            version = libs.version("cmake")
                        }
                    }
                }

                NativeSource.PREBUILT -> {
                    sourceSets.getByName("main") {
                        jniLibs.srcDirs("prebuilt")
                    }
                }

                NativeSource.NONE -> Unit
            }
        }
    }
}
