import java.util.Properties

plugins {
    alias(libs.plugins.ollamamobile.android.application)
    alias(libs.plugins.ollamamobile.android.compose)
    alias(libs.plugins.ollamamobile.android.hilt)
    alias(libs.plugins.ollamamobile.serialization)
}

/**
 * versionName comes from version.txt (owned by release-please).
 * versionCode is derived from it so a tag is the only thing that ever needs
 * bumping: 1.2.3 -> 1_020_300.
 */
val appVersionName: String = rootProject.file("version.txt").readText().trim()
val appVersionCode: Int =
    appVersionName
        .substringBefore('-')
        .split('.')
        .map { it.toInt() }
        .let { (major, minor, patch) -> major * 1_000_000 + minor * 10_000 + patch * 100 }

/**
 * Release signing is opt-in. If neither keystore.properties nor the CI
 * environment variables are present we fall back to debug signing with a loud
 * warning, so a contributor can always run `assembleRelease` locally.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use(::load)
        }
    }
val hasReleaseSigning =
    keystoreProperties.isNotEmpty() ||
        System.getenv("OLLAMA_KEYSTORE_PATH") != null

android {
    namespace = "io.github.jaypetez.ollamamobile"

    defaultConfig {
        applicationId = "io.github.jaypetez.ollamamobile"
        versionCode = appVersionCode
        versionName = appVersionName
    }

    androidResources {
        // Only ship the locales we actually translate. `resourceConfigurations`
        // is deprecated in AGP 9 in favour of this.
        localeFilters += listOf("en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile =
                    file(
                        keystoreProperties.getProperty("storeFile")
                            ?: System.getenv("OLLAMA_KEYSTORE_PATH"),
                    )
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("OLLAMA_KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: System.getenv("OLLAMA_KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("OLLAMA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    logger.warn(
                        "No keystore.properties and no OLLAMA_KEYSTORE_* env vars: " +
                            "signing the release build with the DEBUG key. This " +
                            "artefact must not be published.",
                    )
                    signingConfigs.getByName("debug")
                }
        }
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-common"))
    implementation(project(":core-data"))
    implementation(project(":core-llm-api"))
    implementation(project(":core-remote"))
    implementation(project(":core-storage"))
    implementation(project(":core-download"))
    implementation(project(":core-ml"))
    implementation(project(":server"))

    // The only place the concrete native engine enters the graph. With
    // -Pollama.nativeSource=none this module still resolves; it just binds
    // StubLlamaEngine instead.
    implementation(project(":core-llm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.highlights)
    implementation(libs.coil.compose)
    implementation(libs.timber)

    debugImplementation(project(":core-llm-testing"))

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
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
}
