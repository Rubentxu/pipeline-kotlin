plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

// NOT published. `:pipeline-output` is the Output Plane's published contract; this module is its
// segment/filesystem implementation. Publishing both would hand an external consumer a second
// Authority over the same bytes, which is the double-writer removed. The absence of
// `maven-publish` here is the mechanism that keeps it from being published by accident.

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

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Tests cover the PURE half of this module: the sealed-segment name format, its reader, and the
// `SealedSegment` extent. Those three are the contract between the writer that rotates a segment
// and the reader that serves it back, and they are pure functions over a file NAME — the tests
// build `Path` values by hand and never open one. The segment files' IO (`readChunk`, `readRange`,
// `readRangeDigested`) is exercised by the E2E pipelines under `v2/examples/output/`, not by JUnit;
// the AGENTS.md rule against filesystem persistence in unit tests is what scopes the suite.

// The `performanceTest` lane, its `tasks.test` tag exclusion and its Kover
// `disabledForTestTasks` entry were REMOVED . All three
// read `sourceSets["test"]`, which is now empty, and Gradle 8.14 reports success for an
// empty source set -- it has no `failOnNoDiscoveredTests` property at all -- so the lane would
// have been a green 1 GiB soak
// that never allocated anything. The soak's real subject (Output Plane segment growth
// and rollover) is exercised instead by an E2E pipeline under `v2/examples/`.
