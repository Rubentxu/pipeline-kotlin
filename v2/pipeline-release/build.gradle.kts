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
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The JUnit lane below is NOT the lane that was removed on. That one certified the
// SHIPPED artifact -- it needed the distribution ZIP, the SBOM staging step and the
// `PIPELINEK_DIST_ZIP` environment, and it executed zero classes while naming a real ZIP in its
// output. This one pins pure functions (`apiRangeContains`, `semverCompare`, `evaluate`) that
// decide whether a candidate may ship. AGENTS.md section 2 forbids exactly the ingredients the
// removed lane needed -- filesystem, JAR, Gradle invocation -- and none of them appear here, so
// the candidate-admission model is testable without ever touching the artifact it admits.
// `useJUnitPlatform()` is load-bearing. Gradle defaults to the JUnit 4 runner, which discovers
// zero JUnit 5 tests and reports BUILD SUCCESSFUL over an empty result set: a green gate that ran
// nothing, which is how the removed lane looked from the outside.
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------------------
// The cheap identity gate.
// This task is the boundary. It runs AFTER the distribution ZIP exists, asks
// the release model whether those bytes are an admissible candidate, and fails
// the build when they are not. Until this task existed, the identity model was
// exercised only by tests — a green build did not mean an admissible candidate.
// The task is intentionally thin. It resolves the ZIP, shells out to the
// `candidate-admission` main, and propagates a non-zero exit. It re-derives no
// admission rules, so the build and the tested model cannot drift apart.
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
    // Every admitted candidate carries its SBOM. The cyclonedx plugin
    // already produces build/reports/bom.json; the admission previously recorded
    // "sbom": null because nothing placed the BOM where the probe looks. Generate it as
    // part of admission and stage it under the probed name — no manual step, and the
    // handoff stops shipping an SBOM hole.
    dependsOn(":pipeline-application:cyclonedxBom")
    // of the project's DISTRIBUTION-FIRST RELEASE POLICY lists
    // `pipelinek-<v>.sdk.zip` and `pipelinek-bom-<v>.json` as required
    // release assets. The new `:pipeline-release:sdkZip` task produces
    // them from the local Maven repo populated by
    // `:publishSdkForExternalPlugin`. The candidate manifest records
    // both, distinct from the SBOM (which is the CycloneDX dependency
    // inventory, not the SDK distribution).
    dependsOn("sdkZip")
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
        // It is checked rather than assumed. `dependsOn` declares the intent to run
        // `:pipeline-application:cyclonedxBom`; it does not promise the task emitted a
        // file, and a task that ran and wrote nothing looks exactly like one that was
        // never declared.
        val bomSource = project(":pipeline-application").layout.buildDirectory
            .file("reports/bom.json").get().asFile
        if (!bomSource.isFile) {
            throw GradleException(
                "DIST-PRODUCT: :pipeline-application:cyclonedxBom is a declared dependency of " +
                    "this task but produced no $bomSource. Refusing to admit a candidate whose " +
                    "SBOM is absent: §8 of the DISTRIBUTION-FIRST RELEASE POLICY lists " +
                    "pipelinek-$version.sbom.json as a required asset, and an asset set with a " +
                    "hole in it is not a smaller candidate, it is a different product.",
            )
        }
        bomSource.copyTo(dist.resolve("pipelinek-$version.sbom.json"), overwrite = true)

        val sbom = dist.resolve("pipelinek-$version.sbom.json")

        // names three more assets besides the ZIP, and each one is backed by a
        // `dependsOn` above. An absent file after a declared dependency ran is not an
        // optional state; it is a dependency that did not produce what it promised.
        // The previous shape degraded silently on all three. `takeIf { it.isFile }` turned
        // the absence into null, null was written into argv as "-", and
        // `CandidateAdmissionMain` mapped "-" back to null — so the handoff recorded a
        // candidate with no SDK and no BOM while the task itself went green. `covered` shrank
        // with them, and `covered.isNotEmpty()` was the only completeness invariant
        // `CandidateMaterializer` had, so a `SHA256SUMS` covering the ZIP alone satisfied it.
        // Measured in an earlier build: three mandatory assets could be missing with every existing
        // invariant holding and no diagnostic emitted.
        // `covered.isNotEmpty()` is no longer the ONLY invariant there. The materializer made the
        // materializer refuse a non-empty `covered` with no `sha256sums` path, which is the
        // second half of this defect class and landed after the paragraph above was written.
        // The guards here are still the first line: they refuse at the producer, where the
        // absent dependency is nameable, rather than at the consumer.
        // The ZIP check above already fails closed. These three now do too, and each refusal
        // names the dependency that was supposed to produce the file.
        fun requiredAsset(asset: String, file: File, producedBy: String): File {
            if (!file.isFile) {
                throw GradleException(
                    "DIST-PRODUCT: $producedBy is a declared dependency of this task but " +
                        "produced no $file. Refusing to admit a candidate without $asset; " +
                        "§8 of the DISTRIBUTION-FIRST RELEASE POLICY fixes the asset set " +
                        "exactly, and a missing member is a defect to fix, not a variant to " +
                        "ship around.",
                )
            }
            return file
        }

        val sdkZip = requiredAsset(
            "pipelinek-$version.sdk.zip",
            dist.resolve("pipelinek-$version.sdk.zip"),
            ":pipeline-release:sdkZip",
        )
        val bomJson = requiredAsset(
            "pipelinek-bom-$version.json",
            dist.resolve("pipelinek-bom-$version.json"),
            ":pipeline-release:sdkZip",
        )

        // — the materializer writes SHA256SUMS covering these four
        // distributable assets. The list is `;`-separated on the CLI.
        // It used to be `listOfNotNull(...)` with three `?: "-"` sentinels in the
        // argv beside it, because the three assets were `takeIf { it.isFile }` and
        // could be absent. The checks above now make each of them a non-null `File`,
        // so both forms are gone: a reader of this block can no longer mistake
        // absence for a legitimate value. `covered` is exactly four paths or the task
        // has already thrown.
        val covered = listOf(
            zip,
            sbom,
            sdkZip,
            bomJson,
        )

        args = listOf(
            zip.absolutePath,
            version,
            gitCommitOf(rootProject),
            outDir.get().asFile.absolutePath,
            candidateTag,
            candidateSequence,
            sbom.absolutePath,
            sdkZip.absolutePath,
            bomJson.absolutePath,
            // The repository whose version control state defines this
            // candidate's provenance. RootProject lives in v2/, whose parent
            // holds.git directory.
            rootProject.projectDir.parentFile.absolutePath,
            covered.joinToString(";") { it.absolutePath },
        )
    }

    // Any non-zero exit means the candidate was refused. Propagate it verbatim:
    // the diagnostic printed by the model is the operator-facing message, and
    // wrapping or swallowing it would hide the reason.
    isIgnoreExitValue = false
}


