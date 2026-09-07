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

// The library IS the root project: publishing it from the root keeps the short
// com.github.<user>:freshness-ads coordinate. Extra artifacts live in subprojects that publish
// under the SAME group (see compose/build.gradle.kts), so JitPack serves them next to it as
// com.github.<user>:freshness-ads-<module> — no coordinate change for existing consumers.
rootProject.name = "freshness-ads"

include(":compose")
