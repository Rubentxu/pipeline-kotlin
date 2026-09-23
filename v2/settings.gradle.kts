pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.google.devtools.ksp") version "2.3.11"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
        // WU-RP-040 R4: selective mutation testing (codecs/policies only).
        id("info.solidsoft.pitest") version "1.19.0"
        // WU-RP-040 R5: SAST. detekt 2.0.0-alpha.6 = Kotlin 2.4.10 compatible.
        id("dev.detekt") version "2.0.0-alpha.6"
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
    ":pipeline-scripting-api",
    ":pipeline-scripting-kotlin24",
    ":pipeline-testkit",
    ":pipeline-architecture-tests",
    ":pipeline-events",
    ":pipeline-event-harness",
    ":pipeline-step-sdk:api",
    ":pipeline-step-sdk:processor",
    ":pipeline-step-sdk:runtime",
    ":pipeline-step-sdk:scm-git",
    ":pipeline-step-sdk:junit",
    ":pipeline-step-sdk:files",
    ":pipeline-step-sdk:utilities",
    ":pipeline-step-sdk:workflow-control",
    ":pipeline-credentials-api",
    ":pipeline-credentials-local",
    ":pipeline-credentials-multipart",
    ":pipeline-credentials-executor",
    ":pipeline-binding-factory",
    ":pipeline-artefacts-local",
)
