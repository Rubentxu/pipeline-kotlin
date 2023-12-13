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

include(":pipeline-dsl")
include(":pipeline-cli")
include(":pipeline-model")
include(":pipeline-backend")
include(":lib-examples")
