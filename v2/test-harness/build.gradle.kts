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

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardError")
    }
}
