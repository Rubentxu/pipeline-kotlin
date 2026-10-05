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

// No default version, on purpose. `0.1.0-SNAPSHOT` was here for a long time and it cannot resolve
// anything: the build then failed in a dependency-resolution message naming a version nobody asked
// for, hundreds of lines below the line that matters. The v2 build already omitted `-PsdkVersion`
// once, on 2026-09-19, and paid exactly that. Failing here names the real cause.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this plugin resolves the published SDK contracts and has no default
    version, because a default could only be one that fails to resolve. Pass the candidate's
    version explicitly, e.g. -PsdkVersion=0.47.0
    """.trimIndent()
}

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
