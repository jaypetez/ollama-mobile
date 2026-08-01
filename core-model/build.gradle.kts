plugins {
    alias(libs.plugins.ollamamobile.jvm.library)
    alias(libs.plugins.ollamamobile.serialization)
}

// Pure Kotlin. No Android, no native, no I/O — just the vocabulary every other
// module speaks. Keeping this dependency-free is what makes it safe for
// :core-llm-api and :server to depend on.
kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
