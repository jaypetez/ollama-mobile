plugins {
    alias(libs.plugins.ollamamobile.android.library)
    alias(libs.plugins.ollamamobile.android.hilt)
    alias(libs.plugins.ollamamobile.android.native)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.llm"
}

// The only module that sees llama.cpp. Everything else depends on
// :core-llm-api, which is why the whole app still builds and runs with
// -Pollama.nativeSource=none.
dependencies {
    api(project(":core-llm-api"))
    implementation(project(":core-common"))
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

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
