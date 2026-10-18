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
}

// The `performanceTest` lane and its Kover exclusion were REMOVED (2026-10-18,
// testing RESET). The lane read `sourceSets["test"]`, which is now empty, and
// Gradle 8 defaults `failOnNoDiscoveredTests` to false -- so it would have been
// a green performance gate that measured nothing. The Kover `disabledForTestTasks`
// entry existed only to stop that lane re-entering `check`; with the lane gone,
// the exclusion had nothing to exclude.
//
// `StreamingRedactor`'s throughput floor is no longer enforced by a timed probe.
// A wall-clock floor is a property of the machine, not of the code, so it could
// not have become a portable permanent gate anyway; see the E2E pipelines for
// the behavioural replacement.
