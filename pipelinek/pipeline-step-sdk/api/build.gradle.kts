plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

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
}

// Unit-test wiring REMOVED (2026-10-18, testing RESET).
//
// This module carries no unit tests: it is the SDK's public API surface, whose guarantee is
// `apiCheck` (binary compatibility), the pyramid's leg 3. Its verification is the executable
// E2E layer -- `.pipeline.kts` pipelines under `v2/examples/`, driven by
// `v2/examples/run.sh` -- and not a JUnit class.
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
