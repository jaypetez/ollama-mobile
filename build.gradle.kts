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
}

/**
 * The single required status check on `main`.
 *
 * Branch protection points at `ci-ok` rather than at individual jobs so
 * workflows can be reorganised without touching repository settings. Anything
 * that must gate a merge belongs here.
 */
tasks.register("ciOk") {
    group = "verification"
    description = "Aggregate gate: everything that must pass before a merge."
    dependsOn(
        gradle.includedBuild("build-logic").task(":convention:build"),
    )
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
