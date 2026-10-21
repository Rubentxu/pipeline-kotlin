plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-credentials-api"))
}

// Unit-test wiring removed.
//
// This module carries no unit tests: it is a credential WIRE codec driven through the
// pipeline's own binding execution. Its verification is the executable E2E layer --
// `.pipeline.kts` pipelines under `v2/examples/`, driven by `v2/examples/run.sh` --
// and not a JUnit class.
//
// The JUnit dependency, the launcher and the `useJUnitPlatform()` block were removed together.
// Left in place they were a SILENT green: a `Test` task whose source set is empty reports
// success while executing zero classes. Gradle 8.14 exposes no `failOnNoDiscoveredTests`
// property -- the setting does not exist in this version, so nothing inside the build can
// turn that green into a failure, and a build file asserting otherwise is describing a flag
// it cannot compile.
//
// What turns it into a failure lives outside Gradle: `scripts/verify-all.sh` enumerates the
// modules it asks from the filesystem and fails when a module that has a test source produces
// no JUnit XML. The wiring that could have produced the misleading green is gone from the
// modules that have no tests, so no module reaches that check in silence.

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}
