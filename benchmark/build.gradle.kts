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
    }

    buildTypes {
        // Macrobenchmark measures a release-like build; a debug build's numbers
        // are meaningless. `benchmark` is release + debug signing so it is
        // installable without a release key.
        create("benchmark") {
            isDebuggable = false
            matchingFallbacks += listOf("release")
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
