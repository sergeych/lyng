pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.universablockchain.com/")
        maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
        mavenLocal()
    }
}

rootProject.name = "ling"
include(":library")
