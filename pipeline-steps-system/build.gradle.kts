// Parent module for the @Step system
// Follows the structure of the official Kotlin compiler plugin template
// 
// This module contains:
// - plugin-annotations: Lightweight annotations module
// - compiler-plugin: K2 compiler plugin implementation
// - gradle-plugin: Gradle plugin for easy integration

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("maven-publish")
}

group = "dev.rubentxu.pipeline.steps-system"
version = "2.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure all subprojects
subprojects {
    group = rootProject.group
    version = rootProject.version
    
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

// Task to validate the entire plugin system
tasks.register("validatePluginSystem") {
    description = "Validates the entire @Step plugin system structure"
    group = "verification"
    
    dependsOn(":pipeline-steps-system:plugin-annotations:build")
    dependsOn(":pipeline-steps-system:compiler-plugin:build")
    dependsOn(":pipeline-steps-system:gradle-plugin:build")
    
    doLast {
        println("✅ Plugin system validation completed successfully")
        println("   - plugin-annotations: OK")
        println("   - compiler-plugin: OK")
        println("   - gradle-plugin: OK")
        println()
        println("🎉 @Step plugin system is ready for use!")
    }
}