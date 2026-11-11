import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

plugins {
    kotlin("jvm")
    // Matched to the Kotlin version (2.4.10), NOT the kotlinx-serialization
    // runtime version. The catalog alias `libs.plugins.kotlin.serialization`
    // binds to the runtime version and does not resolve as a Gradle plugin id.
    kotlin("plugin.serialization")
    // Selective mutation (ReplayPolicy core). Run explicitly::pipeline-release:pitest
    id("info.solidsoft.pitest")
}

// The seam of the mutation lane of this module.
//
// It covers the DECISIONS of the Maven channel and the DOCUMENTS they judge:
// the model that says what the repository served against what the manifest
// declares (the consumer's door), the model that says whether a request may
// publish the bundle (the publisher's door), and the manifest, the request and
// the artifact that both doors read. That set is the part whose rules can be
// wrong without anything crashing, so it is the part where a surviving mutant
// is a hole in a test.
//
// The publisher's door and its three documents were NOT in this seam until the
// channel was measured for the first time. The seam held eighteen mutants, all
// of them of the consumer's door, and all eighteen died: a green mutation lane
// that said nothing at all about the half of the channel that decides what may
// be published. A decision that nothing measures is a decision nobody has
// checked, and the number of dead mutants says only that the measured part was
// measured.
//
// The adapter over HTTP is NOT in this seam: its rules (the credential that
// only travels to the repository that asks for it, the redirect that is not
// followed, the address that has to be TLS) are effects, and they are
// measured by the integration lane, which talks to a real server over a real
// socket.
//
// The serializers are excluded because they are GENERATED: the code of a
// `$$serializer` is written by the plugin, not by this repository, and a
// mutant in it is a mutant in the plugin.
//
// FIVE mutants of this seam survive and are EQUIVALENT: they cannot be killed
// by any row, and they are recorded here rather than excluded from the seam,
// because a number that hides its survivors is a number that cannot be read.
//
//   - `certificationViolations` and `publishedViolations` each return
//     `emptyList()` on the path where nothing is wrong; the mutant returns
//     `java.util.Collections.emptyList()` instead. Two empty lists are the
//     same value to every caller: `equals` holds, the size holds, and the
//     type does not promise the identity of the instance.
//   - the same two functions cast a sealed verdict to the case they have
//     already excluded, behind an `Intrinsics.checkNotNull`. The verdict has
//     exactly two cases, the other one was excluded on the line above, and the
//     value is not null, so the check cannot fire and the cast cannot fail.
//   - one conditional of the diagnostic that names the kind of an artifact
//     whose path contradicts it. Its ONLY observable is the message, and the
//     message is pinned exactly, character by character, in the two
//     configurations that reach it: a path of no kind, and a path of a kind
//     the artifact does not declare. The mutant survives both pins, so it
//     changes no output that any caller can read.
//
// Any OTHER survivor of this seam is a hole in a row, and the row is what
// closes it.
pitest {
    junit5PluginVersion = "1.2.1"
    targetClasses = listOf(
        "dev.rubentxu.pipeline.v2.release.MavenResolutionValidation*",
        "dev.rubentxu.pipeline.v2.release.MavenPublicationValidation*",
        "dev.rubentxu.pipeline.v2.release.MavenPublicationManifest",
        "dev.rubentxu.pipeline.v2.release.MavenPublicationRequest",
        "dev.rubentxu.pipeline.v2.release.MavenArtifactDigest",
        "dev.rubentxu.pipeline.v2.release.MavenArtifactKind",
    )
    excludedClasses = listOf(
        "dev.rubentxu.pipeline.v2.release.*\$\$serializer",
    )
    targetTests = listOf(
        "dev.rubentxu.pipeline.v2.release.MavenResolutionValidationTest",
        "dev.rubentxu.pipeline.v2.release.MavenPublicationValidationTest",
        "dev.rubentxu.pipeline.v2.release.MavenPublicationCodecTest",
    )
    threads = System.getenv("PIT_THREADS")?.toInt() ?: 4
    timeoutConstInMillis = 10000
    outputFormats = listOf("XML", "HTML")
    mutators = listOf("DEFAULTS")
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
    // The durable ports of the product: `OperationJournal` and `RunLeasePort`, with the pure
    // decider that decides ownership and fencing. The state of the certification cycle is filed
    // in the product's own journal and guarded by the product's own lease, so this module reaches
    // the PORTS and not the stores: the SQLite journal and the file-backed lease live in
    // `:pipeline-events-store` and stay behind their ports.
    //
    // This module is not published and carries no ABI baseline, so the edge does not change any
    // published surface. The distribution already ships the JDBC driver through
    // `:pipeline-application`, so nothing new enters the candidate ZIP.
    implementation(project(":pipeline-runtime-api"))

    // The stores, for the tests only: `InMemoryOperationJournal` is a REAL implementation of the
    // journal port (not a mock of it), and it is what lets the checkpoint's guarantees be witnessed
    // without a database. The edge is a test edge; the production classpath of this module reaches
    // the ports and nothing else.
    testImplementation(project(":pipeline-events-store"))
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

// The contract of the reception, read by the lanes of this module.
//
// The contract is a document of the repository and NOT a file of this module:
// the reader compares it with what this build emits and refuses what does not
// agree, so a contract that changed and a contract that did not are two
// different contracts. The lanes read it WHEN THEY RUN, through a path the
// build hands over, and Gradle's up-to-date check only sees DECLARED inputs.
// A lane that does not declare the document it reads stays UP-TO-DATE after
// the document changes, and the report it leaves on disk is the report of a
// contract that is no longer the one in the tree — a green over a document
// nobody looked at. The subject of the rule is the document, so the document
// is an input of the task that reads it.
//
// The path travels with the declaration, so the file the lane watches is the
// file the lane reads: the two cannot drift apart, and a reader that composes
// its own path is a second derivation of a fact the build already knows.
//
// `withType<Test>()` reaches both lanes of this module: the unit one and the
// integration one. The integration lane reads the same contract and would
// otherwise keep a report of a contract that no longer exists.
val receptionContract = rootProject.file("contract/reception-contract.json")

tasks.withType<Test>().configureEach {
    inputs.file(receptionContract)
    systemProperty("pipeline.receptionContract", receptionContract.absolutePath)
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
    description = "cheap identity admission for the distribution ZIP."

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

    // The values the action below needs, taken HERE, while the build is
    // configured, so that the action carries the VALUES and not the script
    // that owns them. An action that reaches into the script it was declared
    // in cannot be stored in the configuration cache: the cache refuses to
    // serialize a reference to a build script, and the task then runs against
    // a script that is not there.
    //
    // `project` is read here for the same reason. It is not only the action
    // that reads it: the `map { }` below is evaluated when Gradle resolves the
    // output, which is at execution time, and a `project` read then is
    // unsupported ("invocation of 'Task.project' at execution time is
    // unsupported"). The version below is a plain String, and a String is
    // something the cache can store.
    val version = project.version.toString()
    val repoRoot = rootProject.projectDir.parentFile

    // The BOM the cyclonedx plugin produces. The path is taken HERE for the
    // same reason the version is: a `project` read while the task RUNS is
    // unsupported under the configuration cache, and a Provider is a value
    // the cache can store.
    val bomSource = project(":pipeline-application").layout.buildDirectory
        .file("reports/bom.json")

    // The marker the build's own resolver writes: its CONTENT is the commit
    // of the tree. It is written when the build RUNS, and it is read when the
    // admission RUNS, so a build that reuses a configuration cache entry still
    // records the commit of the tree it is admitting. It is also an INPUT, so
    // a commit that moved re-runs the admission instead of certifying the
    // previous one.
    val commitMarker = project(":pipeline-application").layout.buildDirectory
        .file("tmp/build-commit.txt")
    dependsOn(":pipeline-application:resolveBuildCommit")
    inputs.files(commitMarker)

    // Declared at configuration time: Gradle rejects an outputs mutation once
    // a task has started. Registering the directory here also gives the task a
    // real output, so it re-runs when the emitted material changes.
    outputs.dir(outDir)

    // The digest authority is a published asset, and it is written by the
    // admission itself, so this task has to declare it as an output of its
    // own: without that, a build that starts from a clean distributions
    // directory has nothing to fingerprint and the task would consider itself
    // up to date without ever having written the file the publisher resolves.
    // The name here is an up-to-date hint, not the contract: the contract is
    // that every published asset lives in the distribution's directory, and
    // the admission refuses a candidate that breaks it. The publisher
    // resolves this asset by the name the manifest gives it, so the name
    // itself is never written twice.
    outputs.file(distDir.map { it.file("SHA256SUMS") })

    // The assets the digest authority covers are inputs, not just arguments.
    // A command line carries a PATH, not the bytes behind it, so a task whose
    // only inputs were its arguments could consider itself up to date while
    // the assets it digested had changed underneath: the receipt would name
    // an identity that the bytes no longer have, and the digest authority
    // would vouch for assets that are not the ones published. Declaring them
    // as inputs is what makes the receipt and the digest authority describe
    // the bytes that are on disk.
    inputs.files(distDir.map { it.file("pipelinek-$version.zip") })
    inputs.files(distDir.map { it.file("pipelinek-$version.sbom.json") })
    inputs.files(distDir.map { it.file("pipelinek-$version.sdk.zip") })
    inputs.files(distDir.map { it.file("pipelinek-bom-$version.json") })

    inputs.property("productVersion", version)
    inputs.property("candidateSequence", candidateSequence)

    // Resolved lazily inside doFirst: the ZIP does not exist at configuration
    // time, and a configuration-time existence check would fail the build for
    // the wrong reason.
    doFirst {
        val dist = distDir.get().asFile
        val zip = dist.resolve("pipelinek-$version.zip")
        if (!zip.isFile) {
            throw GradleException(
                "expected distribution ZIP at $zip but it does not exist. " +
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
        val bomSourceFile = bomSource.get().asFile
        if (!bomSourceFile.isFile) {
            throw GradleException(
                "the cyclonedxBom is a declared dependency of " +
                    "this task but produced no $bomSourceFile. Refusing to admit a candidate whose " +
                    "SBOM is absent: the release policy lists " +
                    "pipelinek-$version.sbom.json as a required asset, and an asset set with a " +
                    "hole in it is not a smaller candidate, it is a different product.",
            )
        }
        bomSourceFile.copyTo(dist.resolve("pipelinek-$version.sbom.json"), overwrite = true)

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
                    "$producedBy is a declared dependency of this task but " +
                        "produced no $file. Refusing to admit a candidate without $asset; " +
                        "the release policy fixes the asset set " +
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

        // The commit the candidate's bytes are from, read from the marker the
        // build's own resolver wrote. Two things are avoided here, and both were
        // measured on this build.
        //
        // The first: reading git from this script. The commit would then come
        // from a SECOND implementation of a rule that already has one home, and
        // two implementations of the same fact are free to disagree about it.
        // The marker is written by the build's single resolver.
        //
        // The second: reading the commit while the build is CONFIGURED. With the
        // configuration cache enabled that read happens once, while the entry
        // is stored, and its value is frozen into that entry: a build that
        // reused the entry would record the commit of the tree as it was when
        // the entry was stored, which is not the tree the candidate was built
        // from.
        //
        // This value alone does NOT establish provenance. It is the commit of the
        // working tree, so a dirty tree would make it a false address. The
        // build's real guarantee is the clean-tree law in `SourceProvenance`,
        // enforced by this task; this value only supplies the address to be
        // verified. It is never an empty string: the manifest records
        // provenance, and an empty commit would be a claim about a build nobody
        // can trace.
        val commit = commitMarker.get().asFile
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            .orEmpty()
            .ifEmpty { "unknown" }

        args = listOf(
            zip.absolutePath,
            version,
            commit,
            outDir.get().asFile.absolutePath,
            candidateTag,
            candidateSequence,
            sbom.absolutePath,
            sdkZip.absolutePath,
            bomJson.absolutePath,
            // The repository whose version control state defines this
            // candidate's provenance. RootProject lives in the build root, whose parent
            // holds.git directory.
            repoRoot.absolutePath,
            covered.joinToString(";") { it.absolutePath },
        )
    }

    // Any non-zero exit means the candidate was refused. Propagate it verbatim:
    // the diagnostic printed by the model is the operator-facing message, and
    // wrapping or swallowing it would hide the reason.
    isIgnoreExitValue = false
}


/**
 * Emit the certification request event for a release candidate that has just
 * been published.
 *
 * The event asks the receiver to certify the bytes that were published, and
 * it is emitted at the publication and not at the build: a request about bytes
 * the receiver cannot download yet is worse than no request.
 *
 * The task is intentionally thin, like the admission above. It resolves the
 * paths, hands the arguments over and propagates a non-zero exit. It re-derives
 * no rule, so the build and the tested model cannot drift apart: the decision
 * to emit or to refuse lives in `certificationRequestedEmission`, and the
 * diagnostic the operator reads is the one that model produced.
 *
 * The publication receipt is an input, not an argument: it is the fact of the
 * publication, and a task that carried only its path could consider itself up
 * to date while the publication changed underneath it.
 *
 * The request it carries is the SAME document the admission gate evaluated
 * (`:pipeline-release:certificationAdmission`): the receiver is asked about the
 * bytes and the battery that were admitted, and there is no second derivation
 * of the request that could disagree with the first.
 *
 * The task does NOT take the place of the message. The reception contract
 * does: it declares the inbox the receiver observes and the name the file
 * carries, and the producer lands the message there. A task that took a path
 * would let the release path and the contract say two different things, and
 * only one of them would be the truth.
 */
tasks.register<JavaExec>("certificationRequested") {
    group = "distribution"
    description = "emit the rc.certification.requested event for a published release candidate."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.CertificationRequestedEventMainKt")

    val handoff = providers.gradleProperty("certification.handoff").orNull
    val request = providers.gradleProperty("certification.request").orNull
    val publicationReceipt = providers.gradleProperty("certification.publicationReceipt").orNull
    val publishedAt = providers.gradleProperty("certification.publishedAt").orNull
    val contract = providers.gradleProperty("certification.contract").orNull
    val receiptFile = providers.gradleProperty("certification.receiptFile").orNull

    // The documents the decision depends on are INPUTS: a command line carries
    // a path, not the bytes behind it.
    listOfNotNull(handoff, request, publicationReceipt).forEach { path ->
        inputs.file(project.file(path))
    }
    // The contract is an input too, and it is read from the ROOT project: the
    // contract of the repository is not a file of one module, and a task that
    // read it from somewhere else would read a copy.
    if (contract != null) inputs.file(rootProject.file(contract))
    inputs.property("publishedAt", publishedAt ?: "")
    inputs.property("contract", contract ?: "")
    inputs.property("receiptFile", receiptFile ?: "")

    // The arguments are fixed while the task is CONFIGURED, not while it runs.
    // A task whose arguments are set inside a `doFirst` cannot declare them as
    // its inputs, and an emission that cannot see its own arguments cannot say
    // what it emitted.
    args = listOf(
        handoff ?: "",
        request ?: "",
        publicationReceipt ?: "",
        publishedAt ?: "",
        contract ?: "",
        receiptFile ?: "",
    )

    doFirst {
        val missing = listOf(
            "certification.handoff" to handoff,
            "certification.request" to request,
            "certification.publicationReceipt" to publicationReceipt,
            "certification.publishedAt" to publishedAt,
            "certification.contract" to contract,
            "certification.receiptFile" to receiptFile,
        ).filter { (_, value) -> value.isNullOrBlank() }.map { (name, _) -> name }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "certificationRequested needs ${missing.joinToString(", ")}. The event is " +
                    "emitted from the publication, and an emission with no document to read and " +
                    "no contract to land the message in certifies nothing.",
            )
        }
    }

    // A refusal is a defect in the inputs or in the cycle, and the diagnostic
    // the model printed is the message the operator reads. Propagate it
    // verbatim rather than wrapping it.
    isIgnoreExitValue = false
}


/**
 * The second ABI reading: the candidate's PUBLISHED surface against the
 * surface the PREVIOUS release published.
 *
 * `apiCheck` compares the tree against its own committed baseline
 * (`<module>/api/<module>.api`). That baseline is a file in this repository,
 * so a rewritten baseline makes `apiCheck` green while the published surface
 * breaks: the gate would be certifying the rewrite instead of the artifact.
 *
 * This task reads two PUBLISHED artifacts instead — the candidate this release
 * publishes and the artifact the previous release published. Neither side is a
 * file in this tree, so no rewrite of a file here can satisfy it.
 *
 * The geometry — which repositories, which versions, which modules — is NOT
 * recomputed here. It is read from the root build script through
 * `rootProject.extra`, because the root build script is what names the sides
 * and owns the module list; computing it in a second place would be a second
 * answer to the same question, and a comparison whose two sides came from
 * different answers would compare artifacts that were never named together.
 *
 * The decision itself lives in `PublishedApiCompatibility` (pure) and is
 * applied by `PublishedApiCompatibilityMain` (the interpreter at the
 * boundary). This task only resolves the paths, hands the arguments over and
 * propagates the exit code; it re-derives no rule.
 */
tasks.register<JavaExec>("publishedApiCompatibility") {
    group = "verification"
    description = "compare the candidate's published API surface against the surface " +
        "the previous release published."

    val dumpDirs: Map<String, String> = (rootProject.extra["publishedApiDumpDirs"] as Map<*, *>)
        .map { (side, dir) -> side.toString() to dir.toString() }
        .toMap()
    val versions: Map<String, String?> = (rootProject.extra["publishedApiVersions"] as Map<*, *>)
        .map { (side, version) -> side.toString() to version?.toString() }
        .toMap()
    val modules: List<String> = (rootProject.extra["abiComparedModules"] as List<*>)
        .map { it.toString() }
    val ledger = rootProject.file("contract/published-contract-exceptions.json")

    // Where the interpreter's own exit code is written. The caller owns the
    // path, because the caller is the one that has to tell "the comparison
    // found an unauthorized break" from "the comparison could not be made":
    // Gradle collapses every failure into exit code 1, and those two are not
    // the same fact. The difference decides whether the operator is looking at
    // a defect in the candidate or at a broken instrument.
    val verdictFile: File? = project.providers.gradleProperty("publishedApiVerdictFile")
        .orNull
        ?.let { project.file(it) }

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.PublishedApiCompatibilityMainKt")

    // The dumps are produced by the root build script's own tasks, one per
    // side and per published module. They are named by path and not by
    // reference, because this project is evaluated before the modules that
    // publish later in the alphabetical order: a task reference resolved here
    // would be a reference to a task that does not exist yet.
    dependsOn(
        modules.flatMap { module ->
            listOf(":$module:candidateArtifactApiDump", ":$module:previousArtifactApiDump")
        },
    )

    // The ledger is an input, not an argument: the decision depends on its
    // bytes, and a task that carried only the path could consider itself up to
    // date while the approvals changed underneath it.
    inputs.file(ledger)
    inputs.property("candidateVersion", versions["candidate"] ?: "")
    inputs.property("previousVersion", versions["previous"] ?: "")

    // The arguments are fixed while the task is CONFIGURED, not while it
    // runs. A task whose arguments are set inside a `doFirst` cannot declare
    // them as its inputs, and a verification that cannot see its own
    // arguments cannot say what it verified.
    args = listOf(
        dumpDirs.getValue("candidate"),
        dumpDirs.getValue("previous"),
        versions["candidate"] ?: "",
        versions["previous"] ?: "",
        ledger.absolutePath,
    ) + modules

    doFirst {
        val candidateVersion = versions["candidate"]
        val previousVersion = versions["previous"]
        if (candidateVersion.isNullOrBlank() || previousVersion.isNullOrBlank()) {
            throw GradleException(
                "publishedApiCompatibility needs both sides of the comparison named: " +
                    "-PcandidateArtifactRepo/-PcandidateArtifactVersion and " +
                    "-PpreviousArtifactRepo/-PpreviousArtifactVersion. Without them there is " +
                    "nothing to compare, and a comparison that did not happen certifies nothing.",
            )
        }
        // A verdict left behind by an earlier run is not this run's verdict.
        // It is removed BEFORE the comparison starts, so a run that never
        // reaches the interpreter leaves no verdict behind for a reader to
        // mistake for one.
        verdictFile?.delete()
    }

    // The interpreter's exit code is the decision, and it carries two facts
    // that are not the same: 1 means "there is an unauthorized break", 2 means
    // "the two surfaces could not be compared". Gradle turns every non-zero
    // exit into the same build failure, which would collapse a defect in the
    // candidate into an indistinguishable broken instrument. So the exit code
    // is written where the caller can read it, and the build failure is
    // raised afterwards.
    isIgnoreExitValue = true
    doLast {
        val exitValue = executionResult.get().exitValue
        if (verdictFile != null) {
            verdictFile.parentFile?.mkdirs()
            verdictFile.writeText("$exitValue\n")
        }
        if (exitValue != 0) {
            throw GradleException(
                "the published API comparison exited with $exitValue: 1 means an unauthorized " +
                    "break against the previously published artifact, 2 means the two surfaces " +
                    "could not be compared.",
            )
        }
    }
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
 * Source: `<pipelinek>/build/sdk-repo/`, the local Maven repository populated
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
    description = "build pipelinek-<v>.sdk.zip and pipelinek-bom-<v>.json " +
        "from build/sdk-repo/ (the local Maven repo populated by " +
        ":publishSdkForExternalPlugin)."

    // The SDKs are published to <pipelinek>/build/sdk-repo/ by this task; the
    // BOM POM is published by the same task. Without this dependency
    // the repo is empty and the builder refuses with "no SDK entries
    // found".
    dependsOn(":publishSdkForExternalPlugin")

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.SdkZipMainKt")

    val distDir = project(":pipeline-application").layout.buildDirectory.dir("distributions")
    val sdkRepo = rootProject.layout.buildDirectory.dir("sdk-repo")

    // The version is taken HERE, while the build is configured, so that the
    // action below carries the VALUE and not the script that owns it. An
    // action that reaches into the script it was declared in cannot be stored
    // in the configuration cache, and `project` read at execution time is
    // unsupported: the version would arrive as "unspecified", and the builder
    // refuses to package a release of no version.
    val version = project.version.toString()

    // Declared at configuration time so the task is a real producer in
    // the dependency graph; `candidateAdmission` reads the artifacts
    // from `distDir` after this task has written them.
    //
    // The task owns the two files it writes, not the whole directory. The
    // directory is a SHARED staging area — the distribution ZIP, the SBOM and
    // the digest authority live there too — and a task that declares a
    // directory it does not own collides with the tasks that own the other
    // members of it: Gradle sees two producers of one output and refuses the
    // build for a reason nobody can read.
    outputs.files(
        distDir.map { it.file("pipelinek-$version.sdk.zip") },
        distDir.map { it.file("pipelinek-bom-$version.json") },
    )
    inputs.property("productVersion", version)
    // The SDK repository this task packages is its INPUT, and it was not
    // declared as one. The consequence was measured, not predicted: a change to
    // a published contract rewrote the jar in `build/sdk-repo/` and left the
    // ZIP untouched, because the only declared input was the version, which
    // had not moved. The task reported UP-TO-DATE while carrying the PREVIOUS
    // contract's bytes, and every external consumer -- which resolves from the
    // unpacked ZIP, not from the repo -- compiled against the old artifact.
    // The build was green and the published SDK was wrong.
    //
    // A directory is declared rather than the individual jars because the
    // builder's selection rule is "every file whose FOLDER is named
    // `<version>/`" (see SdkZipBuilder.selectVersionedEntries): naming a file
    // here would reproduce the same class of defect one level down, where the
    // ZIP tracks a list of files and the list is not what it packages.
    inputs.dir(sdkRepo)

    // The list of `group:artifact` pairs the BOM pins. It is READ from the root
    // build's single declaration of the published coordinate, and not written
    // here: a second list of the same five coordinates is a second authority,
    // and the two drift the first time only one of them is edited. The five
    // are the `publishedContractModules` of the root build (the SDK
    // contracts). The `:pipeline-sdk-bom` POM is in the SDK ZIP
    // implicitly; the JSON manifest only needs the contract list.
    val publishedGroup = rootProject.extra["sdkBundleGroup"] as String
    val publishedArtifactNames = rootProject.extra["sdkBundleArtifactNames"] as Map<*, *>
    val managedCoordinates = listOf(
        "pipeline-domain",
        "pipeline-scripting-api",
        "pipeline-events",
        "pipeline-output",
        "pipeline-runtime-api",
        "pipeline-service-client",
    ).map { module -> "$publishedGroup:${publishedArtifactNames[module]}" }
    // The coordinate the BOM ITSELF is published under. It is read from the same
    // declaration, because the BOM JSON declares this coordinate: a BOM that names
    // a coordinate other than the one it is published under sends a consumer to
    // artifacts that do not exist. It is not one of the managed coordinates (those
    // are the five contracts the BOM PINS), so it travels as its own argument.
    val bomCoordinate = "$publishedGroup:${publishedArtifactNames["pipeline-sdk-bom"]}"

    // The coordinates are declared as INPUTS, and the reason is the same one
    // that made the SDK repository an input: a task whose output depends on a
    // fact it does not declare is a task that can report UP-TO-DATE while
    // carrying the PREVIOUS answer. The published group and the published
    // artifact names are read from the root build's declaration, and a change
    // there (the rename of the coordinates IS such a change) rewrites what the
    // BOM pins and what the BOM declares itself to be -- without moving the
    // version and without touching the SDK repository, so the declared inputs
    // would not have moved either. The ZIP would have kept the old coordinates
    // and the build would have been green.
    inputs.property("managedCoordinates", managedCoordinates)
    inputs.property("bomCoordinate", bomCoordinate)

    doFirst {
        val sdkRepoDir = sdkRepo.get().asFile
        if (!sdkRepoDir.isDirectory) {
            throw GradleException(
                "sdkZip: expected SDK repository at $sdkRepoDir but the directory " +
                    "does not exist. Did :publishSdkForExternalPlugin run?",
            )
        }
        args = listOf(
            sdkRepoDir.absolutePath,
            version,
            distDir.get().asFile.absolutePath,
            bomCoordinate,
        ) + managedCoordinates
    }

    isIgnoreExitValue = false
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
// The gate is meant to be reachable without a clean tree, so a developer can
// run it locally. The `releaseGate` task
// is wired as a sibling of the standard lifecycle: it does NOT depend on
// `check` (which is test-time, takes minutes, and is orthogonal to the
// candidate's own identity) but it is a single Gradle invocation, so a
// developer can `gradle :pipeline-release:releaseGate` and see the verdict
// without producing a Prerelease.
// ----------------------------------------------------------------------------

tasks.register("releaseGate") {
    group = "distribution"
    description = "run the candidate gate end-to-end: " +
        "run the candidate gate end-to-end. Dist ZIP + cyclonedxBom + candidateAdmission + " +
        "LaunchReceipt contract + SDK BOM alignment. Non-zero on any refusal."
    // The description used to enumerate four certifications that no longer run here: the
    // renderer partition test, the application regression suite, and the mandatory-plugin test
    // (7 plugins) whose failure-on-unset-`PIPELINEK_DIST_ZIP` was the gate's hard-red path for a
    // missing distribution. All three read a JUnit source set that is empty. Naming them would
    // have been the exact false compliance this removal exists to end: a task description is read
    // as a claim about what ran. The distribution is now exercised by `scripts/verify-all.sh`,
    // which builds it and runs the gate against it.

    dependsOn(
        ":pipeline-application:distZip",
        ":pipeline-application:cyclonedxBom",
        "candidateAdmission",
        // The external example plugin JARs must be rebuilt before a candidate is
        // admitted, so the gate is not run against stale bytes: a candidate is only as honest
        // as the plugin JARs it ships. These are paid for ONCE per release gate run, in ONE
        // build that produces the four JARs concurrently (see `:buildExamplePlugins`).
        // This list used to name three of the four builds; the fourth (the Saga
        // consumer) was already shipped by the distribution, so requiring the whole
        // set now asks for nothing the gate did not already package.
        ":buildExamplePlugins",
    )

    // The path is taken HERE, while the build is configured, for the same
    // reason the other tasks in this file take their values there: an action
    // that reaches into the script it was declared in cannot be stored in the
    // configuration cache, and `project` read at execution time is unsupported.
    val version = project.version.toString()
    val distZipFile = project(":pipeline-application").layout.buildDirectory
        .file("distributions/pipelinek-$version.zip")

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
        val distZip = distZipFile.get().asFile
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
// Its replacement is the gate: `scripts/verify-all.sh` stages the distribution once
// (`:pipeline-application:installDist`) and then runs the unit tests, the integration tests and
// the mutants against it. That path executes the shipped bytes, so it certifies the artifact
// instead of asserting a tag over an empty classpath.

// ----------------------------------------------------------------------------
// The certification request and the certification admission.
//
// The request says WHAT is certified and AGAINST WHICH battery; the admission
// says whether the receipt that came back admits the candidate that was
// asked about. Both are pure decisions in this module -- the request is built
// from a candidate handoff and a published catalogue, and the admission is
// derived from a request and a receipt -- so these two tasks only resolve
// paths, hand the arguments over and propagate the exit code.
//
// The admission task's exit code IS the gate: a non-zero exit means the
// candidate is not certified, and the publisher that asks for it fails on the
// spot with the model's own diagnostic.
//
// The properties are read at configuration time and stored as inputs, so a
// run over different documents re-runs the task instead of reusing a verdict
// that was reached over other bytes.
//
// `certification.correction` is the report of the defect this candidate
// answers, and it is OPTIONAL: a candidate that is not a correction states a
// request that says nothing about any defect. When it is there, the request
// carries the defect and the candidate the defect was seen against, so the
// receiver knows which scenario it has to revalidate first.
tasks.register<JavaExec>("certificationRequest") {
    group = "distribution"
    description = "state the certification request for the candidate and the published battery."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.CertificationRequestMainKt")

    val handoff = providers.gradleProperty("certification.handoff").orElse("").get()
    val catalogue = providers.gradleProperty("certification.catalogue").orElse("").get()
    val out = providers.gradleProperty("certification.out").orElse("").get()
    val correction = providers.gradleProperty("certification.correction").orElse("").get()

    inputs.property("certification.handoff", handoff)
    inputs.property("certification.catalogue", catalogue)
    inputs.property("certification.correction", correction)
    if (handoff.isNotBlank()) inputs.files(handoff)
    if (catalogue.isNotBlank()) inputs.files(catalogue)
    if (correction.isNotBlank()) inputs.files(correction)

    doFirst {
        listOf(
            "certification.handoff" to handoff,
            "certification.catalogue" to catalogue,
            "certification.out" to out,
        ).forEach { (name, value) ->
            if (value.isBlank()) {
                throw GradleException(
                    "certificationRequest: -P$name is required. A request names a candidate and " +
                        "the battery it is asked against, and it cannot be stated without both.",
                )
            }
        }
    }

    args = listOf(handoff, catalogue, out, correction)

    // Any non-zero exit means the request could not be stated. Propagate it
    // verbatim: the diagnostic printed by the model is the operator-facing
    // message.
    isIgnoreExitValue = false
}

/**
 * Compose the receipt of the certification cycle.
 *
 * The receipt binds the documents of a cycle — the candidate, the request, the
 * certification receipt, the defects and the promotion — into ONE document
 * that says which artifact they are all about, and it is NOT written when they
 * disagree about it.
 *
 * The extra documents are a `;`-separated list because their number is not
 * fixed: a cycle may find no defect and one promotion, or three defects and
 * no promotion. Each one is classified by what it says, so the list does not
 * have to be ordered.
 *
 * Like the tasks above, it resolves the paths and propagates the exit code.
 * It re-derives no rule: the composition lives in the model, and the tests pin
 * the model.
 */
tasks.register<JavaExec>("cycleReceipt") {
    group = "distribution"
    description = "compose the receipt of the certification cycle."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.CycleReceiptMainKt")

    val candidate = providers.gradleProperty("cycle.candidate").orElse("").get()
    val request = providers.gradleProperty("cycle.request").orElse("").get()
    val certification = providers.gradleProperty("cycle.certification").orElse("").get()
    val out = providers.gradleProperty("cycle.out").orElse("").get()
    val extras = providers.gradleProperty("cycle.extras").orElse("").get()

    inputs.property("cycle.candidate", candidate)
    inputs.property("cycle.request", request)
    inputs.property("cycle.certification", certification)
    inputs.property("cycle.extras", extras)
    if (candidate.isNotBlank()) inputs.files(candidate)
    if (request.isNotBlank()) inputs.files(request)
    if (certification.isNotBlank()) inputs.files(certification)
    if (extras.isNotBlank()) inputs.files(extras.split(";").filter { it.isNotBlank() })

    doFirst {
        listOf(
            "cycle.candidate" to candidate,
            "cycle.request" to request,
            "cycle.certification" to certification,
            "cycle.out" to out,
        ).forEach { (name, value) ->
            if (value.isBlank()) {
                throw GradleException(
                    "cycleReceipt: -P$name is required. The receipt of a cycle names the " +
                        "candidate, the request and the certification it binds, and it cannot be " +
                        "composed without them.",
                )
            }
        }
    }

    args = listOf(candidate, request, certification, out) +
        extras.split(";").filter { it.isNotBlank() }

    // A non-zero exit means the documents disagree, and the receipt was NOT
    // written. The publisher MUST NOT continue on the strength of a receipt
    // that does not exist.
    isIgnoreExitValue = false
}

tasks.register<JavaExec>("certificationAdmission") {
    group = "distribution"
    description = "decide whether a certification receipt admits the candidate."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.CertificationAdmissionMainKt")

    val request = providers.gradleProperty("certification.request").orElse("").get()
    val receipt = providers.gradleProperty("certification.receipt").orElse("").get()

    inputs.property("certification.request", request)
    inputs.property("certification.receipt", receipt)
    if (request.isNotBlank()) inputs.files(request)
    if (receipt.isNotBlank()) inputs.files(receipt)

    doFirst {
        listOf(
            "certification.request" to request,
            "certification.receipt" to receipt,
        ).forEach { (name, value) ->
            if (value.isBlank()) {
                throw GradleException(
                    "certificationAdmission: -P$name is required. A candidate is published only " +
                        "with a receipt that admits it, and a receipt is judged against the " +
                        "request that asked for it.",
                )
            }
        }
    }

    args = listOf(request, receipt)

    // Any non-zero exit means the receipt does not admit the candidate, and
    // the publisher MUST NOT continue.
    isIgnoreExitValue = false
}

tasks.register<JavaExec>("promotionCertification") {
    group = "distribution"
    description = "decide whether a promotion certification admits the candidate."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.PromotionCertificationMainKt")

    val certification = providers.gradleProperty("promotion.certification").orElse("").get()
    val candidate = providers.gradleProperty("promotion.candidate").orElse("").get()
    val verdict = providers.gradleProperty("promotion.verdict").orElse("").get()

    inputs.property("promotion.certification", certification)
    inputs.property("promotion.candidate", candidate)
    if (certification.isNotBlank()) inputs.files(certification)

    doFirst {
        listOf(
            "promotion.certification" to certification,
            "promotion.candidate" to candidate,
        ).forEach { (name, value) ->
            if (value.isBlank()) {
                throw GradleException(
                    "promotionCertification: -P$name is required. A stable release is promoted " +
                        "only against the complete certification of the candidate it carries, " +
                        "and the certification is judged against the candidate being promoted.",
                )
            }
        }
    }

    args = listOf(certification, candidate, verdict)

    // Any non-zero exit means the certification does not admit the candidate,
    // and the promoter MUST NOT continue.
    isIgnoreExitValue = false
}

/**
 * The door of the Maven publication channel.
 *
 * It answers ONE question: "may this request publish this bundle?". The
 * answer is the model's, and this task only resolves the paths, hands the
 * arguments over and propagates the exit code.
 *
 * The documents the decision depends on are INPUTS, and they are OPTIONAL
 * inputs: the model refuses a document it cannot read, and it refuses by
 * name. A build that failed before the model ran would report a missing
 * file where the operator needs the model's own reason, and the reason is
 * the thing that names which of the seven rules the request broke.
 *
 * The directories are NOT declared as inputs. The bundle is measured by the
 * model, and the state of the target repository is a fact about the world
 * that a task that considered itself up to date would answer from a cache.
 */
tasks.register<JavaExec>("mavenPublication") {
    group = "distribution"
    description = "decide whether a request may publish the Maven bundle."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.MavenPublicationMainKt")

    val request = providers.gradleProperty("maven.request").orElse("").get()
    val manifest = providers.gradleProperty("maven.manifest").orElse("").get()
    val certification = providers.gradleProperty("maven.certification").orElse("").get()
    val bundle = providers.gradleProperty("maven.bundle").orElse("").get()
    val sdkZip = providers.gradleProperty("maven.sdkZip").orElse("").get()
    val published = providers.gradleProperty("maven.published").orElse("").get()
    val verdict = providers.gradleProperty("maven.verdict").orElse("").get()

    listOf(request, manifest).filter { it.isNotBlank() }.forEach { path ->
        inputs.file(project.file(path))
    }
    inputs.property("maven.request", request)
    inputs.property("maven.manifest", manifest)
    inputs.property("maven.certification", certification)
    inputs.property("maven.bundle", bundle)
    inputs.property("maven.sdkZip", sdkZip)
    inputs.property("maven.published", published)
    inputs.property("maven.verdict", verdict)

    // The decision is NEVER up to date, and that is not a workaround. What it
    // decides depends on the state of the target repository, and that state
    // is not one of its inputs: a task that considered itself up to date
    // would answer "the version is not published" from a cache instead of
    // from the repository, and the idempotence of the decision would be the
    // idempotence of the cache and not the rule. The certification is
    // declared as a PATH and not as a file for the same reason: a request
    // made without one is a request the model has to refuse BY NAME, and a
    // build that failed before the model ran would report a missing file
    // where the operator needs the model's own reason.
    outputs.upToDateWhen { false }

    doFirst {
        val missing = listOf(
            "maven.request" to request,
            "maven.manifest" to manifest,
            "maven.bundle" to bundle,
            "maven.sdkZip" to sdkZip,
            "maven.published" to published,
        ).filter { (_, value) -> value.isBlank() }.map { (name, _) -> name }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "mavenPublication: ${missing.joinToString(", ")} must be named. A publication " +
                    "is judged against a request, a manifest, the bytes to publish, the SDK ZIP " +
                    "of the candidate and the state of the target repository, and it cannot be " +
                    "judged without them.",
            )
        }
    }

    args = listOf(request, manifest, certification, bundle, sdkZip, published, verdict)

    // Any non-zero exit means the request may NOT publish, and the publisher
    // MUST NOT continue.
    isIgnoreExitValue = false
}

/**
 * The consumer-side door of the Maven channel.
 *
 * It answers ONE question: "does the repository a consumer resolves from
 * serve the bytes the publication manifest declares?". The answer is the
 * model's, and this task only resolves the paths, hands the arguments over
 * and propagates the exit code.
 *
 * The repository is named by its URL and is NOT an input: the state of a
 * remote repository is a fact about the world, and a task that considered
 * itself up to date would answer that fact from a cache.
 */
tasks.register<JavaExec>("mavenResolution") {
    group = "distribution"
    description = "verify that a Maven repository serves the bytes the manifest declares."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.MavenResolutionMainKt")

    val manifest = providers.gradleProperty("maven.manifest").orElse("").get()
    val repository = providers.gradleProperty("maven.repository").orElse("").get()
    val verdict = providers.gradleProperty("maven.verdict").orElse("").get()
    // The exit code is where the caller reads the decision, and the caller
    // may name where it is. When it names nothing, the task writes it where
    // its own build writes, so the task can be run by hand.
    val exitFile = providers.gradleProperty("maven.exitFile")
        .orElse(layout.buildDirectory.file("maven-resolution/exit-code").get().asFile.absolutePath)
        .get()

    listOf(manifest).filter { it.isNotBlank() }.forEach { path ->
        inputs.file(project.file(path))
    }
    inputs.property("maven.manifest", manifest)
    // The repository is NOT declared as an input. An input is written into
    // the build's own state, and an address is the one string of this task
    // that could carry a credential; the task never considers itself up to
    // date, so the input would buy nothing anyway.
    inputs.property("maven.verdict", verdict)
    inputs.property("maven.exitFile", exitFile)

    // The decision is NEVER up to date, and that is not a workaround. What it
    // decides depends on the state of a remote repository, and that state is
    // not one of its inputs: a task that considered itself up to date would
    // answer "the repository serves the version" from a cache instead of from
    // the repository.
    outputs.upToDateWhen { false }

    doFirst {
        val missing = listOf(
            "maven.manifest" to manifest,
            "maven.repository" to repository,
        ).filter { (_, value) -> value.isBlank() }.map { (name, _) -> name }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "mavenResolution: ${missing.joinToString(", ")} must be named. A resolution is " +
                    "judged against a manifest and against a repository, and it cannot be " +
                    "judged without them.",
            )
        }
        // A verdict left behind by an earlier run is not this run's verdict.
        // It is removed BEFORE the interpreter starts, so a run that never
        // reaches it leaves no verdict behind for a reader to mistake for
        // one. The same is true of the decision file.
        if (verdict.isNotBlank()) {
            project.file(verdict).delete()
        }
        project.file(exitFile).delete()
    }

    args = listOf(manifest, repository, verdict, exitFile)

    // The DECISION of the interpreter is the decision, and the BUILD does not
    // carry it: Gradle turns every non-zero exit into the same build failure,
    // and the two refusals of this check are not the same fact. The decision
    // is written where the caller can read it, and the build failure is
    // raised afterwards.
    isIgnoreExitValue = true
    doLast {
        // What the interpreter WROTE is the decision. The exit code of the
        // process is not: a JVM that cannot start its own main class also
        // exits with 1, and that 1 is not a decision about the repository.
        val decided = project.file(exitFile)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+")) }
        if (decided == null) {
            throw GradleException(
                "mavenResolution did not conclude: the interpreter wrote no decision, so the " +
                    "state of the repository was not measured. A check that could not conclude " +
                    "is not a check that found the channel broken.",
            )
        }
        if (decided != "0") {
            throw GradleException(
                "mavenResolution decided $decided: 1 means the repository does not serve the " +
                    "bytes the manifest declares, and 2 means the state could not be measured.",
            )
        }
    }
}

