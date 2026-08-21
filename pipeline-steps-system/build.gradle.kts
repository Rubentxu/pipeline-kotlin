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

    // Align Java compile target with Kotlin compile target (both JVM 21).
    // Without this, compileJava defaults to the running JDK, which conflicts
    // with compileKotlin's explicit JVM_21 target — surfaced when E0-01
    // attempted to remove the stale excludes in core/build.gradle.kts.
    // JDK 21 LTS per docs/v2/03-specifications/SCRIPTING_COMPILER_SPEC.md
    // (primera línea certificada: Kotlin 2.4.10, JVM target 21, JDK 21).
    // JDK 25 está documentado como "tier-2 until promoted" — pendiente de
    // validar via compatibility corpus (backlog E2-06 / ADR-0019).
    tasks.withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
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