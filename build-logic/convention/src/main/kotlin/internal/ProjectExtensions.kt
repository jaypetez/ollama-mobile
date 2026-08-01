package internal

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The root `libs` version catalogue, available from any convention plugin. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("Missing version '$alias' in libs.versions.toml") }
        .requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int = version(alias).toInt()

/**
 * How `:core-llm` should obtain its native libraries. Driven purely by the
 * `ollama.nativeSource` Gradle property so the value is a tracked configuration
 * input — deriving it from `file(...).exists()` would make the configuration
 * cache stale the moment the git submodule is initialised.
 */
internal enum class NativeSource {
    /** Compile llama.cpp from the git submodule via CMake. Needs the NDK. */
    BUILD,

    /** Consume prebuilt .so files from `core-llm/prebuilt/<abi>/`. */
    PREBUILT,

    /** No native code; `StubLlamaEngine` is used and the app is remote-only. */
    NONE,
    ;

    val enabled: Boolean get() = this != NONE

    companion object {
        fun from(project: Project): NativeSource {
            val raw = project.providers
                .gradleProperty("ollama.nativeSource")
                .orNull
                ?.trim()
                ?.lowercase()
                ?: "none"
            return when (raw) {
                "build" -> BUILD
                "prebuilt" -> PREBUILT
                "none", "" -> NONE
                else -> error(
                    "Invalid -Pollama.nativeSource=$raw. Expected one of: build, prebuilt, none.",
                )
            }
        }
    }
}

/**
 * The ABIs to package. Release builds are arm64-only as specified; debug builds
 * additionally carry x86_64 because that is the only way to run instrumentation
 * tests on a hosted CI runner or an ordinary desktop emulator.
 */
internal object Abis {
    const val ARM64 = "arm64-v8a"
    const val X86_64 = "x86_64"

    val release = setOf(ARM64)
    val debug = setOf(ARM64, X86_64)
}