/**
 * The producer of the Maven publication manifest.
 *
 * It reads the candidate's handoff and the bundle that was staged for it,
 * and writes the two documents of the channel: the manifest, which says
 * what is published, and the request, which asks for that manifest to be
 * published.
 *
 * It is a producer and not a step of the gate: nothing here decides whether
 * the bundle may be published. That decision is the door's, and the door
 * reads the documents this task writes.
 */
tasks.register<JavaExec>("mavenPublicationManifest") {
    group = "distribution"
    description = "write the Maven publication manifest and the request that publishes it."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.MavenPublicationManifestMainKt")

    val handoff = providers.gradleProperty("maven.handoff").orElse("").get()
    val bundle = providers.gradleProperty("maven.bundle").orElse("").get()
    val out = providers.gradleProperty("maven.out").orElse("").get()

    listOf(handoff).filter { it.isNotBlank() }.forEach { path ->
        inputs.file(project.file(path))
    }
    if (bundle.isNotBlank()) inputs.dir(project.file(bundle))
    inputs.property("maven.handoff", handoff)
    inputs.property("maven.bundle", bundle)
    inputs.property("maven.out", out)

    // The task declares no output, so it is never up to date and it always
    // measures. A producer that could consider itself up to date would write
    // a manifest of the bytes of an earlier run, and a manifest of bytes
    // nobody measured is a promise that nobody keeps.
    outputs.upToDateWhen { false }

    doFirst {
        val missing = listOf(
            "maven.handoff" to handoff,
            "maven.bundle" to bundle,
            "maven.out" to out,
        ).filter { (_, value) -> value.isBlank() }.map { (name, _) -> name }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "mavenPublicationManifest: ${missing.joinToString(", ")} must be named. A " +
                    "manifest is measured from the handoff of a candidate and from the bytes " +
                    "that were staged for it, and it cannot be stated without both.",
            )
        }
    }

    args = listOf(handoff, bundle, out)

    // Any non-zero exit means the manifest was NOT written, and the publisher
    // MUST NOT treat it as written.
    isIgnoreExitValue = false
}

