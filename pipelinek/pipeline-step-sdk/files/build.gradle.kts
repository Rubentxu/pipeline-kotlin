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
    testRuntimeOnly(libs.junit.platform.launcher)
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
