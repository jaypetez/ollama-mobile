plugins {
    alias(libs.plugins.ollamamobile.android.library)
    alias(libs.plugins.ollamamobile.android.hilt)
    alias(libs.plugins.ollamamobile.android.room)
    alias(libs.plugins.ollamamobile.serialization)
}

android {
    namespace = "io.github.jaypetez.ollamamobile.storage"
}

dependencies {
    api(project(":core-model"))
    implementation(project(":core-common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
