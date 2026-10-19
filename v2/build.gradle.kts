plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.detekt) apply false
    // C5 / D-011 (audit 2026-09-26): binary-compatibility-validator declared at
    // root and applied per-module via subprojects { ... } below. See comments in
    // the application block for the phased rollout scope.
    alias(libs.plugins.binary.compatibility.validator) apply false
}

group = "dev.rubentxu.pipeline.v2"
// Root-level repositories. This block existed for the Kover merge configuration
// (kover(project(...)) resolves external deps in the ROOT project, which
// `dependencyResolutionManagement` does not cover). Kover was removed 2026-10-18;
// the block is kept because `buildSrc` and the root plugin classpath resolve
// through it, and removing it would be a change without a measured reason.
repositories {
    mavenCentral()
}
// WU-RP-034 closes here. The version is DERIVED from the commit history since the
// previous tag, never chosen by hand:
//
//   git tag --sort=-creatordate  ->  v0.44.1 (0f45a201)
//   v0.44.1..HEAD carries seven breaking commits:
//     feat(cli)!            3cd0d0d2  local-first workspace default
//     fix(steps)!           19577730  core.pwd reports the cwd
//     refactor(workspace)!  b797ca27  the capability value IS the ExecutionLocation ADT
//     refactor(steps)!      4d615fff  scm checkout and junit results anchored to the location
//     fix(workspace)!       9ef19694  ambient-state escape hatch removed
//     fix(workspace)!       9d2e999a  ownership crosses the boundary; a user root is not destructible
//     fix(corpus)!          459f431c  each fixture states the workspace mode it requires
//
// Breaking under 0.x convention bumps the MINOR component: 0.44.1 -> 0.45.0.
//
// This also repairs pre-existing drift: the declared version said 0.44.0 while
// the previous tag was v0.44.1, so the single-version provider had been stale.
//
// WU-RP-035 release train, derived from history since v0.45.0 (a277d67a):
//
//     feat(domain)          83d5b2b0  HANDLER_CONTINUATION + body-bound continuation
//     feat(engine)          5a2c8a7d  handler-driven route over the durable spine
//     feat(examples)        03e77df2  example.repeat external body plugin
//     fix(engine)           342897f0  canonical eligibility admits handler-driven blocks
//     docs(uat)             25c670f7  RP3_EXIT_REVIEW corrective addendum
//     docs(uat)             d2f40691  RP035-E receipt
//
// No breaking commits; pipeline-domain.api is purely additive (+21/-0), so the 0.x
// convention keeps the MINOR bump: 0.45.0 -> 0.46.0.
//
// S4 train, derived from history since v0.46.0 (63ef3220):
//
//     docs(adr)             de1c812c  ADR-S4-F2 ACCEPTED — StageBody.Scripted de primera clase
//     feat(domain)          0c04b543  StageBody.Scripted y ScriptedStageRef (base de F2)
//     refactor(durable)     fa72c901  una sola regla de finalizacion de stage (B0)
//     Merge:                9deaa9f9  absorbe el M1 Output Plane (8 commits, SHAs preservados)
//     fix(s4-m1)            294aea19  una sola autoridad de salida de proceso (B1)
//     test(output)          63dda75f  el contrato medido del plano para un paso sin salida (B2a)
//     feat(output)          051fb610  autoridad de retencion propia del Output Plane (B2b)
//     fix(output)           4f82fd3a  soltar una reserva nunca falla ni inventa bytes (B2c)
//     fix(test)             (este)    el corpus release-scale vuelve a mirar donde estan los bytes
//
// `v0.46.0` remains a certified, immutable tag over the bytes at 63ef3220 and is NOT reused.
// New public surface arrives rather than being removed: a new `pipeline-output` module
// (OutputStore, the four ports, OutputCursor), `StageBody.Scripted`, and the durable/process
// transcript separation. Nothing pre-existing was taken away, so the 0.x convention takes the
// MINOR component: 0.46.0 -> 0.47.0. MINOR rather than PATCH because the added module and the
// added `StageBody` case both change what a consumer can build against, and because
// `EchoOutputCaptured` stops carrying process output — which is a semantic change even though the
// type still exists.
//
// TRAIN P3 identity law below: the product version is final from the moment the candidate is
// built, and the tag is applied afterwards to exactly the certified bytes. So this line opens the
// 0.48.0 train WITHOUT creating a tag and WITHOUT claiming certification.
//
// ROADMAP A (2026-10-09): MINOR, following the same rule the 0.46.0 -> 0.47.0 note above states.
// `:pipeline-sdk-bom` is a new published module, which changes what a consumer can build
// against; `:pipeline-step-sdk:processor` is retired in the same window. Nothing pre-existing
// was removed, so the 0.x convention takes the MINOR component: 0.47.0 -> 0.48.0.
//
// This line carries NO candidate suffix and MUST NOT grow one. `ProductVersion` rejects a
// candidate suffix by construction (`^(\d+)\.(\d+)\.(\d+)$`) because the v0.43.0 incident:
// an artifact internally consistent at `0.43.0-rc1` was later presented under a GA identity.
// Candidate state lives in `CandidateHandoff` (`candidateSequence`, `candidateId` = sha256 of
// ROADMAP A (2026-10-11): 0.50.0 -> 0.51.0 (MINOR). CRIC-M3 publishes
// output.read.digested.v1, output.pin.v1, and output.refusal.retention.v1
// as EXPERIMENTAL consumer-negotiable capabilities. M1 and M2 capabilities
// carry forward (M1 PUBLICADA, M2 EXPERIMENTAL → PUBLICADA on cert).
// The 0.50.0 train is closed (v0.50.0-rc1 was the only candidate); the
// 0.51.0 train opens with v0.51.0-rc1. ProductVersion rejects a candidate
// suffix by construction; the candidate is identified by digest
// (CandidateId = sha256 of the distribution ZIP), not by the version
// string.
//
// R4 / WU-D — the 0.61.0 train publishes v0.61.0-rc1 (and now rc2) with
// the train version as ProductVersion. v0.60.0 was the version shipped
// at v0.61.0-rc1 (an oversight in the rc1 block: ProductVersion was the
// 0.60.0 string while the candidate was the 0.61.0 train). Bumped here
// to 0.61.0 so the rc2 candidate has ProductVersion = 0.61.0, satisfying
// PRODUCT IDENTITY LAW across all five surfaces.
//
// 2026-10-16: the 0.61.0 train closes at v0.61.0-rc6. The 0.62.0 train
// opens here (ProductVersion 0.62.0) so the next candidate can be tagged
// v0.62.0-rc1 and satisfy PRODUCT IDENTITY LAW across all five surfaces.
//
// 2026-10-19: the 0.62.0 train closes at v0.62.0. The 0.63.0 train opens
// here (ProductVersion 0.63.0) so the next candidate can be tagged
// v0.63.0-rc1 and satisfy PRODUCT IDENTITY LAW across all five surfaces.
//
// 2026-10-19: the 0.63.0 train closes at v0.63.0-rc1. The 0.64.0 train opens
// here (ProductVersion 0.64.0) so the next candidate can be tagged
// v0.64.0-rc1 and satisfy PRODUCT IDENTITY LAW across all five surfaces.
// The public SDK contracts grew in this train: the Saga SLOT seam
// (BodySlotInvoker, BodySlots, SlotResolution) is new published API, so the
// version that carries it cannot be the version that shipped without it.
//
// 2026-10-19: the 0.64.0 train closes at v0.64.0-rc1. The 0.65.0 train opens
// here (ProductVersion 0.65.0) so the next candidate can be tagged
// v0.65.0-rc1 and satisfy PRODUCT IDENTITY LAW across all five surfaces.
// The public SDK contracts grew in this train too: the Saga PLAN port
// (SagaPlanOperations, SAGA_PLAN_CAPABILITY, CompensationDirective,
// SagaSnapshot, CompensationLifecycle, CompensationObservation) and the Saga
// obligation lifecycle states are new published API, so the version that
// carries them cannot be the version that shipped without them.
//
// 2026-10-19: the 0.65.0 train closes at v0.65.0-rc1. The 0.66.0 train opens
// here (ProductVersion 0.66.0) so the next candidate can be tagged
// v0.66.0-rc1 and satisfy PRODUCT IDENTITY LAW across all five surfaces.
// The public SDK contracts grew again in this train: the Saga RUNTIME seam
// (SagaRuntime, SagaPlan, SagaActionPlan, SagaCompensationReport,
// SAGA_RUNTIME_CAPABILITY) is new published API, so the version that carries it
// cannot be the version that shipped without it.
//
// 2026-10-19: the 0.66.0 train closes at v0.66.0-rc1. The 0.67.0 train opens
// here (ProductVersion 0.67.0) so the next candidate can be tagged
// v0.67.0-rc1 and satisfy PRODUCT IDENTITY LAW across all five surfaces.
// The public SDK grew additively in this train: InterventionReason gained
// COMPENSATION_NOT_DISPATCHED, the word for a compensation that was NOT
// dispatched because the durable state refused the write-ahead. The published
// vocabulary of a Saga is closed on purpose, so a fact the old one could not
// name needs a new name in it.
//
// 2026-10-19: the 0.67.0 train closes at v0.67.0-rc1. The 0.68.0 train opens
// here (ProductVersion 0.68.0) so the next candidate can be tagged
// v0.68.0-rc1 and satisfy PRODUCT IDENTITY LAW across all five surfaces.
// The public SDK grew additively in this train too: the Saga's own facts are
// EVENTS now (CompensationStarted, CompensationFinished, SagaTerminal), so a
// consumer of the event plane can see what the compensation loop did instead
// of having to read the durable rows to find out. New event kinds are new
// published API, and the version that carries them cannot be the version that
// shipped without them.
version = "0.68.0"

