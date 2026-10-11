plugins {
    kotlin("jvm")
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
    // PK-R1 / CRIC-M2 — module split precondition.
    //
    // This module publishes (in a future cycle) the M2 runtime ports
    // (RuntimeRecoverPort, RuntimeControlPort, RuntimeIntrospectionPort) and
    // their sealed ADTs / pure decider. The published artifact carries no
    // store-side type: a consumer that pulls `:pipeline-runtime-api` does NOT
    // transitively pull in JDBC, the file-backed lease, the SQLite event
    // store, or the segment output follower.
    //
    // `api`, not `implementation`, and the distinction is the whole point.
    // `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` carries `OutputPin`
    // and `OutputStreamId`; `RuntimeObservation.Running.outputTails` and
    // `OutputTailView.stream` carry `OutputStreamId` too. A consumer that
    // names these types then fails to compile if the dependency is hidden at
    // `implementation` scope — the same failure mode the M1 read-side wires
    // guard against.
    api(project(":pipeline-output"))
    // No `api(:pipeline-events)` declaration is needed: no public type in this
    // module names an event-plane value. The host's `:pipeline-runtime` keeps
    // that wire because its adapters compose the event plane.
    //
    // NO `:pipeline-events-store` and NO `:pipeline-output-store`: those are
    // internal implementations that MUST NOT leak into the SDK ABI. If a
    // future change needs them, the split has been done wrong.
    //
    // No explicit kotlin-stdlib declaration: the Kotlin JVM plugin already
    // contributes it as an `api` dependency, and the sibling `:pipeline-output`
    // relies on exactly that. Declaring stdlib here as `implementation` would
    // override it to `runtime` scope in the POM — the same trap the
    // `:pipeline-events` build script calls out below.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    // Required, not optional. Without it Gradle defaults to the JUnit 4 runner,
    // discovers zero JUnit 5 tests, and reports BUILD SUCCESSFUL with an empty
    // result set — a green gate that ran nothing. Mirrors the guard in
    // `:pipeline-runtime`.
    useJUnitPlatform()
}

// PK-R1 / CRIC-M2 — the `maven-publish` block is intentionally absent in
// this commit. The publication of `:pipeline-runtime-api` is a separate
// follow-up cycle that the orchestrator task `odd/tasks/pk-r1-cric-m2-module-split.md`
// names as out-of-scope-for-this-commit. `publishedContractModules` in
// `v2/build.gradle.kts` is unchanged; the SDK contract surface is unchanged;
// the only thing this commit does is restructure the monorepo module graph
// so the eventual publication does not leak store dependencies into the
// published POM.