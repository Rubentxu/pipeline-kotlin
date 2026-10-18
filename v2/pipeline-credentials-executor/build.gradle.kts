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
    implementation(project(":pipeline-credentials-api"))
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-step-sdk:api"))
}

// Unit-test wiring REMOVED (2026-10-18, testing RESET).
//
// This module carries no unit tests: it is an EXECUTOR whose subject is the `withCredentials`
// pipeline step. Its verification is the executable E2E layer -- `.pipeline.kts` pipelines
// under `v2/examples/`, driven by `scripts/run-e2e-pipelines.sh` -- and not a JUnit class.
//
// The JUnit dependency, the launcher and the `useJUnitPlatform()` block were removed together.
// Left in place they were a SILENT green: Gradle 8 defaults `Test.failOnNoDiscoveredTests` to
// false, so a `Test` task whose source set is empty reports success while executing zero
// classes.
//
// The root build now sets `failOnNoDiscoveredTests = true` on every `Test` task, so an empty
// source set is a hard failure rather than a green. That rule is only safe because the wiring
// that could have produced the misleading green is gone from the modules that have no tests.
