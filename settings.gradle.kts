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
        // Termux veroeffentlicht terminal-emulator/terminal-view ausschliesslich ueber JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.termux.*") }
        }
    }
}

rootProject.name = "squeezessh"
include(":app")
