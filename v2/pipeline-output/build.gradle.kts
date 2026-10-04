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
    implementation(libs.kotlin.stdlib)
    // Deliberately NO dependency on :pipeline-events. The independence of the output plane from
    // the event plane is the whole point of ADR-M1 D3, so it is enforced by the module graph
    // rather than by a convention somebody can violate. :pipeline-domain is also absent: an output
    // byte range is not a domain concept, and depending on it would let event vocabulary leak
    // back in through a shared type.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    // Required, not optional. Without it Gradle defaults to the JUnit 4 runner, discovers zero
    // JUnit 5 tests, and reports BUILD SUCCESSFUL with an empty result set — a green gate that ran
    // nothing. This module's first build did exactly that.
    useJUnitPlatform {
        // The 1 GiB soak is a conformance artefact, not a per-commit cost. It runs through
        // `performanceTest` below, which is the same split :pipeline-credentials-api uses for its
        // redactor probe. Excluding it here is necessary but NOT sufficient: the Kover plugin
        // re-attaches every discovered Test task as an input to koverGenerateArtifactJvm, so the
        // probe is disabled as a coverage source rather than merely tag-excluded.
        excludeTags("performance")
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Test>("performanceTest") {
    group = "performance"
    description = "Runs the @Tag(\"performance\") conformance probes in isolation (PR-015 methodology)."
    useJUnitPlatform {
        includeTags("performance")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // A 1 GiB soak measures a machine; it must not share one with other test executions.
    setMaxParallelForks(1)
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("performanceTest")
        }
    }
}
