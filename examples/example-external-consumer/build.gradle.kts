// PK-R1 R1-E / T8 — INDEPENDENT external consumer of the published PipelineK SDK.
//
// This build is DELIBERATELY OUTSIDE the monorepo's `v2/` Gradle build:
// it has its own settings file, its own rootProject, and resolves the SDK
// as ordinary Maven coordinates from a build-local Maven repository that
// `v2/build` produced. It does not depend on any `project(...)` path inside
// `v2/`, and it does NOT consult `mavenLocal()` or `~/.m2/repository`.
//
// The R1-E brief is explicit on this point: "no sustituir un consumer
// externo por pruebas en el mismo monorepo." A consumer that lived inside
// `v2/` could see internal classes through `project(...)` even when its
// POM declared only four coordinates, and it would prove the wrong thing:
// that the SDK's INTERNAL types resolve, not that the PUBLISHED coordinates
// resolve for an outside observer.
//
//   -PsdkRepo=<dir>     directory holding the published SDK artifacts
//                       (default: <repo-root>/v2/build/sdk-repo).
//   -PsdkVersion=<ver>  SDK version to resolve (no fallback — a default would
//                       either resolve the wrong train or fail with a
//                       phantom coordinate).

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

import java.io.File

val sdkRepo: String = providers.gradleProperty("sdkRepo")
    .getOrElse("../../v2/build/sdk-repo")

val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this consumer resolves the published SDK and
    has no default version, because a default could only be one that fails
    to resolve. Pass the candidate's version explicitly, e.g.
    -PsdkVersion=0.51.0
    """.trimIndent()
}

val sdkRepoDir: File = file(sdkRepo)

// Verified by inspection on 2026-10-11: `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`
// contains pom+jar for pipeline-domain, pipeline-events, pipeline-output,
// pipeline-scripting-api and pipeline-sdk-bom, all at 0.51.0. The four
// coordinates below are the contract list declared in `v2/build.gradle.kts`
// (`publishedContractModules`); any change to that list is a deliberate
// change to the published contract, not a detail of this consumer.
repositories {
    mavenCentral()
    maven {
        name = "sdk"
        // `file(...)` not `uri(...)`: a build-local directory with absolute
        // path is what the SDK publication produces, and the resolution
        // path must show that explicitly.
        url = uri(sdkRepoDir)
    }
}

// The repository accumulates every version the train has published. Re-
// resolving on every build keeps a stale artifact from satisfying a build
// that should read THIS revision.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    // Four coordinates — same set `sdk-external-execution` resolves, same
    // omission of `pipeline-runtime` (deliberate: the runtime read ports
    // are the consumer side of the contract, not a published SDK module).
    implementation("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
    implementation("dev.rubentxu.pipeline.v2:pipeline-events:$sdkVersion")
    implementation("dev.rubentxu.pipeline.v2:pipeline-output:$sdkVersion")
    implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}