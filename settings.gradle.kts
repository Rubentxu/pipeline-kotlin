pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
        id("io.kotest") version "0.4.11"
        // KSP para Kotlin 2.4.x — usar version 2.3.11 que es compatible con Kotlin 2.4.10
        id("com.google.devtools.ksp") version "2.3.11"
    }
}

rootProject.name = "pipeline-kotlin"



include(":core")
include(":pipeline-cli")
include(":pipeline-config")
include(":pipeline-backend")
include(":pipeline-lsp-server")
// include(":lib-examples") // Disabled due to DSL dependencies on excluded classes
// Consolidated all phases into core - phase modules no longer needed
// include(":phase1-validation") 
// include(":phase2-validation")
// include(":phase3-validation")  
// include(":phase4-step-migration")

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

includeBuild("v2")
