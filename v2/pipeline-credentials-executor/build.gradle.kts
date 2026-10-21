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
    implementation(project(":pipeline-credentials-api"))
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-step-sdk:api"))

    // Unit-test wiring for the teardown decisions.
    //
    // The three defects this module carried were all in the ORDERING and ACCUMULATION of the
    // credential bind/unbind teardown, none of which is reachable from a `.pipeline.kts` example
    // without a real lock, a real event store and a real filesystem — and the unit-test rule
    // puts those out of reach of a unit test in the first place. The decisions were therefore
    // extracted into pure top-level functions in `CredentialTeardown.kt` and
    // `:pipeline-domain`'s `wipeMaterialization`, each with its filesystem, event-store and
    // SecretHandle effects injected, so what the tests pin is the rule rather than the I/O.
    //
    // `useJUnitPlatform()` below is load-bearing, not boilerplate. Without it Gradle defaults to
    // the JUnit 4 runner, discovers zero JUnit 5 tests, and reports BUILD SUCCESSFUL over an
    // empty result set — and `scripts/verify-all.sh` would then read that as a passing module
    // rather than as the green-with-nothing-executed that it is.
    testImplementation(libs.junit.jupiter)
    // `bindSpecs` is `suspend`, so the end-to-end rollback test needs a coroutine to call it
    // through. Main needs no coroutines dependency: the function only ever awaits nothing.
    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
