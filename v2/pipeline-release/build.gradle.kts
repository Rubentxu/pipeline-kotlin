import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

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
    // PK-RELEASE-CONSOLIDATION R1.1 (audit fix): the audit of
    // 12c3ac17 flagged that releaseGate's previous wiring set
    // PIPELINEK_DIST_ZIP inside doFirst on the aggregator task.
    // By the time the doFirst runs, the test task it depends on
    // has already forked its JVM (or completed) and the env var
    // arrives too late. The fix is to attach the env var to the
    // test task's OWN configuration, with a provider that points
    // at the distZip output. Adding distZip as a direct dependency
    // here also means a developer running `:pipeline-release:test`
    // by hand gets the same wiring as the gate.
    val distZipProvider: Provider<RegularFile> = project(":pipeline-application")
        .layout.buildDirectory
        .file("distributions/pipelinek-${project.version}.zip")
    dependsOn(":pipeline-application:distZip")
    inputs.file(distZipProvider)
        .withPropertyName("release.dist.zip.input")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val distZipPath = distZipProvider.get().asFile.absolutePath
    environment("PIPELINEK_DIST_ZIP", distZipPath)
    systemProperty("release.dist.zip", distZipPath)
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
        // PK-RC-PLUGIN-001 / R0.2: the mandatory-plugin test now observes
        // the distZip through the test task's own configuration (env var
        // + system property), not through a doFirst on the aggregator
        // (audit R1.1 fix). The dependsOn on distZip ensures the file
        // exists before the test task is scheduled.
        "test",
    )

    // PK-RELEASE-CONSOLIDATION R1.1 (audit fix): previously this doFirst
    // called `testTask.environment("PIPELINEK_DIST_ZIP", ...)`. That
    // arrived too late — by the time releaseGate's doFirst ran, the
    // test task it depends on had either already forked or already
    // completed. The env var is now attached to the test task's own
    // configuration (above), so a developer running just
    // `:pipeline-release:test` gets the same wiring as the gate.
    //
    // The doFirst is now a sanity check only: the dependency on
    // :pipeline-application:distZip guarantees the file exists by the
    // time the test JVM forks, but if a developer bypasses the graph
    // (cleaning the build dir mid-run, for instance) we want a hard
    // error rather than a confusing "zip not found" deep in the test.
    doFirst {
        val distZip = project(":pipeline-application").layout.buildDirectory
            .file("distributions/pipelinek-${project.version}.zip").get().asFile
        if (!distZip.isFile) {
            throw GradleException(
                "releaseGate: expected distribution ZIP at $distZip but it does not " +
                    "exist. Run :pipeline-application:distZip first.",
            )
        }
    }
}
