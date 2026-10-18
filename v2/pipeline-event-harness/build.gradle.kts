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
    // BLOCK 2 removed the harness's dependency on `:pipeline-events-store`, and that is the point.
    //
    // `RealHistoryParityTest` drove a real `JsonEventLog` to prove the harness agrees with a real
    // history. `JsonEventLog` is an implementation, and BLOCK 2 moved it to `:pipeline-events-store`
    // so the published event contract would carry no wire format and no JDBC. Reaching for it from
    // here would have made the verifier depend on the thing it verifies — the exact inversion
    // `FArch020EventHarnessIsolationTest` exists to prevent, and the reason that test failed rather
    // than being relaxed.
    //
    // The test moved to `:pipeline-events-store` instead: it still runs, it still drives a real log,
    // and the harness stays a leaf that depends only inward on the contract.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)
}

// Unit-test wiring REMOVED (2026-10-18, testing RESET).
//
// This module carries no unit tests: it is the runtime behind the `pipeline events verify` CLI
// verb -- product functionality whose proof is running the verb. Its verification is the
// executable E2E layer -- `.pipeline.kts` pipelines under `v2/examples/`, driven by
// `scripts/run-e2e-pipelines.sh` -- and not a JUnit class.
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
