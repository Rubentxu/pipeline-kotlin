pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.2.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
        id("io.kotest") version "0.4.11"
        id("com.google.devtools.ksp") version "2.2.0-2.0.2"
    }
}

rootProject.name = "pipeline-kotlin"



include(":core")
include(":pipeline-cli")
include(":pipeline-config")
include(":pipeline-backend")
include(":lib-examples")

// Framework de testing organizado como módulo padre con submódulos
// TODO: Fix testing framework compatibility with new StepsBlock
// include(":pipeline-testing-framework")
// include(":pipeline-testing-framework:annotations")
// include(":pipeline-testing-framework:compiler-plugin")
// include(":pipeline-testing-framework:runtime")

// Sistema de @Step plugins con estructura K2 canónica
include(":pipeline-steps-system")
include(":pipeline-steps-system:plugin-annotations")
include(":pipeline-steps-system:compiler-plugin")
include(":pipeline-steps-system:gradle-plugin")
