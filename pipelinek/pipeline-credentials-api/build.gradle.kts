plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-scripting-api"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// This module has NO performance lane. A lane over `sourceSets["test"]` would be a green
// performance gate that measured nothing: Gradle 8.14 has no `failOnNoDiscoveredTests`
// property, so a source set with no tagged test reports success regardless. (The property
// arrives in Gradle 9, and this build runs on 8.14.5.)
// `StreamingRedactor`'s throughput floor is therefore not enforced by a timed probe: a
// wall-clock floor is a property of the machine, not of the code. The E2E pipelines carry
// the behavioural replacement.

