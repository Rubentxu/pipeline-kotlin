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

// LFC0-004: V1 source remains in the repository as legacy/history, but is not
// part of the active build. The local-first product is the V2 composite build.
includeBuild("v2")
