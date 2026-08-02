package internal

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Raise any 4.1.x netty on this module's classpaths to a patched version.
 *
 * ## What this is
 *
 * Nothing in this repository declares netty. It arrives on the tooling
 * classpaths AGP contributes to every Android module, which is why the OSV scan
 * reports it from all ten Android modules and from none of the three pure-JVM
 * ones. Three advisories currently land there: GHSA-xpw8-rcwv-8f8p (HTTP/2
 * Rapid Reset, high, fixed in 4.1.100), CVE-2024-47535 (fixed in 4.1.115) and
 * CVE-2026-42581 (request smuggling, fixed in 4.1.133).
 *
 * ## What this is not
 *
 * It is not a fix for a vulnerability in the shipped app, because netty is not
 * in the shipped app: `:server` speaks HTTP through Ktor's CIO engine, which
 * has no netty in it, and no module declares a netty dependency to begin with.
 * Treat this as keeping the build's own toolchain current and the scan
 * trustworthy, not as having closed a hole a user could be attacked through.
 * The moment it stops being true that netty is build-only -- someone swaps CIO
 * for the Netty engine, say -- this becomes load-bearing, which is the other
 * reason to set the floor now rather than suppress the finding.
 *
 * Scoped to the 4.1.x line on purpose. Netty 4.2 is a separate line with its
 * own advisories and its own patched versions; silently dragging a 4.2
 * dependency onto a 4.1 release would be a downgrade.
 */
private fun Project.configureNettyFloor() {
    val floor = libs.version("netty")
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true) {
                useVersion(floor)
                because("patched floor for GHSA-xpw8-rcwv-8f8p, CVE-2024-47535 and CVE-2026-42581")
            }
        }
    }
}

/**
 * Shared `android { }` configuration for both application and library modules.
 *
 * NOTE on style: AGP 9's `CommonExtension` exposes `defaultConfig`,
 * `compileOptions`, `packaging`, `testOptions` and `lint` as plain getters —
 * the lambda-block overloads that existed in AGP 8 are gone. Hence the
 * `.apply { }` form throughout rather than `defaultConfig { }`.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    configureNettyFloor()

    extension.compileSdk = libs.intVersion("compileSdk")
    extension.compileSdkMinor = libs.intVersion("compileSdkMinor")

    extension.defaultConfig.apply {
        minSdk = libs.intVersion("minSdk")
    }

    extension.compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Needed at minSdk 29 for java.time and the desugared java.nio APIs
        // the GGUF parser and log rotation rely on.
        isCoreLibraryDesugaringEnabled = true
    }

    extension.packaging.apply {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
        )
        jniLibs.apply {
            // Extract native libs at install time so ggml's own
            // directory-scanning backend loader can enumerate the CPU variants
            // in `applicationInfo.nativeLibraryDir`.
            //
            // This does NOT weaken 16 KB page-size compliance: that is a
            // property of ELF LOAD segment alignment (-Wl,-z,max-page-size=
            // 16384, emitted by NDK r28+), not of how the .so is stored in the
            // APK. Zip alignment only matters for uncompressed-in-APK loading.
            useLegacyPackaging = true
        }
    }

    extension.testOptions.unitTests.apply {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
    }

    extension.lint.apply {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        xmlReport = true
        htmlReport = true
        sarifReport = true
        lintConfig = rootProject.file("config/lint/lint.xml")
        // Deliberately no baseline. The project starts clean, and a baseline
        // file is how a "clean" lint report quietly stops meaning anything.
        // If a genuinely un-fixable warning appears, suppress it in lint.xml
        // with a comment explaining why.
    }

    configureKotlinJvmTarget()

    dependencies.add("coreLibraryDesugaring", DESUGAR_JDK_LIBS)
}

private const val DESUGAR_JDK_LIBS = "com.android.tools:desugar_jdk_libs:2.1.5"

/** Applies the same JVM target and compiler flags to Android and pure-JVM modules. */
internal fun Project.configureKotlinJvmTarget() {
    val target = JvmTarget.fromTarget(libs.version("jvmTarget"))
    val toolchain = libs.intVersion("javaToolchain")

    extensions.findByType(KotlinAndroidProjectExtension::class.java)?.jvmToolchain(toolchain)
    extensions.findByType(KotlinJvmProjectExtension::class.java)?.jvmToolchain(toolchain)
    extensions.findByType(JavaPluginExtension::class.java)?.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val warningsAsErrors = providers
        .gradleProperty("ollama.warningsAsErrors")
        .map { it.toBoolean() }
        .orElse(false)

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(target)
            allWarningsAsErrors.set(warningsAsErrors)
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
            )
        }
    }

    tasks.withType<Test>().configureEach {
        // One fork per test task, deliberately.
        //
        // `org.gradle.parallel=true` already runs several modules' test tasks
        // at once, so forking within each multiplies: on an 8-core machine,
        // cores/2 forks across six modules is 24 test JVMs competing for 8
        // cores. That starves the MockWebServer suites — their clients hit
        // connect and read deadlines that are generous for a responsive machine
        // and far too tight for an oversubscribed one — and produces failures
        // that move around between runs and vanish when the module is run
        // alone. Which is the worst possible failure mode: it reads as a real
        // bug, and it trains people to re-run CI instead of reading it.
        //
        // Gradle's cross-module parallelism is the right level to exploit the
        // cores at. Hosted CI runners have 2-4 of them anyway.
        maxParallelForks = 1
        // Gradle 9 fails a test task that discovers nothing. Modules legitimately
        // start out without tests, and `./gradlew test` across the whole repo
        // should not break because of that. Per-module coverage is enforced by
        // Kover thresholds in CI, which is a real check rather than a proxy.
        failOnNoDiscoveredTests.set(false)
        testLogging {
            events("failed", "skipped")
            showStackTraces = true
            showExceptions = true
        }
    }
}

/**
 * Registers the `benchmark` build type on library modules.
 *
 * `:benchmark` is a `com.android.test` module targeting `:app`'s `benchmark`
 * build type. Every library on the graph must offer a matching variant or
 * dependency resolution fails with "no matching variant found".
 */
internal fun Project.configureBenchmarkBuildType(extension: CommonExtension) {
    extension.buildTypes.maybeCreate("benchmark").apply {
        initWith(extension.buildTypes.getByName("release"))
        matchingFallbacks += listOf("release")
    }
}

/** Sets up JUnit 5 (Jupiter) for a pure-JVM module. */
internal fun Project.configureJunit5() {
    dependencies.apply {
        add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
        add("testImplementation", libs.findLibrary("junit-jupiter").get())
        add("testImplementation", libs.findLibrary("junit-jupiter-params").get())
        add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
