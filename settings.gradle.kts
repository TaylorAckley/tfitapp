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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TFitApp"

include(":domain")
include(":application")
include(":infrastructure:room")
include(":infrastructure:typesafe")
include(":infrastructure:fdc")
include(":infrastructure:nws")
include(":infrastructure:supabase")
include(":app")
