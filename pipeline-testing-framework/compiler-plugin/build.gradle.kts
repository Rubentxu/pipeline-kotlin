plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    `java-library`
}

group = "dev.rubentxu.pipeline.testing.compiler"
version = "0.1.0"

dependencies {
    // Pipeline annotations (lightweight dependency)
    implementation(project(":pipeline-testing-framework:annotations"))
    
    // Kotlin Symbol Processing API
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
    
    // Kotlin Compiler and Reflection
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    
    // For generating Kotlin code
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    
    test {
        useJUnitPlatform()
        
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}

// Configure as a library that can be consumed by other projects
tasks.jar {
    archiveBaseName.set("pipeline-testing-compiler-plugin")
    
    manifest {
        attributes(
            "Implementation-Title" to "Pipeline Testing Compiler Plugin",
            "Implementation-Version" to version,
            "Implementation-Vendor" to "dev.rubentxu"
        )
    }
}