// WU-LPR-071: single-version provider. The root project.version is the SOLE authority
// for every subproject's publication version and for the jar manifest Implementation-Version
// (which pipelinek reads at runtime via `version`). Subprojects inherit by default; we make
// the policy explicit and refuse per-subproject overrides. Any future subproject MUST NOT
// declare its own `version = "..."` — that is a release-time defect.
//
// Snapshot policy (v0.43-train):
//   - v0.40.0 and v0.41.0-rc1 are released candidates and remain immutable.
//   - v0.42.0-rc1 is the released candidate of the previous train; immutable.
//     Its receipt was corrected to retract the scmGit finding (e18ef439); the
//     finding is now fixed properly by the S0-C1 consumption gate.
//   - v0.43.0-rc1 was the candidate of the previous train; immutable. Its
//     installed-distribution UAT certified the bytes built at 1910083e.
//   - v0.44.0-rc1 was the candidate of the previous step of this train; its
//     bytes remain a historical candidate and are NOT renamed to GA. TRAIN P3
//     changed the declared product version to the final 0.44.0, which produced
//     NEW bytes with a NEW CandidateId. MINOR because the v0.43.0..HEAD range
//     carries six feats (S1-A directive kernel, S1-B DSL block plus admission
//     planner, S1-C typed directive observability events, S1-D external
//     directive plugin, plus the directive kernel fitness and the
//     RUN-CONCURRENCY-1 characterisation) and one fix (CLI: typed rejection for
//     a missing pipeline script) with no breaking change and no `!` footer.
//     Train derived from the history, not chosen by hand.
//
// TRAIN P3 identity law (release-evolution, operator-authorized):
//
//   The candidate's identity is `CandidateId = SHA256(ZIP)`. The product
//   version is the identity the binary REPORTS, and it is final (0.44.0) from
//   the moment the candidate is built. Candidate state is NOT expressed as an
//   `-rcN` suffix inside the product version, and it is NOT expressed by the
//   presence of a tag.
//
//   The stable tag is the RESULT of a future promotion, so it cannot be an
//   input to building or certifying this candidate. The previous note here
//   demanded `version` equal the git tag; that coupling is precisely what
//   produced the v0.43.0 family of defect, and it is deliberately removed. What
//   holds instead: the product version equals the version compiled into every
//   identity surface of the artifact, and the tag is applied later to exactly
//   the bytes the harness certified, or not at all.
//
// RP-042 consequence: the S1-E/F UAT certified the 0.43.0-rc1 bytes, and commit
// 0fa47f74 changed production code (Main.kt). Because the bytes changed, that
// certification does not transfer to this candidate: the installed-distribution
// smoke MUST be re-run against the bytes built at this version before any tag.
//
// This repo ships release CANDIDATES, not stable releases (AGENTS.md, "Release
// candidates"). A candidate is an immutable ZIP plus SBOM, SHA256SUMS and a
// manifest, delivered to the external pipelinek-release-harness, which owns
// certification and stable promotion. Nothing here promotes to stable.
//
// Fail-closed law: the artifact's `version` subcommand MUST equal the declared
// product version, and every identity surface in the archive MUST agree with
// it. A stable tag is applied afterwards to those exact bytes, or not at all.
subprojects {
    version = rootProject.version

    // Every subproject jar carries the same Implementation-Version attribute so
    // downstream introspection (or a future pipelinek that inspects dependency
    // manifests) reports a real version rather than "unknown".
    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version.toString(),
                "Implementation-Vendor" to project.group.toString(),
                "Built-By" to "Gradle",
            )
        }
    }
}

