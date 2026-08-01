pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ollama-mobile"

// ---------------------------------------------------------------------------
// Module graph. See docs/architecture/module-map.md for the dependency rules
// that `checkModuleGraph` enforces.
// ---------------------------------------------------------------------------

// Application
include(":app")

// Leaf modules: no dependencies on any other project module.
include(":core-model")
include(":core-common")

// Inference contract (pure JVM) and its implementations.
include(":core-llm-api")
include(":core-llm")
include(":core-llm-testing")

// Capability + data sources.
include(":core-ml")
include(":core-remote")
include(":core-storage")
include(":core-download")

// Aggregation layer consumed by the UI.
include(":core-data")

// Embedded Ollama-compatible HTTP server.
include(":server")

// Macrobenchmark / baseline profile generation.
include(":benchmark")
