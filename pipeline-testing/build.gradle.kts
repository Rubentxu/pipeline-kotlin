plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "dev.rubentxu.pipeline.testing"
version = "0.1.0"

dependencies {
    // Core pipeline dependency
    api(project(":core"))
    
    // Kotest framework (this is the core of our testing framework)
    api(libs.bundles.kotest.extended)
    
    // Kotlin scripting for pipeline execution
    implementation(libs.bundles.kotlin.scripting)
    
    // Coroutines for async testing
    implementation(libs.kotlinx.coroutines.core)
    
    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    
    // Testing utilities
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
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
        
        // Configure for testing framework development
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}

// Configure as a library that can be consumed by other projects
tasks.jar {
    archiveBaseName.set("pipeline-testing-framework")
    
    manifest {
        attributes(
            "Implementation-Title" to "Pipeline Testing Framework",
            "Implementation-Version" to version,
            "Implementation-Vendor" to "dev.rubentxu"
        )
    }
}

// Optional: Create a sources JAR for better IDE support
tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

// Optional: Create a javadoc JAR
tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.javadoc)
}

artifacts {
    archives(tasks.jar)
    archives(tasks.named("sourcesJar"))
    archives(tasks.named("javadocJar"))
}