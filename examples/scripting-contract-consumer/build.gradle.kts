// P3-E E6 — the single-coordinate consumer.
//
// `StepSpec.Error.failureKind` became `FailureKind`, which lives in `:pipeline-domain`. For a
// consumer to compile `error("boom", FailureKind.USER)`, the PUBLISHER has to declare that
// dependency as `api`, because `api` is what writes a compile-scope dependency into the published
// Gradle Module Metadata. `implementation` writes it to `runtimeElements` only, and the
// `apiElements` variant a consumer compiles against then simply does not contain the type.
//
// This build exists to make that distinction observable from the outside:
//
//   - its own settings file; not a subproject of `v2`;
//   - NO `project(...)` anywhere, so it cannot read the source tree through a side door;
//   - EXACTLY ONE PipelineK coordinate. It never names `pipeline-domain`.
//
// The single declaration is the whole experiment. If `FailureKind` resolves below, the only route
// to it is the publisher's metadata. Reverting the publisher to `implementation(...)` removes that
// route and this build fails to compile — which makes the DECLARATION the test, rather than a
// comment asserting that the declaration exists.
//
// The four-coordinate consumer in `examples/fabric-contract-consumer` cannot prove this. It
// declares `pipeline-domain` itself, so it compiles either way.

plugins {
    kotlin("jvm") version "2.4.10"
}

group = "dev.rubentxu.pipeline.scripting.consumer"
version = "0.1.0"

val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../v2/build/sdk-repo")

// No default on purpose, exactly as in the four-coordinate consumer: a default could only be a
// version that fails to resolve, and the build would fail naming a coordinate nobody asked for.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required. This build resolves ONE published contract — pipeline-scripting-api —
    and must be told which version to read, because the repository accumulates every version the
    train has produced and a stale artifact would otherwise satisfy a build that is supposed to be
    reading THIS revision.
    """.trimIndent()
}

val PIPELINEK_GROUP = "dev.rubentxu.pipeline.v2"

repositories {
    mavenCentral()
    maven {
        name = "sdk"
        url = uri(sdkRepo)
    }
}

// Re-resolve every time: the repository is build-local and keeps old versions, so a cached module
// is a build that silently certified the wrong revision.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    // The ONLY PipelineK coordinate. Everything this build can name about PipelineK must arrive
    // through here or not at all.
    implementation("$PIPELINEK_GROUP:pipeline-scripting-api:$sdkVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * The negative control, stated as a property rather than a comment.
 *
 * If `pipeline-domain` were declared at `implementation` in the publisher, or if this build
 * declared it directly, the assertion below would pass for the wrong reason. So the build
 * FAILS if this configuration resolves `pipeline-domain` without the publisher saying so —
 * which cannot happen today, and is what makes the green meaningful tomorrow.
 */
val noDirectDomainDeclaration = tasks.register("assertSingleCoordinate") {
    group = "verification"
    description = "Fails if pipeline-domain was ever declared here directly instead of coming from metadata."
    doLast {
        val declared = configurations.getByName("implementation")
            .allDependencies
            .filter { it.group == PIPELINEK_GROUP }
            .map { it.name }
            .sorted()

        check(declared == listOf("pipeline-scripting-api")) {
            "this build must declare EXACTLY pipeline-scripting-api, and declares $declared. " +
                "Adding pipeline-domain here would make the compile succeed for a reason that has " +
                "nothing to do with the publisher's publication contract, which is the one property " +
                "this build exists to observe."
        }
    }
}

tasks.named("check") { dependsOn(noDirectDomainDeclaration) }
