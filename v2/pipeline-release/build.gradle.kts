plugins {
    kotlin("jvm")
    // Matched to the Kotlin version (2.4.10), NOT the kotlinx-serialization
    // runtime version. The catalog alias `libs.plugins.kotlin.serialization`
    // binds to the runtime version and does not resolve as a Gradle plugin id.
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-step-sdk:runtime"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // PK-RELEASE-CONSOLIDATION H1.4: the test task asserts the SBOM
    // exists next to the distribution ZIP. The candidateAdmission task
    // is what stages it there, and the test must depend on it (or on
    // the cyclonedxBom that produces the source) so a developer
    // running `:pipeline-release:test` directly observes the same
    // staging the releaseGate does.
    dependsOn(":pipeline-application:cyclonedxBom")
    dependsOn("candidateAdmission")
    // Honour a system property override so CI can point the real-artifact
    // checks at a different build tree. Default: this module lives at
    // v2/pipeline-release/, so the parent is v2/.
    val v2RootDefault = projectDir.parentFile.absolutePath
    systemProperty("release.v2.root", System.getProperty("release.v2.root", v2RootDefault))
}

// ---------------------------------------------------------------------------
// DIST-PRODUCT P0.3 build wiring: the cheap identity gate.
//
// This task is the boundary. It runs AFTER the distribution ZIP exists, asks
// the release model whether those bytes are an admissible candidate, and fails
// the build when they are not. Until this task existed, the identity model was
// exercised only by tests — a green build did not mean an admissible candidate.
//
// The task is intentionally thin. It resolves the ZIP, shells out to the
// `candidate-admission` main, and propagates a non-zero exit. It re-derives no
// admission rules, so the build and the tested model cannot drift apart.
//
// Candidate sequence is a build parameter rather than a constant: a release
// train publishes several candidates, and the sequence is what orders them.
// ---------------------------------------------------------------------------

val candidateSequence = providers.gradleProperty("candidate.sequence")
    .orElse("1")
    .get()

val candidateTag = providers.gradleProperty("candidate.tag")
    .orElse("")
    .get()

tasks.register<JavaExec>("candidateAdmission") {
    group = "distribution"
    description = "DIST-PRODUCT P0.3: cheap identity admission for the distribution ZIP."

    dependsOn(":pipeline-application:distZip")
    // PR-012 / WU-RP-5: every admitted candidate carries its SBOM. The cyclonedx plugin
    // already produces build/reports/bom.json; the admission previously recorded
    // "sbom": null because nothing placed the BOM where the probe looks. Generate it as
    // part of admission and stage it under the probed name — no manual step, and the
    // handoff stops shipping an SBOM hole.
    dependsOn(":pipeline-application:cyclonedxBom")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.CandidateAdmissionMainKt")

    val distDir = project(":pipeline-application").layout.buildDirectory.dir("distributions")
    val outDir = layout.buildDirectory.dir("candidate")

    // Declared at configuration time: Gradle rejects an outputs mutation once
    // a task has started. Registering the directory here also gives the task a
    // real output, so it re-runs when the emitted material changes.
    outputs.dir(outDir)
    inputs.property("productVersion", project.version.toString())
    inputs.property("candidateSequence", candidateSequence)

    // Resolved lazily inside doFirst: the ZIP does not exist at configuration
    // time, and a configuration-time existence check would fail the build for
    // the wrong reason.
    doFirst {
        val version = project.version.toString()
        val dist = distDir.get().asFile
        val zip = dist.resolve("pipelinek-$version.zip")
        if (!zip.isFile) {
            throw GradleException(
                "DIST-PRODUCT P0.3: expected distribution ZIP at $zip but it does not exist. " +
                    "Run :pipeline-application:distZip first.",
            )
        }

        // Stage the freshly generated BOM under the probed name. The task dependency above
        // guarantees it exists; the copy is what makes it OBSERVABLE to the admission probe,
        // which only looks inside the distributions directory.
        val bomSource = project(":pipeline-application").layout.buildDirectory
            .file("reports/bom.json").get().asFile
        if (bomSource.isFile) {
            bomSource.copyTo(dist.resolve("pipelinek-$version.sbom.json"), overwrite = true)
        }

        val sbom = listOf(
            dist.resolve("pipelinek-$version.sbom.json"),
            dist.resolve("bom.json"),
        ).firstOrNull { it.isFile }

        args = listOf(
            zip.absolutePath,
            version,
            gitCommitOf(rootProject),
            outDir.get().asFile.absolutePath,
            candidateTag,
            candidateSequence,
            sbom?.absolutePath ?: "-",
            // P0.5 — the repository whose version control state defines this
            // candidate's provenance. RootProject lives in v2/, whose parent
            // holds the .git directory.
            rootProject.projectDir.parentFile.absolutePath,
        )
    }

    // Any non-zero exit means the candidate was refused. Propagate it verbatim:
    // the diagnostic printed by the model is the operator-facing message, and
    // wrapping or swallowing it would hide the reason.
    isIgnoreExitValue = false
}


