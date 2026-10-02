plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-scripting-api"))
    // C3 / D-011: PipelineJson helper (see /PipelineJson.kt). This module
    // exports JSON helpers that wrap kotlinx.serialization.json; the runtime
    // dep is required because helpers build and parse JsonObject directly.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
