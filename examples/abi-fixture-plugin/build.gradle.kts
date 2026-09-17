plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "abi.fixture"
version = "1.0.0"

// ABI FIXTURE PLUGIN (LFC-2E3-P / P1) — NOT a product plugin.
//
// This module exists to be built ONCE against a given SDK shape and then FROZEN as
// a committed JAR. Its purpose is to prove the PLUGIN ABI COMPATIBILITY law:
//
//   old plugin JAR  +  new host  =  loads / registers / is admitted / EXECUTES
//
// It deliberately implements ONLY the ORIGINAL StepDefinitionContributor shape
// (`id` + `definitions()`). It does NOT implement StepCapabilityContributor, and it
// declares no capabilities — exactly like a plugin JAR built before that SPI existed.
//
// WHY THE BUILT JAR IS COMMITTED RATHER THAN REBUILT
// --------------------------------------------------
// A plugin recompiled against the current SDK always matches the current SDK, so it can
// never reproduce the regression class this fixture guards. The regression is:
//
//   existing SPI + new abstract/default member  ->  old prebuilt JAR -> AbstractMethodError
//
// Only a physically preserved artifact can observe that. The committed JAR is therefore
// THE fixture; this source exists to document its contents and to allow an explicit,
// reviewed regeneration. Tests MUST read the committed resource and never rebuild it.
//
// Regeneration is a deliberate act (see the abi-fixture justfile target), never automatic.

// Lane R: consume the public SDK from the build-local Maven repository produced by this
// revision, exactly like the other plugin modules.
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
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
}
