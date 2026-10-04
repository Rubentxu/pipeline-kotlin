plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

// NOT published. `:pipeline-output` is the Output Plane's published contract; this module is its
// segment/filesystem implementation. Publishing both would hand an external consumer a second
// authority over the same bytes, which is the double-writer ADR-M1 D2 removed. The absence of
// `maven-publish` here is the mechanism, and `PublishedContractBoundaryFitnessTest` is what keeps
// it from being re-added by accident.

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    // `api`, not `implementation`: SegmentOutputStore's public signatures are expressed in the
    // contract's types (it implements OutputReadPort and returns OutputReadResult/OutputPage), so a
    // consumer of this module needs the contract on its COMPILE classpath, not only at runtime.
    // With `implementation` the published POM would carry it as `runtime` scope and the consumer
    // would fail to compile against a type it is legally allowed to see.
    api(project(":pipeline-output"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    // The same JUnit 5 platform declaration `pipeline-output` carries, and for the same reason:
    // without it Gradle defaults to the JUnit 4 runner, discovers zero tests, and reports
    // BUILD SUCCESSFUL with an empty result set.
    useJUnitPlatform {
        // The 1 GiB soak in OutputPlaneConformanceTest is a conformance artefact, not a
        // per-commit cost. It runs through `performanceTest` below.
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
            // The probe is disabled as a coverage source, not merely tag-excluded: the Kover plugin
            // re-attaches every discovered Test task as an input to koverGenerateArtifactJvm.
            disabledForTestTasks.add("performanceTest")
        }
    }
}
