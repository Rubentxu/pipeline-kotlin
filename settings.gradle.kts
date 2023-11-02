pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
}

rootProject.name = "pipeline-kotlin"

include(":core")
//include("pipeline-script")
include(":pipeline-cli")
