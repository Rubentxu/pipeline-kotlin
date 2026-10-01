plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "example.block"
version = "0.1.0"

// INDEPENDENT BUILD (WU-RP-035 / slice D): separate Gradle boundary, same lane as the
// atomic example plugin. It depends ONLY on public SDK artifacts (pipeline-domain for the
// Step contract, body-continuation port and capability; pipeline-scripting-api for the DSL
// facade surface). No import of pipeline-application, no engine or journal dependency.
//
// A plugin that needed a core change to reach its own body could not live behind this
// boundary, which is the point: if the seam is not open, this build does not compile.
//
//   -PsdkRepo=<dir>     repository holding the SDK artifacts
//   -PsdkVersion=<ver>  SDK version to resolve
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
    // The plugin JAR carries only its own classes. The ServiceLoader descriptor is a
    // resource; the SDK contracts come from the host at runtime.
}
