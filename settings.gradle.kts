pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The library IS the root project. With more than one module JitPack publishes every artifact
// under the multi-module group com.github.<user>.<repo>:<artifactId> (verified on the 1.1.0 build
// log), and turns the old single-module coordinate com.github.<user>:freshness-ads into an
// aggregator POM that pulls ALL modules. Consumers must use
//   com.github.vuhuyvqh2112.freshness-ads:freshness-ads
//   com.github.vuhuyvqh2112.freshness-ads:freshness-ads-compose
rootProject.name = "freshness-ads"

include(":compose")
