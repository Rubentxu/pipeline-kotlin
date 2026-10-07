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
        // S6/H: `com.google.devtools.ksp` was declared here for the Step-descriptor generator,
        // which synthesised a second StepDescriptor authority from @Step annotations and had
        // zero consumers. No module applies it any more, and the declaration is removed so
        // the plugin cannot be re-applied by accident. Reintroducing a compiler layer that
        // writes Step metadata is pinned by NoSecondStepMetadataAuthorityFitnessTest.
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
        // WU-RP-040 R4: selective mutation testing (codecs/policies only).
        id("info.solidsoft.pitest") version "1.19.0"
        // WU-RP-040 R5: SAST. detekt 2.0.0-alpha.6 = Kotlin 2.4.10 compatible.
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
    // DIST-PRODUCT P0: product identity, distribution manifest, candidate
    // handoff and the cheap identity gate. Pure contract plus the ZIP probe;
    // the build wires the gate at candidate materialization. Kept separate from
    // :pipeline-architecture-tests so the build can run these checks without
    // depending on the fitness harness.
    ":pipeline-release",
    ":pipeline-scripting-api",
    ":pipeline-scripting-kotlin24",
    ":pipeline-testkit",
    ":pipeline-architecture-tests",
    ":pipeline-events",
    // The event plane's durable implementation: journal, replay cursor, run lease, and the
    // in-memory / JSON / SQLite stores. Split out of `:pipeline-events` so the published contract
    // carries no SQLite schema, no filesystem store and no replay protocol. Not published.
    ":pipeline-events-store",
    ":pipeline-event-harness",
    // M1: the Output Plane. Separate from :pipeline-events on purpose — output continuation is
    // an independent order (ADR-M1 D3), and the module graph is what keeps it independent.
    // PUBLISHED CONTRACT: the read side an external consumer (Fabric) needs, and nothing else.
    ":pipeline-output",
    // The Output Plane's segment/filesystem implementation. Split out of :pipeline-output so the
    // published artifact carries no writer, no recovery entry point and no filesystem authority.
    // Not published, and deliberately so: see its build script.
    ":pipeline-output-store",
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

// A Gradle invocation mutates the shared v2/*/build tree. Two invocations on
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