// The V2 root is an aggregate build. Its lifecycle check is the repository
// gate and deliberately covers every active V2 subproject declared in settings.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        rootProject.tasks.named("check") {
            dependsOn(tasks.named("check"))
        }
    }
}

// Kover was REMOVED (2026-10-18, testing RESET). Coverage verification is derived
// from executed tests; with the Kotlin suites gone it measures 0% and every
// `bound` rule would fail `check` for a reason that says nothing about the
// product. Keeping a disabled-but-configured coverage gate is the same false
// signal as a green empty `Test` task, so the plugin and its per-module rule
// maps went with the suites they measured.
//
// Static analysis is NOT removed: detekt below is the "análisis estático" leg of
// the current verification pyramid and needs no tests to produce a verdict.

// WU-RP-040 R5: SAST (detekt). Applied to every Kotlin subproject with a single
// shared config, and wired into each subproject's own `check` so the root
// `check` inherits it.
//
// This block used to assert that detekt was NOT in `check` and pointed at
// `lpr0-ci.yml` as the authority that ran it instead. That workflow was deleted
// in `754ddda0` (2026-09-30) after 60 consecutive cancelled runs against offline
// self-hosted runners. The comment therefore described a gate that no longer
// existed, and SAST quietly stopped being enforced by anything. The fix is not
// to restore the comment's claim or to recreate the workflow; it is to make the
// local command say what it actually does, so `./gradlew check` fails on a new
// static-analysis finding with no external dependency.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        pluginManager.apply("dev.detekt")
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            buildUponDefaultConfig.set(true)
            parallel.set(true)
            // Only `src/main/kotlin`: `src/test/kotlin` no longer exists, and listing
            // a non-existent source directory is how a source-set guard silently
            // covers nothing.
            source.setFrom(files("src/main/kotlin"))
            // WU-RP-040 R5: initial honest debt snapshot (2026-09-23, HEAD 6f7c445f).
            // Baseline freezes pre-existing findings; the gate fails on ANY new
            // issue. Debt burn-down tracked in the slice receipt.
            //
            // The filename is the plugin's own `detekt-baseline-main.xml`, the file
            // `./gradlew detektBaselineMain` writes. It used to be
            // `detekt-baseline.xml`, which no task produced: running the snapshot
            // task created a second file that the gate never read, so refreshing the
            // debt snapshot appeared to work and changed nothing. Naming the file
            // the task writes is what makes the refresh one reproducible command.
            val moduleBaseline = layout.projectDirectory.file("detekt-baseline-main.xml")
            if (moduleBaseline.asFile.exists()) {
                baseline.set(moduleBaseline)
            }
        }
        // detekt 2.x: report toggles live on the task, not the extension.
        tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
            reports {
                html.required.set(true)
                checkstyle.required.set(true)
            }
        }
        // Detekt 2.x no longer self-attaches to `check` (detekt 1.x did), so the
        // attachment has to be stated here or SAST is not part of the gate at all.
        //
        // It has to name `detektMain`, not `detekt`. In 2.0.0-alpha.6 the bare
        // `detekt` task carries no default source set: it analyses zero files and
        // reports success, so naming it made the gate pass without looking at the
        // code. Measured, not inferred: a deliberately unused import added to a
        // `src/main` file left `detekt` at BUILD SUCCESSFUL with a 79-byte, zero-file
        // report, while `detektMain` named the import, its line and its rule.
        //
        // The first half of this comment was already half the fix — detekt 2.x moved
        // the work out of the task `check` used to reach, and the attachment was
        // restated without the task identity following it.
        tasks.named("check") {
            dependsOn(tasks.named("detektMain"))
        }
    }
}

