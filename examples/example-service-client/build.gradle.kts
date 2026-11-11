plugins {
    kotlin("jvm") version "2.4.10"
    application
}

// INDEPENDENT BUILD: a separate Gradle boundary, like the example plugins.
//
// It depends ONLY on the published SDK artifact `pipelinek-service-client`
// and on the contracts that artifact brings with it. It does NOT depend on
// `pipeline-application`, and it does NOT reach into the product's internals:
// a client that could see the product's classes would prove nothing about a
// client that has to resolve the SDK like anybody else.
//
// The SDK is resolved as an ordinary Maven coordinate from a build-local
// repository produced by the build from the SAME source revision. It is
// deliberately NOT a `files(...)` dependency on a committed jar: a stale SDK
// must fail this build rather than silently satisfy it.
//   -PsdkRepo=<dir>     repository holding the SDK artifacts
//   -PsdkVersion=<ver>  SDK version to resolve
val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../pipelinek/build/sdk-repo")

// No default version, on purpose: a default could only be one that fails to
// resolve, and the failure would name a version nobody asked for.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this example resolves the published SDK and has no default
    version, because a default could only be one that fails to resolve. Pass the candidate's
    version explicitly, e.g. -PsdkVersion=0.97.0
    """.trimIndent()
}

repositories {
    // The SDK repository comes FIRST, and the order is the point.
    //
    // The product's own coordinates are published to Maven Central too, and a
    // version that Central already carries is a version Central serves: a
    // build that asked Central first would resolve the OLD BOM of that
    // version, and the old BOM pins the contracts of the day it was
    // published. The candidate's SDK is the one this build is about, so the
    // repository that carries it is asked first.
    maven {
        name = "sdk"
        url = uri(sdkRepo)
    }
    mavenCentral()
}

// The SDK is published as a SNAPSHOT. Gradle's default snapshot cache TTL is 24h,
// which would let this build compile against an SDK that no longer matches the
// repository. Re-resolve on every build instead.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    // The published BOM is the SDK's single resolution document: ONE platform
    // declaration pins the version of every published contract, so the
    // contract below carries no version of its own.
    implementation(platform("dev.rubentxu.pipelinek:pipelinek-sdk-bom:$sdkVersion"))
    // The client of the local service. ONE dependency is the whole point of
    // the SDK: the wire's vocabulary, the codec and the typed client arrive
    // together, and the consumer writes no copy of any of them.
    implementation("dev.rubentxu.pipelinek:pipelinek-service-client")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("example.serviceclient.ServiceClientExampleKt")
}

// The resolved SDK is an INPUT of the compilation, declared here: a build
// that compiles against an SDK it did not resolve is a build whose artifact
// declares a compatibility it cannot back.
tasks.named("compileKotlin") {
    inputs.files(configurations.named("compileClasspath"))
        .withNormalizer(ClasspathNormalizer::class)
}
