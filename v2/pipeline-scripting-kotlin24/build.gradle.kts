plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        // KotlinScriptedSourceMapper deliberately reaches into compiler PSI/CLI
        // internals; Kotlin 2.4 gates both behind opt-in markers.
        freeCompilerArgs.addAll(
            "-opt-in=org.jetbrains.kotlin.K1Deprecation",
            "-opt-in=org.jetbrains.kotlin.config.CompilerConfiguration.Internals",
        )
    }
}

dependencies {
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-domain"))
}

// Unit-test wiring REMOVED (2026-10-18, testing RESET).
//
// This module carries no unit tests: it is a COMPILER front end whose subject is a
// `.pipeline.kts` file being compiled and run. Its verification is the executable E2E layer --
// `.pipeline.kts` pipelines under `v2/examples/`, driven by `scripts/run-e2e-pipelines.sh` --
// and not a JUnit class.
//
// The JUnit dependency, the launcher and the `useJUnitPlatform()` block were removed together.
// Left in place they were a SILENT green: Gradle 8 defaults `Test.failOnNoDiscoveredTests` to
// false, so a `Test` task whose source set is empty reports success while executing zero
// classes.
//
// The root build now sets `failOnNoDiscoveredTests = true` on every `Test` task, so an empty
// source set is a hard failure rather than a green. That rule is only safe because the wiring
// that could have produced the misleading green is gone from the modules that have no tests.
