plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "example.lock"
version = "0.1.0"

// INDEPENDENT BUILD: mirrors examples/example-uppercase-plugin. Depends ONLY on
// public SDK artifacts (pipeline-domain: DirectiveContributor/registry contracts
// + domain value types; pipeline-scripting-api is NOT needed: the directive DSL
// already accepts open-world keys as plain strings, so no DSL extension is
// required for directives).
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

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    // Only the plugin's own classes + the ServiceLoader descriptor resource.
}
