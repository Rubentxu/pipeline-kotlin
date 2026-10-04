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
    //
    // The @Tag("performance") exclusion and the `performanceTest` task that used to sit here moved
    // with the soak to `:pipeline-output-store` in BLOCK 2, which is where the store it measures
    // now lives. Keeping a `performanceTest` task over a source set with no tagged test would have
    // been a task that reports success having executed nothing — the exact failure mode the
    // `useJUnitPlatform` line above exists to prevent, one layer up.
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
