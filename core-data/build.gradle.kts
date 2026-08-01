plugins {
    alias(libs.plugins.ollamamobile.android.library)
    alias(libs.plugins.ollamamobile.android.hilt)
    alias(libs.plugins.ollamamobile.serialization)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.data"
}

// Aggregation layer: repositories, the InferenceGateway implementation, the
// smart router that chooses between the local engine and remote servers, and
// the RAG orchestration. The UI talks only to this module.
dependencies {
    api(project(":core-model"))
    api(project(":core-llm-api"))

    implementation(project(":core-common"))
    implementation(project(":core-storage"))
    implementation(project(":core-remote"))
    implementation(project(":core-download"))
    implementation(project(":core-ml"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(project(":core-llm-testing"))
}
