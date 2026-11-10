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
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    // The event plane's published contract, for the cursor family the events
    // street resumes from: `EventCursor` is in the public surface of this
    // module (a subscription carries one), so the dependency is `api`. A
    // consumer that names the cursor compiles against the event plane's own
    // types, and hiding the dependency would break that compilation.
    api(project(":pipeline-events"))
    // The runtime's published contract, for `RuntimeObservation`: the state of
    // a run is answered with the SAME type the runtime publishes and the
    // command line renders, so the client and the local path cannot disagree
    // about what a run looks like. `api`, because the type is in this module's
    // public surface.
    api(project(":pipeline-runtime-api"))
    // The domain's published contract, for `ParameterSpec`: the DECLARATION of
    // the parameters a pipeline accepts travels in this module's public
    // signature —`ServiceRequest.StartRun` carries the contract the client
    // knows— and a consumer that names the contract compiles against the
    // domain's own types. `api`, because the type is in this module's public
    // surface.
    //
    // It ARRIVED transitively through `:pipeline-events`, which declares the
    // domain as `api`. That is not enough: a module that uses a type of
    // another artifact has to DECLARE that artifact, because the published
    // POM is what tells a consumer where to resolve the type from, and a
    // dependency that is only there by accident of another module's graph is
    // one dependency away from disappearing.
    api(project(":pipeline-domain"))
    // The JSON runtime is NOT declared here, and that is deliberate.
    //
    // `ServiceResponse.Answered` carries a `JsonObject` in its public
    // signature, so a consumer of this artifact compiles against the
    // serialization API — it has to be `api` scope in the published POM.
    // It already ARRIVES as `api` through `:pipeline-events`, which declares
    // `api(libs.kotlinx.serialization.json)` for exactly that reason.
    // Declaring it here as `implementation` would OVERRIDE that to `runtime`
    // scope in this module's POM, and a consumer that decodes a document of
    // this contract would fail to compile. The same trap the runtime contract
    // calls out for the Kotlin standard library.
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
// coordinate into `pipelinek/build/sdk-repo/`. The pattern mirrors
// `:pipeline-runtime-api` exactly; the version is not declared here
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
