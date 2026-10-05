import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "dev.rubentxu.pipeline.fabric.consumer"
version = "0.1.0"

// ── BLOCK 2: the consumer that proves the POM, rather than describing it ────────
//
// Every other check in BLOCK 2 has looked at the published artifacts from the INSIDE: the POM was
// read, the ABI dump was compared, the class listing was walked. All three can be satisfied by an
// artifact that a real consumer still cannot compile against. This build is the outside.
//
// The property being certified is an INDEPENDENT Gradle boundary. It has its own settings file, it
// is not a subproject of `v2`, and it has no `project(...)` dependency anywhere — if it did, it
// would be reading the repository's source tree through a side door and would prove nothing about
// what a consumer resolves. Every type it touches comes from four published coordinates.
//
// What it exercises is the surface `pipelinek-fabric` needs for BLOCK 3 and BLOCK 4, and nothing
// else: the run outcome algebra, the envelope and its paging contract, and the output read
// contract with every refusal case handled. It cannot name `OperationJournal`, `SegmentOutputStore`
// or anything else in an implementation module, because those coordinates do not exist.
//
//   -PsdkRepo=<dir>      repository holding the published artifacts
//   -PsdkVersion=<ver>   version to resolve
val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../v2/build/sdk-repo")

// `sdkVersion` has NO default on purpose. An earlier version defaulted to `0.1.0-SNAPSHOT`, which
// cannot resolve anything: the build would fail in a dependency-resolution message that names a
// version nobody asked for, hundreds of lines below the line that actually matters. Failing here
// makes the cause the missing property rather than a phantom coordinate, and it removes the
// possibility of a build that "passes" against a version that was never published.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this build resolves the four published PipelineK contracts and has
    no default version, because a default could only be a version that fails to resolve. Pass the
    candidate's version explicitly, e.g. -PsdkVersion=0.47.0
    """.trimIndent()
}

/** The one group this build is allowed to resolve product code from. */
val PIPELINEK_GROUP = "dev.rubentxu.pipeline.v2"

repositories {
    mavenCentral()
    maven {
        name = "sdk"
        url = uri(sdkRepo)
    }
}

// The SDK is published as a release version, but the repository it lands in is build-local and
// accumulates every version the train has produced. Re-resolving on every build keeps a stale
// artifact from satisfying a build that is supposed to be reading THIS revision.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    // Exactly the four published contracts. There is no fifth, and adding one here is the change
    // that would make this build stop certifying the boundary.
    implementation("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
    implementation("dev.rubentxu.pipeline.v2:pipeline-events:$sdkVersion")
    implementation("dev.rubentxu.pipeline.v2:pipeline-output:$sdkVersion")
    // Declared `api` in the publisher, so it arrives on the COMPILE classpath without being asked
    // for here. That is the whole claim being tested: a consumer that names DomainEvent's
    // CacheKey property and a DomainEvent's serializer compiles without knowing that
    // `pipeline-scripting-api` and `kotlinx-serialization` exist.
    //
    // The negative control below fails this build if the transitive compile classpath is empty,
    // which is what `implementation` scope would have produced.
    implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")

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
 * The negative control, and the reason this build is a test rather than a sample.
 *
 * A consumer that compiles because a repository happened to be on the classpath proves nothing.
 * So before anything runs, this asserts that the resolved compile classpath contains each of the
 * four coordinates and nothing else from `dev.rubentxu.pipeline.v2` — in particular, that no
 * implementation module is reachable. If a future change publishes `pipeline-events-store` or
 * `pipeline-output-store`, this build fails with the name of the artifact that leaked, which is
 * the failure `pipelinek-fabric` must never discover for itself in production.
 *
 * The coordinates are read from the RESOLVED MODULE IDENTITY, not from a file path and not from
 * an artifact's display name. The first version walked `compileClasspath.files` and rebuilt
 * `group:artifact` from the Maven directory layout, assuming the group was one path segment. It is
 * four — `dev/rubentxu/pipeline/v2/` — so every coordinate came back as `pipeline-events:v2`, was
 * filtered out, and the control reported `Found: []` on a build whose surface was in fact exactly
 * right. A check that cannot fail is worse than no check, because it reads as evidence.
 *
 * The second version asked `artifact.id` for a `ModuleComponentIdentifier` and got an empty list
 * again, for a subtler reason: on a resolved artifact, `id` is the identity of the FILE
 * (`pipeline-events-0.47.0.jar`), not of the module that produced it. The module identity lives on
 * `artifact.variant.owner`. Both failures were silent and in the same direction, which is why the
 * emptiness is now asserted separately instead of being allowed to fall through as equality.
 */
val verifyPublishedSurface by tasks.registering {
    group = "verification"
    description = "Fails if the resolved compile classpath is not exactly the four published contracts."

    val compileClasspath = configurations.named("compileClasspath")
    inputs.files(compileClasspath).withPropertyName("compileClasspath").withPathSensitivity(PathSensitivity.NAME_ONLY)

    doLast {
        val coordinates = compileClasspath.get()
            .incoming
            .artifacts
            .artifacts
            .mapNotNull { artifact ->
                val owner = artifact.variant.owner
                if (owner is ModuleComponentIdentifier) "${owner.group}:${owner.module}" else null
            }
            .filter { it.startsWith("$PIPELINEK_GROUP:") }
            .distinct()
            .sorted()

        val expected = listOf(
            "$PIPELINEK_GROUP:pipeline-domain",
            "$PIPELINEK_GROUP:pipeline-events",
            "$PIPELINEK_GROUP:pipeline-output",
            "$PIPELINEK_GROUP:pipeline-scripting-api",
        )

        // An empty result is a FAILED control, not a vacuous pass. It is called out separately
        // because "nothing was on the classpath" and "exactly the right things were" produce the
        // same `==` on a broken reader, and only one of them is a working build.
        check(coordinates.isNotEmpty()) {
            """
            No $PIPELINEK_GROUP module was resolved onto the compile classpath at all, so this
            control has not actually checked anything. Resolve the coordinates from the artifact
            identities rather than from file paths.
            """.trimIndent()
        }

        check(coordinates == expected) {
            """
            The resolved PipelineK surface is not the four published contracts.
            Expected: $expected
            Found:    $coordinates

            An implementation module on this classpath means a published artifact now carries the
            filesystem, JDBC or replay internals, and a consumer could name them. A contract MISSING
            from this list means a consumer of that surface can no longer compile.
            """.trimIndent()
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPublishedSurface)
}
