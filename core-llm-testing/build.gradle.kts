plugins {
    alias(libs.plugins.ollamamobile.jvm.library)
}

// FakeLlamaEngine and friends. Shipped as a normal (not test-only) artifact so
// any module — and the app's debug build — can depend on it to exercise
// inference paths without native code.
dependencies {
    api(project(":core-llm-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
