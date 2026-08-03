plugins {
    // Kotlin comes from AGP 9's built-in support; the standalone
    // org.jetbrains.kotlin.android plugin must not be applied.
    alias(libs.plugins.android.test)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(libs.versions.javaToolchain.get().toInt())
    }

    defaultConfig {
        // Macrobenchmark drives a real device or emulator; API 29 devices
        // cannot run it (it needs 29+ for most metrics, 31+ for power).
        minSdk = 29
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"

        // Macrobenchmark refuses to run in conditions that make numbers
        // meaningless, and an x86_64 emulator on a shared CI runner trips
        // several of those checks. Suppressing them is only defensible because
        // the nightly job is explicitly a *relative* regression signal on
        // identical infrastructure — see docs/benchmarking/nightly.md. Each
        // suppression is listed individually rather than with a blanket flag so
        // that adding a new one is a visible decision:
        //
        //   EMULATOR         — the nightly runner has no physical device.
        //   UNLOCKED         — CI devices are not screen-locked.
        //   LOW-BATTERY      — an emulator reports a synthetic battery level.
        //   NOT-PROFILEABLE  — belt and braces; :app does declare
        //                      <profileable android:shell="true"/>, so this
        //                      should never fire and is here to keep a
        //                      manifest-merge accident from failing the whole
        //                      nightly instead of one metric.
        //
        // ACTIVITY-MISSING and DEBUGGABLE are deliberately NOT suppressed:
        // those two mean the harness measured the wrong thing.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,UNLOCKED,LOW-BATTERY,NOT-PROFILEABLE"

        // A run that has to be reproduced needs its raw traces, not just the
        // summary line. Storage on a hosted runner is free; a lost trace is not.
        testInstrumentationRunnerArguments["androidx.benchmark.profiling.mode"] = "None"
    }

    buildTypes {
        // Macrobenchmark measures a release-like build; a debug build's numbers
        // are meaningless. `benchmark` is release + debug signing so it is
        // installable without a release key.
        create("benchmark") {
            isDebuggable = false
            matchingFallbacks += listOf("release")

            // Without this the APK is unsigned and the install fails with
            // INSTALL_PARSE_FAILED_NO_CERTIFICATES — which is exactly how the
            // nightly job failed on its first two real runs. AGP attaches the
            // debug signing config to the `debug` build type only; a type
            // created from scratch gets none, and `create` (unlike the
            // `initWith(release)` the library and application plugins use)
            // inherits nothing to fall back on. :app's own `benchmark` type
            // says the same thing in AndroidApplicationConventionPlugin.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) {
        // Only the `benchmark` variant is meaningful here.
        it.enable = it.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.espresso.core)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.junit4)
}
