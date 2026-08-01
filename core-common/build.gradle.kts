plugins {
    alias(libs.plugins.ollamamobile.android.library)
    alias(libs.plugins.ollamamobile.android.hilt)
    alias(libs.plugins.ollamamobile.serialization)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.common"
}

// The substrate every other Android module sits on: the single OkHttpClient,
// the network policy that enforces offline / LAN-only mode, structured logging,
// crash capture and the API inspector.
dependencies {
    api(project(":core-model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // The network policy needs its own tiny preferences file: :core-storage
    // depends on :core-common, so the settings store cannot be borrowed from
    // there without inverting the module graph.
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    // Architecture tests: no second OkHttpClient, no custom TrustManager,
    // no bare Socket anywhere in the codebase.
    testImplementation(libs.konsist)
}
