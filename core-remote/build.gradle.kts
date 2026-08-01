plugins {
    alias(libs.plugins.ollamamobile.android.library)
    alias(libs.plugins.ollamamobile.android.hilt)
    alias(libs.plugins.ollamamobile.serialization)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.remote"
}

dependencies {
    api(project(":core-model"))
    implementation(project(":core-common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
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
}