// The Kover-all merge module was removed with the plugin (2026-10-18). It aggregated
// coverage from the Kotlin suites; with those gone every dependency in the merge list
// resolves to zero coverage, so it reported a number that could not fail and could not
// pass either.

// Lane R: expose the repository root to tests. Tests that must read files from the
// repository previously hardcoded a developer-specific absolute path such as
// /var/home/<user>/Proyectos/kotlin/pipeline-kotlin, which pinned the suite to one
// machine and to one worktree. rootDir is the v2 build root; its parent is the repository.
val repositoryRoot: String = rootDir.parentFile.absolutePath

subprojects {
    // Lane R: expose the repository root to tests that must read files from the repository.
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

// ── C5 / D-011 (audit 2026-09-26, R7): binary-compatibility-validator ──────────
// Phase 1 (2026-09-26, commit `2d83fe3d`): opt-in to four modules with the most stable
// public Kotlin surface. The plugin emits one .api file per module under
// api/<module>.api (default location; explicit below for predictability) and
// exposes the `apiCheck` task. Phase 2 below wires that task into each selected
// module's normal `check` lifecycle.
//
// Phase 2 (2026-09-26, C5): the four baselined public modules wire `apiCheck` into
// their own `check` lifecycle. The aggregate root `check` already depends on
// every Kotlin subproject check, so ABI drift now fails the normal repository gate.
//
// The chosen modules have the largest stable JVM ABI surfaces:
//   - pipeline-domain: 248 top-level types (Step contract, ReplayPolicy, durable).
//   - pipeline-events: 96 types (event algebra, sink interfaces).
//   - pipeline-credentials-api: credentials SDK (SecretPatternRegistry, etc.).
//
// BCV guards MORE modules than are published, and the two questions are different. `bcvModules`
// answers "which internal ABI do I want to see reviewed when it changes"; `publishedContractModules`
// below answers "which artifact can an external consumer resolve". `pipeline-step-sdk:api` and
// `pipeline-credentials-api` are in the first list and not the second, and that is measured, not
// assumed: neither applies `maven-publish`, so neither resolves as a Maven coordinate, and
// `examples/` imports nothing from `dev.rubentxu.pipeline.v2.sdk`.
//
// This comment used to read "pipeline-step-sdk:api: SDK contract for external plugin authors". That
// was false, and it was the same defect this session already paid for once in the KSP: a written
// claim that no evidence backs, pointing a future reader at a consumer program that does not exist.
// It also called this a four-module list while the set below has carried six since BLOCK 2.
//
// The consequence for a break: a BCV-only module gets no maturity classification and no exception
// receipt, because it has no consumer program that could break silently. Its internal callers are
// inside this build, so a removed public type fails `compileKotlin` immediately and loudly. What
// the dump buys is the review — a diff nobody has to be shown.
//
// Adding a new module to BCV requires two steps: (1) append its name to
// `bcvModules` below; (2) run `:pipeline-<x>:apiDump` to materialise the
// baseline, then commit the .api file alongside the build change.
val bcvModules = setOf(
    "pipeline-domain",
    "pipeline-events",
    "pipeline-step-sdk:api".removePrefix(":"), // resolved below by project.path
    "pipeline-credentials-api",
    // BLOCK 2: the published output read contract. Added because `:pipeline-output` became a
    // PUBLISHED artifact, and the dump is what freezes exactly which types a consumer resolves.
    // Without it, adding a public type to the output plane would change the published surface with
    // nothing failing: no diff to review, and no `apiCheck` to refuse it.
    //
    // `:pipeline-output-store` and `:pipeline-events-store` are deliberately NOT here. BCV protects
    // a published ABI; those modules are not published, and a baseline over an internal
    // implementation is maintenance with no consumer behind it.
    "pipeline-output",
    // BLOCK 2: already PUBLISHED since Lane R (the external plugin compiles against it) and
    // therefore already had no ABI guard. Found while running `apiCheck` across the published set:
    // the task did not exist. Guarding three published contracts and leaving the fourth free would
    // have been the inconsistency, not the fix.
    "pipeline-scripting-api",
    // 0.52.0-rc1 / CRIC-M2: runtime ports publication. The new `:pipeline-runtime-api` module
    // (carved out of `:pipeline-runtime` in commit 2a97653a) carries the M2 publish/subscribe
    // ports and their ADTs. Guiding the published ABI here so `apiCheck` refuses an
    // unannounced surface change.
    "pipeline-runtime-api",
)

subprojects {
    val isBcvModule = bcvModules.any { project.path == ":$it" || project.path.endsWith(":$it") }
    if (isBcvModule) {
        pluginManager.apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        // C5 Phase 2: wait until the Kotlin JVM plugin has created the
        // lifecycle tasks, then fail the module gate on an ABI mismatch instead
        // of leaving the validator as an opt-in task. The allowlist above remains
        // the only authority for which published API surfaces are checked.
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            tasks.named("check") {
                dependsOn(tasks.named("apiCheck"))
            }
        }
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

/**
 * The modules whose contract is PUBLISHED, as an explicit list.
 *
 * BLOCK 2 is what turns this from two entries into four. An external consumer — `pipelinek-fabric`
 * first of all — has to resolve these as ordinary Maven coordinates produced from THIS source
 * revision, with no source or composite dependency on this repository.
 *
 * The list is here, and not spelled out one task per module, so that "what is published" is a
 * single readable sentence. A module that is not on it is not published, and its absence is a
 * decision rather than an oversight: `:pipeline-output-store` and `:pipeline-events-store` carry
 * the filesystem, JDBC and replay implementations precisely so that they CANNOT be on it.
 *
 * A module joins by being appended here AND applying `maven-publish` with a `sdk` publication AND
 * declaring every dependency that reaches its public ABI as `api`. All three, because each of the
 * other two without this one is a publication whose POM cannot compile a consumer.
 */
val publishedContractModules = listOf(
    "pipeline-domain",
    "pipeline-scripting-api",
    // BLOCK 2: the event contract (envelope, cursor, read/paging) and the output read contract.
    "pipeline-events",
    "pipeline-output",
    // 0.52.0-rc1 / CRIC-M2: the runtime publish/subscribe ports. The four existing entries
    // (read-side contracts for events, output, scripting, and domain) gave consumers a
    // complete read plane; this fifth entry adds the write plane, and the BOM picks up the
    // matching `api(...)` constraint (see v2/pipeline-sdk-bom/build.gradle.kts).
    //
    // This comment used to name `PublishedContractBoundaryFitnessTest` as the guard on the
    // size of this list, and pointed at its source under
    // `v2/pipeline-architecture-tests/`. That fitness was deleted on 2026-10-18 with the rest
    // of the QA harness, so the list above now has NO automated guard on its membership.
    // `apiCheck` proves the published surface is internally consistent; it does not prove the
    // list is the right list. Anyone adding a sixth published contract must decide here, in
    // review, whether it earns publication.
    "pipeline-runtime-api",
)

/**
 * B2A: the SDK BOM, published alongside the five contracts and deliberately OUTSIDE
 * [publishedContractModules].
 *
 * The two lists answer different questions. `publishedContractModules` is the set of artifacts whose
 * TYPE surface a consumer compiles against and whose bytes `apiCheck` freezes — it is read by
 * `PublishedContractBoundaryFitnessTest` and must stay exactly the five. `sdkBomModules` is the set
 * of published RESOLUTION documents: `:pipeline-sdk-bom` carries no classes, no ABI and no tests, so
 * adding it to the contract list would be a claim that it has a surface to guard. It does not; it
 * has five version constraints derived from `rootProject.version`.
 *
 * It is published by the same task, into the same `sdk-repo`, from the same revision, so a consumer
 * resolving the platform and the coordinates finds a consistent set or finds nothing.
 */
val sdkBomModules = listOf(
    "pipeline-sdk-bom",
    // 0.52.0-rc1 / CRIC-M2: the runtime API module is also in the BOM, so a consumer that
    // pins the platform version pulls the runtime ports automatically. The BOM is the
    // single resolution document, and leaving the runtime ports out of it would force
    // every consumer to declare the version independently.
    "pipeline-runtime-api",
)

val publishSdkForExternalPlugin by tasks.registering {
    group = "build"
    description = "Publishes the published-contract artifacts an external consumer compiles against."
    dependsOn(publishedContractModules.map { ":$it:publishSdkPublicationToSdkRepository" })
    // B2A: the BOM is published by this same task, not by a second mechanism. Its presence here
    // (and its absence from `publishedContractModules`) is the whole "convenience, not contract"
    // distinction, expressed in the build rather than only in a comment.
    dependsOn(sdkBomModules.map { ":$it:publishSdkPublicationToSdkRepository" })
}

val buildExamplePlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external example plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("examples/example-uppercase-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    // The plugin's output depends on the SDK jars of THIS revision, not on the
    // repository's timestamped snapshot filenames.
    //
    // `pipeline-events` joins the list in P3-D: the plugin now contributes an event definition and
    // emits it through the published emission seam, so it compiles against that contract too.
    // Omitting it from these inputs would let the task consider its output UP-TO-DATE after a
    // change to the Event Plane — and the plugin would then be shipping against a stale seam.
    inputs.files(":pipeline-domain:jar", ":pipeline-scripting-api:jar", ":pipeline-events:jar")
    // R4 / WU-R4.0.0 — declare rootProject.version as an explicit input. See
    // buildExampleBlockPlugin for the full story (apiRange goes stale when
    // the host version bumps but the SDK jar content does not).
    inputs.property("rootProject.version", rootProject.version.toString())
    outputs.file(pluginDir.resolve("build/libs/example-uppercase-plugin-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        // Pass the SDK version the external plugin must resolve. Without this the
        // plugin's default sdkVersion is 0.1.0-SNAPSHOT, which is not in the local
        // sdk-repo (it carries the root project version 0.39.0). The --rerun-tasks
        // failure mode observed 2026-09-19 was caused by this omission: the example
        // build re-resolved the SDK from scratch, hit the default version, and could
        // not find the module.
        "-PsdkVersion=" + rootProject.version.toString(),
        "jar",
    )
}

// SAGA / PK-1 — the external CONSUMER of the Saga intent port.
//
// It exists for the same reason as the plugin above and with the same discipline: it is built
// by a SECOND Gradle, against the SDK artifacts this revision PUBLISHED, so the contract is
// proven from outside the repository rather than from inside it. The difference is what it
// consumes: a published capability that only the host can provide.
//
// The SDK jars of THIS revision are declared inputs, so the task cannot consider its output
// UP-TO-DATE after a change to the Saga contract: a consumer built against a stale seam is
// exactly the failure this task exists to make visible.
val buildExampleSagaPlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external Saga consumer against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("examples/example-saga-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar", ":pipeline-scripting-api:jar")
    inputs.property("rootProject.version", rootProject.version.toString())
    outputs.file(pluginDir.resolve("build/libs/example-saga-plugin-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "-PsdkVersion=" + rootProject.version.toString(),
        "jar",
    )
}

// S6/I: the external plugin is now the SUBJECT of a test in :pipeline-application, and a test
// whose subject is optional is a test that passes by omission. `the artifact really carries a
// manifest and all four ServiceLoader descriptors` would report the absence of every family as a
// clean sheet rather than as a missing build.
//
// The cost is real and is stated rather than discovered later: every `check` now publishes to
// sdk-repo and forks a second Gradle against the working tree, which is exactly what the three
// external builds below were kept out of `check` to avoid. It is paid here because the other
// tasks produce artifacts nothing under `check` inspects, while this one produces the artifact
// that decides whether BLOCK 1-I is real.
//
// FAST LANE (2026-10-17): the subject test now lives in `:pipeline-application`'s
// `pluginContractTest` lane, which declares the three JARs as a buildable file collection
// over the tasks that produce them. THIS WIRING IS NOW REDUNDANT AND DELETED, and that is
// the whole point of the change. It used to read
// `tasks.named("test") { dependsOn(buildExamplePlugin) }`: the INNER LOOP paid for external
// certification — republishing the SDK to sdk-repo and forking a second
// Gradle — on every run after a `pipeline-domain` edit, which is the most common edit there
// is. That contradicted the fast-lane contract `pipeline-application/build.gradle.kts`
// documents 600 lines away, and the two files could each be read in isolation without the
// contradiction being visible. `:pipeline-application`'s `tasks.test` now carries a `doFirst`
// invariant that fails the build if an external plugin build reappears in the RESOLVED
// fast-lane graph, so this cannot be re-introduced from a second file.
//
//
// The `withPlugin` hook this block used is gone with it. The BCV block below hooks the
// same way for the same reason (the root script is evaluated before the Kotlin JVM plugin
// creates the subproject's tasks).

// S1-D: the directive analogue of buildExamplePlugin. The external directive
// plugin proves the directive kernel is open by key from outside the build.
val buildExternalDirectivePlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external directive plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("examples/example-directive-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar")
    // R4 / WU-R4.0.0 — declare rootProject.version as an explicit input. See
    // buildExampleBlockPlugin for the full story.
    inputs.property("rootProject.version", rootProject.version.toString())
    outputs.file(pluginDir.resolve("build/libs/example-directive-plugin-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "-PsdkVersion=" + rootProject.version.toString(),
        "jar",
    )
}

// WU-RP-035 / slice D: the BODY-bearing analogue. This plugin is the proof that the
// corrected ADR-0081 claim holds from outside the build: its handler drives its own body
// through the bound continuation, compiled against the public SDK only. It is built as an
// INDEPENDENT Gradle project on purpose — if it needed anything from pipeline-application,
// the open-world claim this WU certifies would be false.
val buildExampleBlockPlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external body plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("examples/example-block-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar")
    // R4 / WU-R4.0.0 — declare rootProject.version as an explicit input. The
    // example plugin's Gradle property `-PsdkVersion=` is derived from this
    // value at commandLine time, but Gradle's default fingerprint for an
    // Exec task does NOT see the version string when only the SDK jar content
    // is declared as an input. A bump from 0.59.0 to 0.60.0 with no source
    // change previously left the example plugin's apiRange at [0.59.0, 0.60.0),
    // which the shipped installDist then refused as IncompatibleApiRange.
    // Without this property declared, every `pipelinek run` against that
    // installDist exited 2 BEFORE the run body compiled.
    inputs.property("rootProject.version", rootProject.version.toString())
    outputs.file(pluginDir.resolve("build/libs/example-block-plugin-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "-PsdkVersion=" + rootProject.version.toString(),
        "jar",
    )
}

// PK-RELEASE-CONSOLIDATION H1.4 follow-up — stage the three external example
// plugin JARs into the application distribution so the releaseGate (and
// DistributionMandatoryPluginsTest) can observe them.
//
// The example plugins are independent builds outside v2/, so they cannot
// be wired as project(":example-block-plugin") dependencies. The
// staging task copies the built JARs into pipeline-application/lib/ so
// the distZip and installDist both pick them up alongside the four
// official :pipeline-step-sdk plugins.
//
// Each input is a single external JAR, declared as a file. The
// external build must run first (the dependsOn below), and the
// inputs force re-staging if the JARs change.
val stageExternalExamples by tasks.registering {
    group = "distribution"
    description = "PK-RELEASE-CONSOLIDATION H1.4: stage the three external example " +
        "plugins (block, directive, uppercase) into pipeline-application/lib/ so " +
        "the distribution ZIP carries them as bundled plugins."

    dependsOn(buildExamplePlugin, buildExternalDirectivePlugin, buildExampleBlockPlugin)

    val appLibDir = file("pipeline-application/build/install/pipelinek/lib")
    val targets = listOf(
        file("examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar") to "example-uppercase-plugin-0.1.0.jar",
        file("examples/example-directive-plugin/build/libs/example-directive-plugin-0.1.0.jar") to "example-directive-plugin-0.1.0.jar",
        file("examples/example-block-plugin/build/libs/example-block-plugin-0.1.0.jar") to "example-block-plugin-0.1.0.jar",
    )

    inputs.files(targets.map { it.first })
    outputs.files(targets.map { appLibDir.resolve(it.second) })

    doLast {
        appLibDir.mkdirs()
        for ((src, name) in targets) {
            if (!src.exists()) {
                throw GradleException(
                    "stageExternalExamples: expected external plugin JAR at $src but it " +
                        "does not exist. Run the external build first: ./gradlew " +
                        "${src.name.removeSuffix("-0.1.0.jar")}:jar -PsdkVersion=<host>."
                )
            }
            val dst = appLibDir.resolve(name)
            src.copyTo(dst, overwrite = true)
            println("staged: ${src.name} -> ${dst.absolutePath}")
        }
    }
}

// The :pipeline-application:distZip task (and the installDist task it
// depends on) must run AFTER the external JARs are built, because
// pipeline-application/build.gradle.kts declares them as `runtimeOnly`
// files. The implicit file dependency makes Gradle fail the build
// with "Declare an explicit dependency ... using Task#mustRunAfter"
// unless we declare the ordering here. Once the external JARs
// exist, `installDist` stages them into `lib/` and `distZip` packages
// the lib directory.
//
// `mustRunAfter` is necessary but not sufficient: Gradle would still
// see installDist as UP-TO-DATE if the staged JARs are not declared as
// inputs. The staged JARs are in `lib/`, which installDist does not
// know about; the input declaration is the only way to make Gradle
// re-run installDist when an external JAR changes.
project(":pipeline-application").pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    val appProject = project(":pipeline-application")
    val appLibDir = file("pipeline-application/build/install/pipelinek/lib")
    val externalJars = listOf(
        "example-uppercase-plugin-0.1.0.jar",
        "example-directive-plugin-0.1.0.jar",
        "example-block-plugin-0.1.0.jar",
    )
    val externalBuildTasks = listOf(
        "buildExamplePlugin",
        "buildExternalDirectivePlugin",
        "buildExampleBlockPlugin",
    )
    appProject.tasks.matching {
        it.name == "distZip" || it.name == "installDist" || it.name == "startScripts"
    }.configureEach {
        externalBuildTasks.forEach { name ->
            dependsOn(":$name")
        }
        // Declare the staged JARs as inputs so Gradle invalidates
        // installDist/distZip when an external JAR changes.
        inputs.files(externalJars.map { appLibDir.resolve(it) })
            .withPropertyName("externalExamplePlugins")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

/**
 * BLOCK 2: the independent consumer, run as a gate step.
 *
 * `apiCheck` and `PublishedContractBoundaryFitnessTest` both look at the published surface from
 * INSIDE this build, and all three of them can be satisfied by an artifact a real consumer still
 * cannot compile against. `examples/fabric-contract-consumer` is the outside: its own settings
 * file, no `project(...)` anywhere, resolving only the five published coordinates. It builds the
 * `RunOutcome` / `OutputRefusal` / `EventQuery` mappings Fabric will need and pins their wire
 * forms against a golden committed OUTSIDE `v2`, so a contract change fails a build that never
 * saw the change.
 *
 * It runs `check` rather than `jar` deliberately: the point is the twelve tests and the negative
 * control that fails if an implementation module becomes resolvable, not that some classes were
 * produced.
 *
 * NOT a dependency of `check`, and that is a decision rather than an omission. Wiring it in would
 * make every `check` publish to `sdk-repo` and fork a second Gradle against the working tree; the
 * three external plugin builds above stand outside `check` for the same reason, and this one
 * follows them. The cost is that it is only as reliable as whoever runs it, which is why the
 * BLOCK 2 receipt names this task as a required gate step rather than leaving it to habit.
 */
val verifyFabricContractConsumer by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles and tests the independent consumer against this revision's published contracts."
    dependsOn(publishSdkForExternalPlugin)

    val consumerDir = file("examples/fabric-contract-consumer")
    inputs.dir(consumerDir.resolve("src"))
    inputs.files(consumerDir.resolve("build.gradle.kts"), consumerDir.resolve("settings.gradle.kts"))
    // The consumer reads the five CONTRACT modules, so those are the jars whose bytes it compiles
    // against. Listing the store modules would be wrong: they are not published, and adding them
    // here would let a future publication slip past the consumer's own negative control by making
    // the outer build quietly expect it.
    inputs.files(publishedContractModules.map { module -> project(":$module").tasks.named("jar") })
    outputs.file(consumerDir.resolve("build/libs/fabric-contract-consumer-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", consumerDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "-PsdkVersion=" + rootProject.version.toString(),
        "check",
    )
}

/**
 * B2A — the end-to-end EXTERNAL EXECUTION gate: the outside, running the product.
 *
 * `buildExamplePlugin` proves the plugin COMPILES against the published SDK, and the two
 * `verify*Consumer` tasks prove a consumer can COMPILE against it. None of them runs the installed
 * product with an externally built plugin. This task does, and it is the only place in the
 * repository that closes that loop from outside the product build:
 *
 * ```text
 * examples/sdk-external-execution   (own settings file, no project(...))
 *   -> resolves the five contracts through pipeline-sdk-bom      (ENTREGA A)
 *   -> builds examples/example-uppercase-plugin against that SDK (Exec, independent build)
 *   -> runs :pipeline-application's INSTALLED binary with --plugin-jar against a .pipeline.kts
 *   -> asserts: exit 0, "Pipeline finished with SUCCESS", and the plugin's own event in the run
 * ```
 *
 * The subprocess is the PRODUCT, not a test double: same JAR the CLI installs, same `--plugin-jar`
 * argument `CliParser` accepts. A pipeline that ended successfully without the plugin executing
 * would still fail the assertion, which is why the event kind is asserted and not just the exit.
 *
 * NOT a dependency of `check`, and the cost is stated rather than discovered later: wiring it in
 * would make every `check` publish to `sdk-repo`, run `installDist`, and fork a third Gradle (the
 * external build forks a second one for the plugin). That cost is not paid per `check` run. This is
 * a named closeout gate step, run explicitly as `:verifySdkExternalExecution`.
 */
val verifySdkExternalExecution by tasks.registering(Exec::class) {
    group = "build"
    description = "Runs the installed product against an externally built plugin, resolved through the SDK BOM."
    // Both artifacts this task reads are produced HERE, by task dependency rather than by a nested
    // `v2` invocation: the root build already holds this checkout's build lock, so forking `v2`
    // from inside it would fail rather than wait.
    dependsOn(publishSdkForExternalPlugin)
    dependsOn(":pipeline-application:installDist")

    val externalDir = file("examples/sdk-external-execution")
    inputs.dir(externalDir.resolve("src"))
    inputs.dir(externalDir.resolve("fixtures"))
    inputs.files(
        externalDir.resolve("build.gradle.kts"),
        externalDir.resolve("settings.gradle.kts"),
    )
    // The installed binary is a real input: a change to the product must re-run this gate.
    val installedBinary = project(":pipeline-application")
        .layout
        .buildDirectory
        .dir("install/pipelinek/bin")
        .map { it.file("pipelinek") }
    inputs.file(installedBinary)

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", externalDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "-PsdkVersion=" + rootProject.version.toString(),
        "-PpipelinekBin=" + installedBinary.get().asFile.absolutePath,
        "check",
    )
}

/**
 * P3-E E6 — the SINGLE-coordinate consumer, run as a gate step.
 *
 * `StepSpec.Error.failureKind` became `FailureKind`, a type from `:pipeline-domain`. That forces
 * `pipeline-scripting-api` to publish that dependency at `api` rather than `implementation`, and
 * that is a change to the PUBLICATION contract: `api` is what writes a compile-scope dependency
 * into the published Gradle Module Metadata, so it is the only reason a consumer can name the type
 * at all.
 *
 * `examples/fabric-contract-consumer` cannot observe this. It declares all five coordinates, so it
 * compiles whether the publisher said `api` or `implementation` — the property under test would be
 * satisfied by that build's own dependency list. `examples/scripting-contract-consumer` declares
 * ONE coordinate and never names `pipeline-domain`, so the publisher's metadata is the only route
 * by which `FailureKind` can resolve.
 *
 * The control is mechanical and it was measured, not assumed: reverting the publisher to
 * `implementation(...)` makes that build fail with `Cannot access class
 * dev.rubentxu.pipeline.v2.domain.FailureKind. Check your module classpath`. The declaration is
 * therefore the test; a comment asserting that the declaration exists would be worth nothing.
 *
 * Like the consumer above, it is NOT a dependency of `check`: wiring it in would make every
 * `check` publish to `sdk-repo` and fork a second Gradle against the working tree. BLOCK E names it
 * as a required closeout step rather than leaving it to habit.
 */
val verifyScriptingContractConsumer by tasks.registering(Exec::class) {
    group = "build"
    description = "Proves FailureKind reaches a consumer declaring only pipeline-scripting-api, via publisher metadata."
    dependsOn(publishSdkForExternalPlugin)

    val consumerDir = file("examples/scripting-contract-consumer")
    inputs.dir(consumerDir.resolve("src"))
    inputs.files(consumerDir.resolve("build.gradle.kts"), consumerDir.resolve("settings.gradle.kts"))
    // Only the scripting jar's bytes are read. Listing `pipeline-domain` here would defeat the
    // experiment: the whole claim is that the consumer cannot get the type except through the
    // publisher's own declaration of it.
    inputs.files(project(":pipeline-scripting-api").tasks.named("jar"))
    outputs.file(consumerDir.resolve("build/libs/scripting-contract-consumer-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", consumerDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        "-PsdkVersion=" + rootProject.version.toString(),
        "check",
    )
}
