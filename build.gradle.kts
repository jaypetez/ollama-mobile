plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

/**
 * Coverage aggregation.
 *
 * The child projects are listed explicitly rather than derived from
 * `subprojects`: reaching into other projects at configuration time is what
 * breaks the configuration cache and Isolated Projects.
 */
dependencies {
    listOf(
        ":core-model",
        ":core-common",
        ":core-llm-api",
        ":core-llm",
        ":core-llm-testing",
        ":core-ml",
        ":core-remote",
        ":core-storage",
        ":core-download",
        ":core-data",
        ":server",
        ":app",
    ).forEach { kover(project(it)) }
}

kover {
    reports {
        filters {
            excludes {
                // Generated code: Hilt/Dagger components, Room _Impl classes,
                // Compose previews and BuildConfig. Including them inflates the
                // number without telling us anything.
                classes(
                    "*_Factory", "*_Factory\$*", "*_MembersInjector", "*_HiltModules*",
                    "*_Impl", "*_Impl\$*", "Hilt_*", "*ComposableSingletons*",
                    "*BuildConfig", "*_GeneratedInjector", "dagger.hilt.*",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview", "javax.annotation.processing.Generated")
            }
        }
    }
}

/**
 * detekt runs from the root over all module sources.
 *
 * It is a *non-blocking* signal, not a merge gate: the only release line that
 * understands our Kotlin is still an alpha, and an alpha static analyser must
 * not be able to break the build. Spotless and Android Lint are the gates.
 */
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(
        files(
            subprojects.map { "${it.projectDir}/src/main/kotlin" },
            subprojects.map { "${it.projectDir}/src/test/kotlin" },
        ),
    )
    parallel = true
    ignoreFailures = true
}

// The merge gate is the `ci-ok` *job* in .github/workflows/ci.yml, which
// `needs:` every other job. Branch protection points at that single check so
// workflows can be reorganised without touching repository settings.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
