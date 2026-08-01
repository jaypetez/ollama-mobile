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
    implementation(libs.kotlinx.serialization.json)
    // For HttpRangeGgufSource only, and only as a Call.Factory: the single
    // OkHttpClient is built in :core-common with the network policy, the
    // pinning and the header redaction attached. Never construct one here.
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    // The FTS5 tests run the real bundled SQLite, not Robolectric's. The AAR
    // only carries Android .so files, which a host JVM cannot load, so the
    // `-jvm` variant of the same artifact supplies host natives and the AAR is
    // kept off the unit-test classpath entirely — both variants export the same
    // class names, and whichever wins the ordering decides whether the test
    // exercises the driver we actually ship.
    testImplementation(libs.sqlite.bundled.jvm)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

configurations.configureEach {
    if (name.contains("UnitTest")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    }
}
