plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

// NOT published. `:pipeline-output` is the Output Plane's published contract; this module is its
// segment/filesystem implementation. Publishing both would hand an external consumer a second
// authority over the same bytes, which is the double-writer ADR-M1 D2 removed. The absence of
// `maven-publish` here is the mechanism, and `PublishedContractBoundaryFitnessTest` is what keeps
// it from being re-added by accident.

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    // `api`, not `implementation`: SegmentOutputStore's public signatures are expressed in the
    // contract's types (it implements OutputReadPort and returns OutputReadResult/OutputPage), so a
    // consumer of this module needs the contract on its COMPILE classpath, not only at runtime.
    // With `implementation` the published POM would carry it as `runtime` scope and the consumer
    // would fail to compile against a type it is legally allowed to see.
    api(project(":pipeline-output"))
}

// The `performanceTest` lane, its `tasks.test` tag exclusion and its Kover
// `disabledForTestTasks` entry were REMOVED (2026-10-18, testing RESET). All three
// read `sourceSets["test"]`, which is now empty, and Gradle 8 defaults
// `failOnNoDiscoveredTests` to false -- so the lane would have been a green 1 GiB soak
// that never allocated anything. The soak's real subject (Output Plane segment growth
// and rollover) is exercised instead by an E2E pipeline under `v2/examples/`.
