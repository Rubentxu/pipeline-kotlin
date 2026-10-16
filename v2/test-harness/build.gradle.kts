// BQ1: pure impact / evidence / validation harness.
//
// The module is a leaf that owns no I/O. Its public surface is total
// functions over their inputs (ImpactResolver, EvidenceReader, ValidationPlan).
// The only side effects are inside XmlReportReader (filesystem + XML parser)
// and the catalogue IO surface is left to a future iteration; the contract
// surface here is deciders + their property tests.
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
    // No domain / runtime dependency: the deciders are pure total functions
    // over the ADTs declared in this module. The harness must be testable
    // without constructing the coordinator, the journal, or the event sink.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    // The fast lane must run with fresh JVMs only when a test actually
    // mutates global state. The deciders here are pure, so a single JVM
    // is enough and parallel forks would be wasted overhead.
    maxParallelForks = 1
    forkEvery = 0
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardError")
    }
}

// BQ2: explicit `validateFast` task that runs the test-harness deciders
// in-process. It does NOT depend on `:pipeline-application:installDist`
// or any of the `:buildExamplePlugin` family. The deciders here are pure
// total functions; no I/O; no subprocess. This is the lane a developer
// runs while iterating on a reducer, a codec, or a contract.
//
// The default `test` task already does this (it is the only test source
// in this module), so `validateFast` is an alias that documents intent
// and gives a name the CI matrix can target without ambiguity.
tasks.register<Test>("validateFast") {
    description = "Fast lane: in-process deciders, no installDist, no plugin build."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    testLogging {
        events("passed", "skipped", "failed", "standardError")
    }
}
