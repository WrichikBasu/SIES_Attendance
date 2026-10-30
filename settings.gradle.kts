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

rootProject.name = "SIES Attendance"

// The Android app.
include(":app")

// Pure-Java domain code that both the app and the server compile: the enums,
// AcademicRules, the filter objects and the small formatting helpers. No Android
// and no SQL dialect in it, so one copy of every rule serves both sides.
include(":shared")

// The Spring Boot service and the PostgreSQL migrations.
include(":server")