/**
 * The commit the candidate was built from, or an explicit marker when git is
 * unavailable (an exported source tree, for example). Never an empty string:
 * the manifest records provenance, and an empty commit would be a claim about
 * a build nobody can trace.
 *
 * NOTE (P0.5): this value alone does NOT establish provenance. It is git HEAD
 * while the ZIP is compiled from the working tree, so a dirty tree would make
 * it a false address. The build's real guarantee is the clean-tree law in
 * `SourceProvenance`, enforced by the `candidateAdmission` task; this function
 * only supplies the address to be verified.
 */
fun gitCommitOf(root: Project): String = try {
    providers.exec {
        workingDir(root.projectDir.parentFile)
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
} catch (_: Exception) {
    "unknown"
}

// ----------------------------------------------------------------------------
// PK-RC-PLUGIN-001 — the release gate.
//
// A release MUST NOT be published without this gate producing a PASS receipt
// bound to the ZIP's sha256. The gate runs:
//   1. `:pipeline-application:distZip`             (the candidate bytes)
//   2. `:pipeline-application:cyclonedxBom`       (the SBOM)
//   3. `:pipeline-release:candidateAdmission`      (the BundledPluginCompatibility
//                                                   + identity + provenance checks)
//
// Any non-zero exit from any of the three propagates verbatim. There is no
// "I will skip the gate" escape: the only way to advance is for the gate to
// admit the bytes the ZIP actually contains.
//
// Block 3 of the spec also asked the gate to be reachable from `check` so a
// developer without a clean tree can run it locally. The `releaseGate` task
// is wired as a sibling of the standard lifecycle: it does NOT depend on
// `check` (which is test-time, takes minutes, and is orthogonal to the
// candidate's own identity) but it is a single Gradle invocation, so a
// developer can `gradle :pipeline-release:releaseGate` and see the verdict
// without producing a Prerelease.
// ----------------------------------------------------------------------------

tasks.register("releaseGate") {
    group = "distribution"
    description = "PK-RC-PLUGIN-001 + PK-RC-RENDER-001 + PK-NEXT R0.2 + PK-RELEASE-CONSOLIDATION H1.4: " +
        "run the candidate gate end-to-end. Dist ZIP + cyclonedxBom + candidateAdmission + " +
        "renderer partition + application regression suite + mandatory-plugin test (7 plugins: 4 " +
        "official + 3 external) + LaunchReceipt contract + SDK BOM alignment. " +
        "Non-zero on any refusal. The mandatory-plugin test fails (not skips) when " +
        "PIPELINEK_DIST_ZIP is unset, so a missing distribution is a hard red."

    dependsOn(
        ":pipeline-application:distZip",
        ":pipeline-application:cyclonedxBom",
        "candidateAdmission",
        // PK-RC-RENDER-001: the renderer partition test would have caught
        // the rc4 regression (`run: [RunCorrelated]`) if it had been in
        // this gate. Adding it here is the structural reason a RunCorrelated
        // added to the routing table cannot survive a release without a
        // matching arm in `ConsoleFamilyLines.run(...)`.
        ":pipeline-architecture-tests:test",
        ":pipeline-application:test",
        // PK-RC-PLUGIN-001 / R0.2: the mandatory-plugin test must observe the
        // distZip the gate just built. Wired below as both env var and
        // system property (the test reads env first, then property).
        // PK-RELEASE-CONSOLIDATION H1.4: this test class now asserts the
        // seven-plugin surface (4 official + 3 external), the LaunchReceipt
        // schema token, and the SBOM ↔ SDK BOM alignment. The mutation
        // tests in DistributionMandatoryPluginsMutationTest run as part of
        // the same `test` task and are NOT separately wired because they
        // share the test runtime; the gate fails if any of them is
        // missing or skipped.
        "test",
    )

    doFirst {
        val distZip = project(":pipeline-application").layout.buildDirectory
            .file("distributions/pipelinek-${project.version}.zip").get().asFile
        if (!distZip.isFile) {
            throw GradleException(
                "releaseGate: expected distribution ZIP at $distZip but it does not " +
                    "exist. Run :pipeline-application:distZip first.",
            )
        }
        // R0.2: the mandatory-plugin test reads System.getenv, so the gate
        // MUST pass the path as an env var, not as a system property. The
        // system property below is the same value re-published for the
        // convenience of a developer running the test by hand.
        val testTask = tasks.named("test") as Test
        testTask.environment("PIPELINEK_DIST_ZIP", distZip.absolutePath)
        testTask.systemProperty("release.dist.zip", distZip.absolutePath)
    }
}
