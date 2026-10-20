plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

// NOT published, and the absence of `maven-publish` is the mechanism.
//
// BLOCK 2 publishes `:pipeline-events` so an external consumer can read envelopes and page
// history without a source or composite dependency. Everything that touches SQLite, a filesystem,
// the journal protocol or the replay cursor lives here instead: OperationJournal, ReplayCursorStore,
// OperationJournalSchema, RunExecutionLease, JsonEventLog, SqliteEventStore and InMemoryEventStore.
//
// Two reasons, and the second is the one that binds:
//
//  1. A published JDBC schema is a published coupling. Whoever adapts to `OperationJournalSchema`
//     adapts to a table layout that exists to serve a replay protocol, not to be consumed.
//  2. The read side and the write side are different authorities. A consumer that can name the
//     journal can decide what a replay returns; a consumer that can only page history can only
//     observe. Publishing both would make the first a legal thing to do.
//
// The package is `events.durable` for every file here, including the stores that were at the events
// root. That is what makes the boundary mechanically checkable: `events` and `events.identity` are
// published, `events.durable` is not, and a published source that names the third is a build error
// waiting to be written.

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    // `api`: the public signatures of OperationJournal, ReplayCursorStore and the stores are
    // written in DomainEvent and in the domain's durable operation types, so a consumer of this
    // module needs both on its compile classpath.
    api(project(":pipeline-events"))
    api(project(":pipeline-domain"))
    // `implementation`: the scripting vocabulary and the JSON codec are used inside the wire format
    // and never appear in a signature this module publishes.
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.serialization.json)
    // Required at runtime by DriverManager.getConnection("jdbc:sqlite:..."); the module imports
    // only java.sql, so the driver is a classpath fact rather than a compile-time one.
    implementation(libs.sqlite.jdbc)

    // Test-only.
    //
    // `:pipeline-event-harness` is here for ONE consumer: `RealSagaPlaneLawTest`, which runs the
    // plane laws over the CAPTURED planes of real runs. The laws live in the harness (it backs
    // the `pipeline events verify` verb) and the captured bytes live here (the decoder does),
    // so this is the only module where the two halves meet — and the only place a decoder that
    // quietly loses a fact could hide: a decoder that read every `compensatedActions` as empty
    // would leave a law that can never fire.
    //
    // It used to carry this edge for `RealHistoryParityTest` and
    // `FArch020EventHarnessIsolationTest`, both deleted by the RESET; it came back when the
    // captured plane had a law to answer for.
    //
    // `:pipeline-event-harness` itself is NOT dead: `:pipeline-application` depends on it because
    // it backs the `pipeline events verify` product command. This edge was the only other one.
    testImplementation(project(":pipeline-event-harness"))
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
