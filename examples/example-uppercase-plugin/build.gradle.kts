plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "example.uppercase"
version = "0.1.0"

// INDEPENDENT BUILD (EP-3): separate Gradle boundary. It depends ONLY on public
// SDK artifacts (pipeline-domain: StepDefinitionContributor/StepRegistry contracts
// + domain value types; pipeline-scripting-api: the DSL facade surface). No
// internal application/runtime imports.
//
// Lane R: the SDK is consumed as ordinary module coordinates resolved from a
// build-local Maven repository produced by the v2 build from the SAME source
// revision. It is deliberately NOT a `files(...)` dependency on a committed
// jar: a stale SDK must fail this build rather than silently satisfy it.
//
//   -PsdkRepo=<dir>     repository holding the SDK artifacts
//   -PsdkVersion=<ver>  SDK version to resolve (default 0.1.0-SNAPSHOT)
val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../v2/build/sdk-repo")
val sdkVersion: String = providers.gradleProperty("sdkVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenCentral()
    maven {
        name = "sdk"
        url = uri(sdkRepo)
    }
}

// The SDK is published as a SNAPSHOT. Gradle's default snapshot cache TTL is 24h,
// which would let this build compile against an SDK that no longer matches the
// repository. Re-resolve on every build instead.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    // Nothing else: the plugin JAR carries only its own classes. The ServiceLoader
    // descriptor (EP-4) is a resource; the SDK contracts come from the host at runtime.
}
