plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "pipeline.testing"
version = "0.1.0-SNAPSHOT"

// INDEPENDENT BUILD (LFC-2E3 FIRST VERTICAL: junit + publishHTML).
//
// LFC-2E3 deliberately ships under a NEW OFFICIAL_PLUGIN coordinate
// (`pipeline.testing@0.1.0-SNAPSHOT`). E2 proved the utilities coordinate
// scales for filesystem / archive / codec families; E3 proves the SAME
// SDK absorbs a NEW DIMENSION — structured test results + HTML report
// publication — without growing production core.
//
// Lane R: SDK is consumed as ordinary module coordinates resolved from a
// build-local Maven repository produced by the v2 build from the SAME
// source revision. This is the same pattern the sibling utilities-plugin
// uses; the property being certified is that a SECOND plugin coordinate
// ships zero changes in production core.
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

    testImplementation("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
    testImplementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    // Nothing else: the plugin JAR carries only its own classes. The
    // ServiceLoader descriptor (META-INF/services) is a resource; the SDK
    // contracts come from the host at runtime via the Maven coordinates above.
}
