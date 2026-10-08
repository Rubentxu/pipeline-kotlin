import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.4.10"
}

// ── B2A: the outside, exercised end to end ────────────────────────────────────────────────────
//
// This is an INDEPENDENT Gradle build (its own settings file, no `project(...)` anywhere). It is
// the only artifact in the repository that does all three of the following against artifacts
// produced from ONE source revision:
//
//   1. resolves the SDK through the published BOM (`pipeline-sdk-bom`), declaring the four
//      contracts WITHOUT versions and compiling a source that names one type from each;
//   2. builds the external example plugin from `examples/example-uppercase-plugin` against that
//      same published SDK;
//   3. runs the INSTALLED distribution of the product against a `.pipeline.kts` that uses the
//      plugin's own contribution, passing the JAR through `--plugin-jar`, and asserts that the
//      pipeline finished AND that the plugin actually executed.
//
// Every other check in this repository observes at most two of the three, and none observes them
// through a real product process. `examples/fabric-contract-consumer` compiles against the four
// coordinates but runs nothing; `P3DPluginEventInstalledDistributionUatTest` runs the binary but
// lives inside `:pipeline-application` and resolves the plugin from a build directory. This build
// is the outside that closes the loop.
//
// It is deliberately NOT a subproject of `v2` and deliberately NOT wired into `v2`'s `check`:
// wiring it in would make every `check` publish to `sdk-repo`, install the distribution and fork a
// second Gradle against the working tree. It is a named gate step instead (see the receipt), run
// explicitly through `:verifySdkExternalExecution`.
//
//   -PsdkRepo=<dir>      repository holding the published artifacts
//   -PsdkVersion=<ver>   version to resolve
//   -PpipelinekBin=<path> installed `pipelinek` binary (defaults to the installDist output)
val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../v2/build/sdk-repo")

// No default version, on purpose. A default could only be a version that fails to resolve, and
// failing here names the real cause instead of a phantom coordinate.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this build resolves the published PipelineK contracts and has no
    default version. Pass the candidate's version explicitly, e.g. -PsdkVersion=0.47.0
    """.trimIndent()
}

group = "example.sdk.external"
version = "0.1.0"

val sdkRepoDir: java.io.File = file(sdkRepo)

repositories {
    mavenCentral()
    maven {
        name = "sdk"
        url = uri(sdkRepoDir)
    }
}

// The repository is build-local and accumulates every version the train has produced. Re-resolving
// keeps a stale artifact from satisfying a build that reads THIS revision.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    // ENTREGA A: ONE platform coordinate, and the four contracts WITHOUT versions. If the BOM does
    // not apply its constraints, these four declarations cannot resolve and this build fails at
    // dependency resolution, before a single line is compiled. That failure is the test.
    implementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))
    implementation("dev.rubentxu.pipeline.v2:pipeline-domain")
    implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api")
    implementation("dev.rubentxu.pipeline.v2:pipeline-events")
    implementation("dev.rubentxu.pipeline.v2:pipeline-output")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

// ── ENTREGA B, step 1: build the external plugin from its own build ────────────────────────────
//
// Built by an Exec into the plugin's OWN Gradle build (independent settings file), not by a
// `project(...)` dependency: if this build could see the plugin as a subproject it would be proving
// an in-build composition, which is exactly what the external-plugin claim is not.
val repoRoot: java.io.File = rootDir.parentFile.parentFile
val pluginDir: java.io.File = repoRoot.resolve("examples/example-uppercase-plugin")
val v2Gradlew: java.io.File = repoRoot.resolve("v2/gradlew")
val externalPluginJar: java.io.File = pluginDir.resolve("build/libs/example-uppercase-plugin-0.1.0.jar")

val buildExternalPlugin by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds examples/example-uppercase-plugin against THIS revision's published SDK."
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    outputs.file(externalPluginJar)

    workingDir = repoRoot
    commandLine(
        v2Gradlew.absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.absolutePath,
        "-PsdkVersion=" + sdkVersion,
        "jar",
    )
}

// ── ENTREGA B, step 2/3: run the installed distribution and assert ─────────────────────────────
//
// The real binary is the SUBJECT; the test class only orchestrates it and reads its result. The
// installed binary is a build output of `:pipeline-application:installDist` in `v2`; this build
// does not produce it and does not reach into `v2` for it — the root task that launches this build
// depends on `installDist`, and the default path below is where that task leaves it.
val pipelinekBin: java.io.File = file(
    providers.gradleProperty("pipelinekBin").getOrElse(
        repoRoot.resolve("v2/pipeline-application/build/install/pipelinek/bin/pipelinek").absolutePath,
    ),
)

val externalScript: java.io.File = file("fixtures/external.pipeline.kts")

tasks.test {
    useJUnitPlatform()
    // A test whose subject is optional is a test that passes by omission. Both the plugin JAR and
    // the installed binary are built before the assertion runs, or the build fails.
    dependsOn(buildExternalPlugin)

    systemProperty("pipelinek.bin", pipelinekBin.absolutePath)
    systemProperty("example.plugin.jar", externalPluginJar.absolutePath)
    systemProperty("external.script", externalScript.absolutePath)

    // Hermetic temp root. JUnit `@TempDir` resolves `java.io.tmpdir`, which by default is `/tmp` —
    // often RAM-backed and always outside this build. Point it at a build-local directory so a
    // fixture never lands in `/tmp` and every run is cleaned with the build.
    val tempRoot = layout.buildDirectory.dir("test-tmp")
    doFirst { tempRoot.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", tempRoot.get().asFile.absolutePath)

    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
