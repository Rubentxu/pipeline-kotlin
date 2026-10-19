plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

dependencies {
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-scripting-api"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// One exception to "this module carries no unit tests", and it is the whole reason this block
// exists.
// The module's *step* guarantee is the filesystem: `cleanWs` and `deleteDir` delete real files,
// and AGENTS.md section 2 forbids unit tests from touching one. That reasoning is still correct
// for `execute()`.
// It does not reach `antPatternToRegex`, which is a pure String -> String compiler with its own
// written contract in the class KDoc: "`**` matches any path segment (including zero)". A defect
// in that compiler is invisible to an E2E pipeline in the worst possible way — it deletes the
// WRONG tree and returns a plausible file count — and it is exactly the kind of decision that
// becomes testable by being named separately from the I/O that consumes it.
// So the test covers the compiler, and nothing here claims the deletion itself is unit-tested.
tasks.test {
    // Load-bearing. Without it Gradle defaults to the JUnit 4 runner, discovers zero JUnit 5
    // tests, and reports BUILD SUCCESSFUL over an empty result set.
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Unit-test wiring REMOVED .
// This module carries no unit tests: it is a filesystem STEP whose guarantee is that a pipeline
// reads and writes real files. Its verification is the executable E2E layer -- `.pipeline.kts`
// pipelines under `v2/examples/`, driven by `v2/examples/run.sh` -- and not a JUnit
// class.
// The JUnit dependency, the launcher and the `useJUnitPlatform()` block were removed together.
// Left in place they were a SILENT green: a `Test` task whose source set is empty reports
// success while executing zero classes. Gradle 8.14 exposes no `failOnNoDiscoveredTests`
// property -- the setting does not exist in this version, so nothing inside the build can
// turn that green into a failure, and a build file asserting otherwise is describing a flag
// it cannot compile.
// What turns it into a failure lives outside Gradle: `scripts/verify-all.sh` enumerates the
// modules it asks from the filesystem and fails when a module that has a test source produces
// no JUnit XML. The wiring that could have produced the misleading green is gone from the
// modules that have no tests, so no module reaches that check in silence.