/**
 * Build the SDK distribution ZIP and the SDK BOM JSON manifest.
 *  of the project's DISTRIBUTION-FIRST RELEASE POLICY requires both
 * `pipelinek-<v>.sdk.zip` and `pipelinek-bom-<v>.json` as exact release
 * assets, distinct from the SBOM (which is a CycloneDX dependency
 * inventory) and from the distribution ZIP (which is the CLI). The SDK
 * ZIP carries the public contracts (`pipeline-domain`,
 * `pipeline-scripting-api`, `pipeline-events`, `pipeline-output`,
 * `pipeline-runtime-api`) and the BOM POM that pins them.
 * Source: `<v2>/build/sdk-repo/`, the local Maven repository populated
 * by `:publishSdkForExternalPlugin`. The builder selects every entry
 * whose folder ends with `/<version>/` and zips it. The BOM JSON is a
 * flat manifest of `group:artifact:version` triples plus a per-file
 * SHA-256 inventory of the ZIP contents.
 * The managed-coordinates list MUST mirror
 * `publishedContractModules` in the root build. The `sdkBomModules`
 * list (the `:pipeline-sdk-bom` POM itself) is included implicitly
 * because the builder scans the repo.
*/
tasks.register<JavaExec>("sdkZip") {
    group = "distribution"
    description = "§8: build pipelinek-<v>.sdk.zip and pipelinek-bom-<v>.json " +
        "from build/sdk-repo/ (the local Maven repo populated by " +
        ":publishSdkForExternalPlugin)."

    // The SDKs are published to <v2>/build/sdk-repo/ by this task; the
    // BOM POM is published by the same task. Without this dependency
    // the repo is empty and the builder refuses with "no SDK entries
    // found".
    dependsOn(":publishSdkForExternalPlugin")

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.SdkZipMainKt")

    val distDir = project(":pipeline-application").layout.buildDirectory.dir("distributions")
    val sdkRepo = rootProject.layout.buildDirectory.dir("sdk-repo")

    // Declared at configuration time so the task is a real producer in
    // the dependency graph; `candidateAdmission` reads the artifacts
    // from `distDir` after this task has written them.
    outputs.dir(distDir)
    inputs.property("productVersion", project.version.toString())

    // The list of `group:artifact` pairs the BOM pins. Mirrors
    // `publishedContractModules` in the root build (the five SDK
    // contracts). The `:pipeline-sdk-bom` POM is in the SDK ZIP
    // implicitly; the JSON manifest only needs the contract list.
    val managedCoordinates = listOf(
        "dev.rubentxu.pipeline.v2:pipeline-domain",
        "dev.rubentxu.pipeline.v2:pipeline-scripting-api",
        "dev.rubentxu.pipeline.v2:pipeline-events",
        "dev.rubentxu.pipeline.v2:pipeline-output",
        "dev.rubentxu.pipeline.v2:pipeline-runtime-api",
    )

    doFirst {
        val version = project.version.toString()
        val sdkRepoDir = sdkRepo.get().asFile
        if (!sdkRepoDir.isDirectory) {
            throw GradleException(
                "§8 sdkZip: expected SDK repository at $sdkRepoDir but the directory " +
                    "does not exist. Did :publishSdkForExternalPlugin run?",
            )
        }
        args = listOf(
            sdkRepoDir.absolutePath,
            version,
            distDir.get().asFile.absolutePath,
        ) + managedCoordinates
    }

    isIgnoreExitValue = false
}


