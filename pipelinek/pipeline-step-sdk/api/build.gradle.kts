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
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-scripting-api"))
    // PipelineJson helper (see /PipelineJson.kt). This module
    // exports JSON helpers that wrap kotlinx.serialization.json; the runtime
    // dep is required because helpers build and parse JsonObject directly.
    implementation(libs.kotlinx.serialization.json)
}

// The module is the SDK's public API surface, whose compatibility guarantee
// is `apiCheck`. That is the leg that watches the SHAPE of the surface, and it
// says nothing about what the accessors do: the difference between a field a
// step declared and a field it did not is a decision, dozens of codecs rely on
// it, and a required accessor that returned null instead of failing would hand
// a step a payload with a hole in it.
//
// The executable layer reaches the plugins that use these accessors, so the
// claim that it verified this module was not as wrong as it looked — but it
// never checked the difference itself. `JsonAccessorsTest` does.
//
// These lines exist because a test source does, and `scripts/verify-all.sh`
// fails the build when a module with a test source produces no JUnit XML.
// Gradle 8.14 exposes no `failOnNoDiscoveredTests` property — the setting does
// not exist in this version — so the guarantee that makes this wiring safe
// lives in that script, not here.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
