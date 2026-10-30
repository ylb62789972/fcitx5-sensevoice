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
        maven {
            name = "MavenCentralTestDependencies"
            url = uri("https://repo1.maven.org/maven2/")
            content {
                includeGroup("junit")
                includeGroup("org.hamcrest")
            }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "fcitx5-sensevoice"
include(":app")
include(":sensevoice_models")