/**
 * The commit the candidate was built from, or an explicit marker when git is
 * unavailable (an exported source tree, for example). Never an empty string:
 * the manifest records provenance, and an empty commit would be a claim about
 * a build nobody can trace.
 * This value alone does NOT establish provenance. It is git HEAD
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
// The release gate.
// A release MUST NOT be published without this gate producing a PASS receipt
// bound to the ZIP's sha256. The gate runs:
//   1. `:pipeline-application:distZip`             (the candidate bytes)
//   2. `:pipeline-application:cyclonedxBom`       (the SBOM)
//   3. `:pipeline-release:candidateAdmission`      (the BundledPluginCompatibility
//                                                   + identity + provenance checks)
// Any non-zero exit from any of the three propagates verbatim. There is no
// "I will skip the gate" escape: the only way to advance is for the gate to
// admit the bytes the ZIP actually contains.
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
        "LaunchReceipt contract + SDK BOM alignment. Non-zero on any refusal."
    // The description used to enumerate four certifications that no longer run here: the
    // renderer partition test, the application regression suite, and the mandatory-plugin test
    // (7 plugins) whose failure-on-unset-`PIPELINEK_DIST_ZIP` was the gate's hard-red path for a
    // missing distribution. All three read a JUnit source set that is empty. Naming them would
    // have been the exact false compliance this removal exists to end: a task description is read
    // as a claim about what ran. The distribution is now exercised by `scripts/verify-all.sh`
    // and `v2/examples/run.sh`, which run the real binary.

    dependsOn(
        ":pipeline-application:distZip",
        ":pipeline-application:cyclonedxBom",
        "candidateAdmission",
        // The three external example plugin JARs must be rebuilt before a candidate is
        // admitted, so the gate is not run against stale bytes: a candidate is only as honest
        // as the plugin JARs it ships. These are paid for ONCE per release gate run.
        ":buildExamplePlugin",
        ":buildExternalDirectivePlugin",
        ":buildExampleBlockPlugin",
    )

    // Previously this doFirst
    // called `testTask.environment("PIPELINEK_DIST_ZIP", ...)`. That
    // arrived too late — by the time releaseGate's doFirst ran, the
    // test task it depends on had either already forked or already
    // completed. The env var is now attached to the test task's own
    // configuration (above), so a developer running just
    // `:pipeline-release:test` gets the same wiring as the gate.
    // The doFirst is now a sanity check only: the dependency on
    //:pipeline-application:distZip guarantees the file exists by the
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

// The JUnit lane was REMOVED . Everything it configured existed to
// certify the SHIPPED artifact from a JVM: the SBOM staging dependency, the
// `PIPELINEK_DIST_ZIP` environment plumbing, the `release.dist.zip` and `release.v2.root` system
// properties, and the `releaseGate` entries that depended on `:test` and on
// `:pipeline-application:test`.
// Gradle 8.14 has no `Test.failOnNoDiscoveredTests` property and `src/test` was empty here at
// the time, so that lane reported a distribution certification while executing zero classes --
// the most expensive false green in the build, because it names a real ZIP and a real SBOM. The
// gate entries go with it: a `dependsOn(":pipeline-application:test")` on an empty source set
// makes `releaseGate` slower and no more honest.
// `src/test` is no longer empty. It holds the pure release decisions --
// `ApiRangeContainsTest`, `CandidateHandoffDigestAttributionTest`,
// `ProductVersionIdentityTest` -- which is exactly what the lane was not for: they decide from
// strings, with no ZIP and no SBOM. The clause above used to read "is empty here" in the
// present tense, and it stopped being true when the first of those landed. A comment that
// asserts a property of the build outlives the state it described unless someone edits it.
// Its replacement is the executable E2E layer: `scripts/verify-all.sh` stages the distribution
// once (`:pipeline-application:installDist`) and `v2/examples/run.sh` runs the real binary
// against the `.pipeline.kts` pipelines. An earlier version of this comment named a single
// script, `scripts/run-e2e-pipelines.sh`, which does not exist in this tree and never staged
// anything. That path executes the shipped bytes, so it certifies the artifact instead of
// asserting a tag over an empty classpath.
