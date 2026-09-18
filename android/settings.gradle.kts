pluginManagement {
    repositories {
        google()
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

rootProject.name = "logos-android-wrap-poc"

include(":logos-common")
include(":logos-delivery")
include(":logos-storage")
include(":logos-glue")
include(":demo-app-delivery")
include(":demo-app-full")
