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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}

// The `performanceTest` lane and its Kover exclusion were removed.
// The lane read `sourceSets["test"]`, and Gradle 8.14 has no
// `failOnNoDiscoveredTests` property, so an empty source set reports success
// regardless -- the lane would have been a green performance gate that measured
// nothing. The Kover `disabledForTestTasks` entry existed only to stop that lane
// re-entering `check`; with the lane gone, the exclusion had nothing to exclude.
//
// `StreamingRedactor`'s throughput floor is no longer enforced by a timed probe.
// A wall-clock floor is a property of the machine, not of the code, so it could
// not have become a portable permanent gate anyway; see the E2E pipelines for
// the behavioural replacement.
