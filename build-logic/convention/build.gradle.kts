plugins {
    `kotlin-dsl`
}

group = "io.github.jaypetez.ollamamobile.buildlogic"

// build-logic is an included build: it resolves its own plugin classpath
// independently of the root project. The Kotlin version here must therefore be
// declared explicitly and must match `kotlin` in gradle/libs.versions.toml,
// otherwise the convention plugins compile against a different Kotlin than the
// one they configure. Verify with `./gradlew buildEnvironment`.
kotlin {
    jvmToolchain(libs.versions.javaToolchain.get().toInt())

    compilerOptions {
        // `kotlin-dsl` compiles this project with Gradle's *embedded* Kotlin,
        // which is older than the Kotlin we build the app with. The AGP, KGP
        // and Room plugin jars on the classpath below carry newer metadata, so
        // without this flag the compiler refuses to read them:
        //
        //   "Module was compiled with an incompatible version of Kotlin.
        //    The binary version of its metadata is 2.3.0, expected 2.0.0"
        //
        // Reading the newer metadata is safe here: we only touch stable Gradle
        // plugin DSL surfaces, never Kotlin-version-specific internals.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    compileOnly(libs.gradle.android)
    compileOnly(libs.gradle.kotlin)
    compileOnly(libs.gradle.room)
    implementation(libs.gradle.spotless)
    // NOTE: the KSP and Compose-compiler plugins are deliberately absent.
    // They are applied by plugin id and configured from the modules
    // themselves, so build-logic never has to compile against their types.
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "ollamamobile.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "ollamamobile.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "ollamamobile.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "ollamamobile.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "ollamamobile.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("androidNative") {
            id = "ollamamobile.android.native"
            implementationClass = "AndroidNativeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "ollamamobile.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("serialization") {
            id = "ollamamobile.serialization"
            implementationClass = "SerializationConventionPlugin"
        }
        register("testJunit5") {
            id = "ollamamobile.test.junit5"
            implementationClass = "TestJunit5ConventionPlugin"
        }
        register("quality") {
            id = "ollamamobile.quality"
            implementationClass = "QualityConventionPlugin"
        }
        register("moduleGraph") {
            id = "ollamamobile.module.graph"
            implementationClass = "ModuleGraphConventionPlugin"
        }
    }
}
