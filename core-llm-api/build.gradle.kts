plugins {
    alias(libs.plugins.ollamamobile.jvm.library)
}

// The inference contract: LlamaEngine, GenerationRequest, GenerationEvent,
// InferenceGateway. Deliberately pure JVM so that
//   * :server can depend on it without pulling in the app data stack, and
//   * every consumer can be unit-tested against FakeLlamaEngine with no
//     device, no NDK and no Robolectric.
kotlin {
    explicitApi()
}

dependencies {
    api(project(":core-model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
