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

    // The rule that decides whether a run identity can name a durable directory belongs to the
    // domain's `RunId`, and this plane asks it rather than keeping a second copy of the alphabet.
    // A second copy is what let the artifact plane accept a run identity the durable lease
    // refused, so the two planes disagreed about what an identity is. `implementation`, not `api`:
    // the domain type is consulted, never exposed — this module's public surface is the Output
    // Plane contract, and adding the domain to it would widen a published API for no reason.
    implementation(project(":pipeline-domain"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Tests cover the PURE half of this module: the sealed-segment name format, its reader, the
// `SealedSegment` extent, the decision about who owns a stream directory, and the lifetime of the
// per-stream exclusions. The first three are the contract between the writer that rotates a
// segment and the reader that serves it back; the last two are the rules that decide which
// directory a stream lands in and how long its exclusion lives. All of them are pure functions
// over a file NAME, a record and a counter — the tests build `Path` values by hand and never open
// one. The segment files' IO (`readChunk`, `readRange`, `readRangeDigested`) is exercised by the
// integration lane, not by JUnit; the AGENTS.md rule against filesystem persistence in unit
// tests is what scopes the unit suite.

// This module has NO performance lane. A Gradle test task that discovers no tests reports
// SUCCESS -- Gradle 8.14 has no `failOnNoDiscoveredTests` property at all -- so a lane over
// this source set would be a green 1 GiB soak that never allocates anything. The soak's real
// subject (Output Plane segment growth and rollover) is not measured by any test in this
// repository: it is a declared gap.
