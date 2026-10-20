plugins {
    kotlin("jvm")
    `maven-publish`
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
    // Module split precondition.
    // This module publishes (in a future cycle) the runtime ports
    // (RuntimeRecoverPort, RuntimeControlPort, RuntimeIntrospectionPort) and
    // their sealed ADTs / pure decider. The published artifact carries no
    // store-side type: a consumer that pulls `:pipeline-runtime-api` does NOT
    // transitively pull in JDBC, the file-backed lease, the SQLite event
    // store, or the segment output follower.
    // `api`, not `implementation`, and the distinction is the whole point.
    // `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` carries `OutputPin`
    // and `OutputStreamId`; `RuntimeObservation.Running.outputTails` and
    // `OutputTailView.stream` carry `OutputStreamId` too. A consumer that
    // names these types then fails to compile if the dependency is hidden at
    // `implementation` scope — the same failure mode the read-side wires
    // guard against.
    api(project(":pipeline-domain"))
    api(project(":pipeline-output"))
    // No `api(:pipeline-events)` declaration is needed: no public type in this
    // module names an event-plane value. The host's `:pipeline-runtime` keeps
    // that wire because its adapters compose the event plane.
    // NO `:pipeline-events-store` and NO `:pipeline-output-store`: those are
    // internal implementations that MUST NOT leak into the SDK ABI. If a
    // future change needs them, the split has been done wrong.
    // No explicit kotlin-stdlib declaration: the Kotlin JVM plugin already
    // contributes it as an `api` dependency, and the sibling `:pipeline-output`
    // relies on exactly that. Declaring stdlib here as `implementation` would
    // override it to `runtime` scope in the POM — the same trap the
    // `:pipeline-events` build script calls out below.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // Required, not optional. Without it Gradle defaults to the JUnit 4 runner,
    // discovers zero JUnit 5 tests, and reports BUILD SUCCESSFUL with an empty
    // result set — a green gate that ran nothing. Mirrors the guard in
    // `:pipeline-runtime`.
    useJUnitPlatform()
}

// This module is now on `publishedContractModules` in
// `pipelinek/build.gradle.kts`. The `maven-publish` block makes that claim real by
// emitting an SDK Maven coordinate into `pipelinek/build/sdk-repo/`. The pattern
// mirrors `:pipeline-events` exactly; the version is not declared here
// (rootProject.version is the single authority; per-module `version =` is a
// release-time defect).
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "sdk"
            url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
        }
    }
}