/**
 * Receive a `defect.reported` document and record the defect in the task board of this
 * repository.
 *
 * The task resolves the paths and hands the arguments over; it re-derives no
 * rule, so the build and the tested model cannot drift apart. The candidate
 * in course is a property and not something the task reads from the tree: a
 * reception that guessed which candidate is in course would mix the defects
 * of one cycle with the defects of another.
 */
tasks.register<JavaExec>("defectReception") {
    group = "distribution"
    description = "receive a defect.reported document and record it in the task board of this repository."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.DefectReceptionMainKt")

    val eventFile = providers.gradleProperty("defect.eventFile").orElse("").get()
    val currentCandidate = providers.gradleProperty("defect.currentCandidate").orElse("").get()
    val board = providers.gradleProperty("defect.board").orElse("").get()
    val milestone = providers.gradleProperty("defect.milestone").orElse("").get()

    inputs.property("defect.eventFile", eventFile)
    inputs.property("defect.currentCandidate", currentCandidate)
    inputs.property("defect.board", board)
    inputs.property("defect.milestone", milestone)
    if (eventFile.isNotBlank()) inputs.files(eventFile)

    doFirst {
        listOf(
            "defect.eventFile" to eventFile,
            "defect.currentCandidate" to currentCandidate,
        ).forEach { (name, value) ->
            if (value.isBlank()) {
                throw GradleException(
                    "defectReception: -P$name is required. A defect is recorded against the " +
                        "candidate it was observed against, and a reception with no document to " +
                        "read and no candidate in course records nothing.",
                )
            }
        }
    }

    // The board and the milestone are optional: the board defaults to the
    // directory the task runs in, and a defect can belong to no milestone.
    args = listOf(eventFile, currentCandidate, board, milestone)

    // The reception is NEVER up to date, and that is not a workaround: what it
    // decides depends on the state of the board, and the board is not one of
    // its inputs. A build that considered the reception up to date would answer
    // "the defect is already recorded" from a cache instead of from the board,
    // and the idempotence of the reception would be the idempotence of the
    // cache and not the rule.
    outputs.upToDateWhen { false }

    // Any non-zero exit means the defect was NOT recorded, and the caller
    // MUST NOT treat it as recorded.
    isIgnoreExitValue = false
}
