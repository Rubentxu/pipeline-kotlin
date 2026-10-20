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
    // The harness does not depend on `:pipeline-events-store`, and that is the point.
    // `RealHistoryParityTest` drove a real `JsonEventLog` to prove the harness agrees with a real
    // history. `JsonEventLog` is an implementation, and it lives in `:pipeline-events-store`
    // so the published event contract would carry no wire format and no JDBC. Reaching for it from
    // here would have made the verifier depend on the thing it verifies — the exact inversion
    // `FArch020EventHarnessIsolationTest` existed to prevent.
    // That test did not survive: BOTH it and `RealHistoryParityTest` were deleted by the RESET,
    // which `:pipeline-events-store/build.gradle.kts` already records. So the edge below is held by
    // the module graph itself — `dependencies` is the only thing that can pull the store in here —
    // and not by a fitness test. The harness stays a leaf that depends only inward on the contract.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)
}

// The module is the runtime behind the `pipeline events verify` verb, and the
// executable layer does not reach it: the launcher runs a pipeline and reads its
// exit code, while the verifier is a second command that needs a journal, a run
// id and a contract file. No scenario drives that command, so the claim that the
// end-to-end layer is this module's verification was true of no one. The
// guarantee that a contract honoured comes back clean, and a contract broken
// comes back with the rule it broke, had no observer at all — not a unit test,
// not an example. `EventHarnessVerifyTest` is that observer.
//
// These lines exist because a test source does, and `scripts/verify-all.sh`
// fails the build when a module with a test source produces no JUnit XML.
// Gradle 8.14 exposes no `failOnNoDiscoveredTests` property — the setting does
// not exist in this version — so the guarantee that makes this wiring safe
// lives in that script, not here.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
