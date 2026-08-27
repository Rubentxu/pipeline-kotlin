pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.google.devtools.ksp") version "2.3.11"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pipeline-v2"

include(
    ":pipeline-domain",
    ":pipeline-application",
    ":pipeline-protocol",
    ":pipeline-scripting-api",
    ":pipeline-scripting-kotlin24",
    ":pipeline-testkit",
    ":pipeline-architecture-tests",
    ":pipeline-events",
    ":pipeline-step-sdk:api",
    ":pipeline-step-sdk:processor",
    ":pipeline-step-sdk:runtime",
    ":pipeline-credentials-api",
    ":pipeline-credentials-local",
)
