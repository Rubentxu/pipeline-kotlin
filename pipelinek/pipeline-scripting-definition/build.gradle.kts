plugins {
    kotlin("jvm")
    // The publication of the SDK: one coordinate, resolved from the build-local
    // Maven repository that the release stages and signs.
    `maven-publish`
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
    // `api`: `PipelineScriptCompilationConfiguration` IS a `ScriptCompilationConfiguration`, and
    // `PipelineScript` carries `@KotlinScript`. Both types are in this module's public surface, so
    // a consumer that loads the definition (the runner, or an IDE that reads the template marker)
    // compiles and links against the scripting API. `kotlin-scripting-jvm` brings
    // `kotlin-scripting-common`, which declares the annotation and the configuration keys.
    api(libs.kotlin.scripting.jvm)
    // `api`: the definition names no type of the DSL in its signatures, but its default imports
    // name the DSL's declarations, and a script compiled with this definition does not resolve
    // without them. Whoever resolves the definition resolves the DSL it imports, from the same
    // version.
    api(project(":pipeline-scripting-api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // Required, not optional: without it Gradle defaults to the JUnit 4
    // runner, discovers zero JUnit 5 tests, and reports BUILD SUCCESSFUL with
    // an empty result set — a green gate that ran nothing.
    useJUnitPlatform()
}

// This module is on `publishedContractModules` in `pipelinek/build.gradle.kts`.
// The `maven-publish` block makes that claim real by emitting an SDK Maven
// coordinate into `pipelinek/build/sdk-repo/`. The version is not declared here
// (rootProject.version is the single authority).
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "sdk"
            url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
        }
    }
}
