plugins {
    alias(libs.plugins.ollamamobile.android.library)
    alias(libs.plugins.ollamamobile.android.hilt)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.ml"
}

// Device capability detection and numeric kernels.
//
// This module is deliberately NOT an inference accelerator. NNAPI is deprecated
// and neither it nor LiteRT can execute GGUF — there is no format bridge, and
// claiming one would be a lie. What lives here is: CPU feature probing, the
// feature-set -> ggml CPU variant policy, the backend crash quarantine ledger,
// thermal and performance hints, and the int8 vector kernel used by RAG.
dependencies {
    api(project(":core-model"))
    implementation(project(":core-common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
