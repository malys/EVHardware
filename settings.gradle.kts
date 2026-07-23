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

// Standalone build (own CI). When consumed by an app, the app's settings.gradle includes
// MG4Hardware/lib directly as a subproject via the git submodule — this file is ignored
// in that case.
rootProject.name = "MG4Hardware"
include(":lib")
