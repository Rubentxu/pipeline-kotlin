import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // `com.google.devtools.ksp` was declared here for the Step-descriptor generator,
        // which synthesised a second StepDescriptor authority from @Step annotations and had
        // zero consumers. No module applies it any more, and the declaration is removed so
        // the plugin cannot be re-applied by accident. Reintroducing a compiler layer that
        // writes Step metadata is pinned by NoSecondStepMetadataAuthorityFitnessTest.
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
        // Selective mutation testing (codecs/policies only).
        id("info.solidsoft.pitest") version "1.19.0"
        // SAST. detekt 2.0.0-alpha.6 = Kotlin 2.4.10 compatible.
        id("dev.detekt") version "2.0.0-alpha.6"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pipeline-v2"

include(
    ":pipeline-domain",
    ":pipeline-application",
    // Product identity, distribution manifest, candidate
    // handoff and the cheap identity gate. Pure contract plus the ZIP probe;
    // the build wires the gate at candidate materialization.
    // Three QA modules were REMOVED from this list :
    // `:test-harness` (35 sources, executable contract catalogue; sole consumer was
    // `testImplementation` in pipeline-application), `:pipeline-testkit` (4 sources,
    // ZERO consumers -- `PipelineFixture` was an empty class and `pipelineDefinitionShould`
    // was `@Suppress("unused")`), and `:pipeline-architecture-tests` (a fitness harness whose
    // last remaining consumer was `dependsOn(":pipeline-architecture-tests:test")` in
    //:pipeline-release, also removed). None was reachable from `src/main`.
    // NOT removed, despite the name: `:pipeline-event-harness` and `:pipeline-step-sdk:junit`
    // stay `include`d because both are PRODUCT -- the first is the `pipeline events verify`
    // CLI verb, the second is the `junit.results` OFFICIAL_PLUGIN, and both are wired with
    // `implementation`, not `testImplementation`.
    ":pipeline-release",
    ":pipeline-scripting-api",
    // The SDK BOM. A `java-platform` whose only content is a version constraint per published
    // contract. It has no classes, no tests and no ABI: it is a RESOLUTION convenience, not a fifth
    // contract, and it exists so an external consumer can name one coordinate and declare the four
    // published contracts without repeating the version. See its build script for why that
    // distinction is load-bearing.
    ":pipeline-sdk-bom",
    ":pipeline-scripting-kotlin24",
    ":pipeline-events",
    // The event plane's durable implementation: journal, replay cursor, run lease, and the
    // in-memory / JSON / SQLite stores. Split out of `:pipeline-events` so the published contract
    // carries no SQLite schema, no filesystem store and no replay protocol. Not published.
    ":pipeline-events-store",
    ":pipeline-event-harness",
    // The Output Plane. Separate:pipeline-events on purpose — output continuation is
    // An independent order, and the module graph is what keeps it independent.
    // PUBLISHED CONTRACT: the read side an external consumer (Fabric) needs, and nothing else.
    ":pipeline-output",
    // The Output Plane's segment/filesystem implementation. Split out:pipeline-output so the
    // published artifact carries no writer, no recovery entry point and no filesystem authority.
    // Not published, and deliberately so: see its build script.
    ":pipeline-output-store",
    // Module split precondition (NOT a publication).
    // `:pipeline-runtime-api` holds the four verbs (RuntimeRecoverPort /
    // RuntimeControlPort / RuntimeIntrospectionPort) plus the public pure decider
    // RuntimeRecoverDecision and their sealed ADTs / option types. Its
    // `dependencies { }` block is `api :pipeline-output` only — no `:pipeline-events`,
    // no store-side module — so a future publication does not leak store dependencies
    // into the published POM. It is NOT yet in `publishedContractModules`; this
    // commit is the structural precondition only.
    ":pipeline-runtime-api",
    // The runtime surface. The host-internal module that holds the three
    // adapters and the store composition. Depends on the new `:pipeline-runtime-api`
    // for the port interfaces (so adapter sources see the published types) and on
    // the internal `:pipeline-events-store` / `:pipeline-output-store` for adapter
    // wiring (declared as `implementation` in the module's build script to keep
    //'s internal types out of the published ABI). Not published.
    ":pipeline-runtime",
    ":pipeline-step-sdk:api",
    ":pipeline-step-sdk:runtime",
    ":pipeline-step-sdk:scm-git",
    ":pipeline-step-sdk:http",
    ":pipeline-step-sdk:junit",
    ":pipeline-step-sdk:files",
    ":pipeline-step-sdk:utilities",
    ":pipeline-step-sdk:workflow-control",
    ":pipeline-credentials-api",
    ":pipeline-credentials-local",
    ":pipeline-credentials-multipart",
    ":pipeline-credentials-executor",
    ":pipeline-binding-factory",
    ":pipeline-artefacts-local",
)

// A Gradle invocation mutates the shared v2/ */build tree. Two invocations on
// this checkout can otherwise race while writing compiler caches and produce
// failures that disappear when the task is rerun in isolation. Keep the
// protection at settings evaluation so every `./gradlew -p v2 ...` entry point
// gets the same fail-fast contract.
val buildLockPath = rootDir.resolve(".gradle/pipelinek-build.lock").toPath()
Files.createDirectories(buildLockPath.parent)
val buildLockChannel = FileChannel.open(
    buildLockPath,
    StandardOpenOption.CREATE,
    StandardOpenOption.WRITE,
)
val buildLock = try {
    buildLockChannel.tryLock()
} catch (_: OverlappingFileLockException) {
    null
}

if (buildLock == null) {
    buildLockChannel.close()
    throw GradleException(
        "Another Gradle invocation is already using this v2 checkout. " +
            "Run concurrent builds from separate worktrees.",
    )
}

val acquiredBuildLock = requireNotNull(buildLock)
gradle.buildFinished {
    if (acquiredBuildLock.isValid) {
        acquiredBuildLock.release()
    }
    buildLockChannel.close()
}
