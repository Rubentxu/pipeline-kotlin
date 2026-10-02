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
    implementation(project(":pipeline-scripting-api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    // WU-RP-5 / FASE 2 item S: wall-clock performance probes are not valid
    // under full-build contention, so they are excluded from the standard gate
    // and run deliberately through `performanceTest` (PR-015 methodology).
    // Nothing is weakened: the probe keeps its floor; it measures where the
    // measurement means something.
    useJUnitPlatform {
        excludeTags("performance")
    }
}

// The `excludeTags("performance")` above is NECESSARY BUT NOT SUFFICIENT. The
// Kover plugin (0.9.9) wires every Test task it discovers as an input to
// `koverGenerateArtifactJvm`, so the probe was re-attached to the standard gate
// through a path the tag exclusion cannot reach:
//
//   koverGenerateArtifactJvm --> performanceTest --> (fails the floor)
//
// Measured on the same SHA and the same machine: 19,6 MB/s inside the full
// `check` gate versus 22,4 MB/s running the probe alone. That 14% swing is
// contention from the rest of the build, not the redactor — it made the
// repository gate non-reproducible for reasons unrelated to the code under
// test, and it contradicted both the tag exclusion and the probe's own KDoc.
//
// `disabledForTestTasks` is the supported Kover API for exactly this: the task
// is not a coverage source, so nothing pulls it into `check`. Coverage for
// StreamingRedactor still comes from the dedicated redaction tests.
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("performanceTest")
        }
    }
}

tasks.register<Test>("performanceTest") {
    group = "performance"
    description = "Runs the @Tag(\"performance\") probes in isolation (PR-015 methodology)."
    useJUnitPlatform {
        includeTags("performance")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Performance probes must measure an otherwise-idle machine: forbid Gradle
    // from scheduling other test executions in parallel with this one.
    setMaxParallelForks(1)
}
