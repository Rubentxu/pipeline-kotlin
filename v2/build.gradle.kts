plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
}

group = "dev.rubentxu.pipeline.v2"
version = "0.1.0-SNAPSHOT"

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

// ── Lane R: utilities.json OFFICIAL_PLUGIN reproducibility (FASE 6 / LFC-2E2) ──
// utilities-plugin is the FIRST plugin shipped against the frozen universal-core
// authoring surface. It depends only on the public SDK contracts (pipeline-domain +
// pipeline-scripting-api) and registers through the StepDefinitionContributor SPI.
// It is an INDEPENDENT Gradle build (its own settings file) so the property it
// certifies — "a plugin ships zero changes in production core" — is preserved.
//
// Same Lane R pattern as example-uppercase-plugin: SDK artifacts must come from
// THIS revision, never from committed snapshot jars.
val buildUtilitiesPlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent OFFICIAL utilities.json plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("../examples/utilities-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar", ":pipeline-scripting-api:jar")
    outputs.file(pluginDir.resolve("build/libs/utilities-plugin-1.0.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "jar",
    )
}

// ── Lane R: testing OFFICIAL_PLUGIN reproducibility (LFC-2E3) ──
// testing-plugin is the SECOND OFFICIAL_PLUGIN under the frozen universal-core
// authoring surface (the first vertical of LFC-2E3-TESTING-REPORTS). It proves
// that a NEW dimension — structured test results + HTML report publication —
// can be added under a SEPARATE plugin coordinate (`pipeline.testing@0.1.0-SNAPSHOT`)
// without changing production core.
//
// Same Lane R pattern as utilities-plugin: SDK artifacts must come from
// THIS revision, never from committed snapshot jars. The build artefact path
// uses `-SNAPSHOT` because the testing coordinate is still under iteration
// (R1 publishHTML will follow the same coordinate; a stable 1.0.0 release
// waits on the full E3-A closure).
val buildTestingPlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent OFFICIAL testing plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("../examples/testing-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar", ":pipeline-scripting-api:jar")
    outputs.file(pluginDir.resolve("build/libs/testing-plugin-0.1.0-SNAPSHOT.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "jar",
    )
}
