plugins {
    alias(libs.plugins.ollamamobile.android.library)
    alias(libs.plugins.ollamamobile.android.hilt)
    alias(libs.plugins.ollamamobile.serialization)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.server"
}

// The embedded Ollama-compatible HTTP server.
//
// It depends on the InferenceGateway *interface* from :core-llm-api and on
// :core-remote for DTO reuse — never on :core-data, :core-storage or
// :core-llm. checkModuleGraph enforces that. The concrete gateway is bound at
// :app assembly, which is what keeps the server hostable without dragging in
// Room, WorkManager and the downloader.
dependencies {
    implementation(project(":core-llm-api"))
    implementation(project(":core-remote"))
    implementation(project(":core-common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(project(":core-llm-testing"))
}
