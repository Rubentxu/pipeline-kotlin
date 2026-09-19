plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

// The V2 root is an aggregate build. Its lifecycle check is the repository
// gate and deliberately covers every active V2 subproject declared in settings.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        rootProject.tasks.named("check") {
            dependsOn(tasks.named("check"))
        }
    }
}

// Lane R: expose the repository root to tests. Tests that must read files from the
// repository previously hardcoded a developer-specific absolute path such as
// /var/home/<user>/Proyectos/kotlin/pipeline-kotlin, which pinned the suite to one
// machine and to one worktree. rootDir is the v2 build root; its parent is the repository.
val repositoryRoot: String = rootDir.parentFile.absolutePath

subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("pipeline.repoRoot", repositoryRoot)
    }
}

// WU-LPR-070: reproducible archives (BUILD ONCE / PUBLISH SAME BYTES).
// Nested project jars feed the pipelinek distZip; without these flags the
// embedded jar CRCs change on every rebuild (observed 2026-09-19).
subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// ── Lane R: external plugin reproducibility ────────────────────────────────────
// example-uppercase-plugin is an INDEPENDENT Gradle build (its own settings file),
// not a subproject of v2: that independence is the property it certifies. It
// compiles against SDK artifacts, so those artifacts must be produced from THIS
// revision and handed to it explicitly.
//
// Before this, the plugin consumed committed libs/*.jar snapshots (silent drift)
// and its own jar had no producing task anywhere in v2, so :pipeline-application
// could not compile from a clean checkout.
val sdkRepoDir = layout.buildDirectory.dir("sdk-repo")

val publishSdkForExternalPlugin by tasks.registering {
    group = "build"
    description = "Publishes the SDK artifacts the external example plugin compiles against."
    dependsOn(
        ":pipeline-domain:publishSdkPublicationToSdkRepository",
        ":pipeline-scripting-api:publishSdkPublicationToSdkRepository",
    )
}

val buildExamplePlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external example plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("../examples/example-uppercase-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    // The plugin's output depends on the SDK jars of THIS revision, not on the
    // repository's timestamped snapshot filenames.
    inputs.files(":pipeline-domain:jar", ":pipeline-scripting-api:jar")
    outputs.file(pluginDir.resolve("build/libs/example-uppercase-plugin-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "jar",
    )
}
