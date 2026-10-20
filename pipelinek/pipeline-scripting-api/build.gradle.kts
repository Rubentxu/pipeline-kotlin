plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        // S0-C1 (Pure Builder Consumption Gate): `@MustUseReturnValues` is an
        // "ignorability" annotation, and Kotlin 2.4 rejects it on a declaration
        // when the return-value checker is disabled (diagnostic
        // IGNORABILITY_ANNOTATIONS_WITH_CHECKER_DISABLED). The DSL module is
        // where the carriers are declared, so the checker is enabled here too.
        // This makes the library honest at its own boundary: an unconsumed
        // `PURE_BUILDER` result is an error for library consumers, not only
        // inside `.pipeline.kts`.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}

dependencies {
    // P3-E E6: `StepSpec.Error.failureKind` is typed as `FailureKind`, so the published ABI of
    // this module names a type from `:pipeline-domain`. That makes the dependency part of the
    // contract and it has to be `api`, not `implementation`.
    //
    // This is a declaration, not a new exposure. `pipeline-events/build.gradle.kts:31-32` already
    // declares `api(project(":pipeline-domain"))` AND `api(project(":pipeline-scripting-api"))`,
    // and all four modules are published to the same SDK repository, so any consumer of the
    // published set already compiles with both on its classpath. Before this change that coupling
    // was real but undeclared: it existed transitively, by accident of the module graph.
    api(project(":pipeline-domain"))
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}

// Lane R: publish this SDK module into a build-local Maven repository so the
// independent external plugin build can compile against artifacts produced from
// THIS source revision. Replaces the committed libs/*.jar snapshots, which could
// silently drift from the live SDK.
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
