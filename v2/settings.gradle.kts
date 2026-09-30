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
        id("com.google.devtools.ksp") version "2.3.11"
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
    ":pipeline-event-harness",
    ":pipeline-step-sdk:api",
    ":pipeline-step-sdk:processor",
    ":pipeline-step-sdk:runtime",
    ":pipeline-step-sdk:scm-git",
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
