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

// Unit-test wiring REMOVED .
// This module carries no unit tests: it is a COMPILER front end whose subject is a
// `.pipeline.kts` file being compiled and run. Its verification is the executable E2E layer --
// `.pipeline.kts` pipelines under `examples/`, driven by `examples/run.sh` --
// and not a JUnit class.
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
