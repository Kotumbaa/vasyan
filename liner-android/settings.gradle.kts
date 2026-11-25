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

rootProject.name = "liner-android"

include(":core:model")
include(":core:crypto")
include(":core:lyrics")
include(":core:network")

// Pass -PskipAndroidApp=true to run pure-JVM :core:* builds/tests on hosts without Android SDK.
if (providers.gradleProperty("skipAndroidApp").orNull != "true") {
    include(":app")
}
