plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.kover) apply true
    alias(libs.plugins.detekt) apply false
    // C5 / D-011 (audit 2026-09-26): binary-compatibility-validator declared at
    // root and applied per-module via subprojects { ... } below. See comments in
    // the application block for the phased rollout scope.
    alias(libs.plugins.binary.compatibility.validator) apply false
}

group = "dev.rubentxu.pipeline.v2"
// WU-RP-040 R7: root-level repositories are required because the kover merge
// configuration (kover(project(...))) resolves external deps of merged modules
// in the ROOT project (Gradle resolves configurations in the consuming
// project). dependencyResolutionManagement covers subprojects only.
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
// 0.47.0 train WITHOUT creating a tag and WITHOUT claiming certification.
version = "0.47.0"

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
//     installed-distribution UAT (docs/v2/07-uat/S1_EF_INSTALLED_DIRECTIVE_UAT_RECEIPT.md)
//     certified the bytes built at 1910083e.
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

// WU-RP-040 R1: risk-based coverage verification (Kover).
// Thresholds are grounded in criticality, not vanity numbers:
//  - domain / sdk-api / events: pure decision + codec/policy logic -> HIGH bar
//  - application: coordinator composition mostly exercised by real-process UATs -> MEDIUM bar
// Exclusions: generated protobuf, scripting test harness bootstrap, example plugins.
kover {
    reports {
        verify {
            rule("Branch coverage of critical decision modules") {
                disabled = false
            }
        }
        filters {
            excludes {
                packages(
                    "dev.rubentxu.pipeline.v2.protos",
                    "dev.rubentxu.pipeline.v2.generated",
                )
            }
        }
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        // WU-RP-040 R7: kover must be applied to EVERY Kotlin module. Root-level
        // `kover(project(...))` against a module WITHOUT the plugin treats it as
        // an external artifact and resolves its runtime classpath with
        // usage=kover attributes, which external modules (kotlin-stdlib) do not
        // publish — known upstream bug Kotlin/kotlinx-kover#798. With the plugin
        // applied everywhere, the merge consumes only in-project variants.
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        // D-002: Rp022ThroughputProbe pins a 20 MB/s perf floor. Kover agent
        // instrumentation adds per-read overhead to StreamingRedactor and pushes
        // the probe below the floor on loaded machines. Coverage collected by
        // the probe has zero value; exclude the redactor classes from
        // instrumentation in this module so kover-all runs the full suite
        // without tripping the known flake (their coverage still comes from
        // the dedicated redaction tests, which stay instrumented).
        if (project.name == "pipeline-credentials-api") {
            kover {
                currentProject {
                    instrumentation {
                        excludedClasses.add("dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor*")
                    }
                }
            }
        }
        if (project.name in setOf("pipeline-domain", "pipeline-events")) {
            kover {
                currentProject {
                    sources {
                        excludedSourceSets.addAll(listOf("integrationTest"))
                    }
                }
            }
        }
        // D-013 (audit 2026-09-26): per-module Kover bound rules with
        // measured-and-reasoned `minValue` per module. The historical single
        // 55-bound for pipeline-domain/pipeline-events (named "branch coverage"
        // but the kover `bound` rule actually checks LINE coverage by default)
        // was useful, but it left the other 18 tracked modules unmonitored
        // against silent regressions. The numbers below are derived from the
        // most recent Kover HTML snapshots in `*/build/reports/kover/html/
        // index.html` (line %, "all classes" row); the value chosen is current
        // - 5pp for HIGH modules (≥75 line%) and current - 5pp for MID modules
        // (50-75 line%), with a generous floor of 30 for modules below 50.
        // Modules with disabled=true are intentionally not gated: their
        // coverage is dominated by generated code (KSP/api), DSL builders,
        // or fitness assertions, where a line-coverage metric is not the
        // right invariant. They keep being measured for visibility.
        // Note: the historical rule `minValue = 55` was effectively a
        // line-coverage gate (kover 0.9.x default metric for `bound` is
        // LINE). It was passing at 82.8% (domain) and 77.6% (events) so
        // the new 75/70 thresholds preserve the same intent (anti-regression
        // guard with slack) while bringing consistency to the rest of the
        // tracked modules.
        val koverRuleMinByModule = mapOf(
            // HIGH coverage (≥75 line)
            "pipeline-artefacts-local"      to 85,
            "pipeline-binding-factory"      to 85,
            "pipeline-domain"               to 75, // historical intent preserved at line-based
            "pipeline-events"               to 70, // 77.6 - 5 + slack for D-002 instrumentation overhead
            "pipeline-event-harness"        to 70,
            "pipeline-step-sdk/runtime"     to 70,
            "pipeline-step-sdk/utilities"   to 65,
            // MID coverage (50-75 line)
            "pipeline-step-sdk/scm-git"     to 55,
            "pipeline-credentials-executor" to 55,
            "pipeline-scripting-kotlin24"   to 55,
        )
        val koverRuleDisabledByModule = setOf(
            // generated / DSL / fitness / no-data modules: line metric not the right invariant
            "pipeline-step-sdk/api",        // 1.5% line: pure codec IR + interfaces
            "pipeline-step-sdk/processor",  // 9.4% line: KSP code generator
            "pipeline-step-sdk/junit",      // 15.5% line: dominant case is contract fixtures
            "pipeline-step-sdk/http",       // contract/fitness dominant; the wire is pinned by golden vectors
            "pipeline-step-sdk/files",      // no line data: only branch/class visible
            "pipeline-scripting-api",       // 32.1% line: pure DSL builder methods
            "pipeline-credentials-api",     // no line data: only branch visible
            "pipeline-credentials-local",   // no line data: only branch visible
            "pipeline-credentials-multipart", // no line data: only class/method visible
            "pipeline-testkit",             // test-only support module
            "pipeline-architecture-tests",  // fitness assertions only
            "pipeline-application",         // coordinator composition; coverage from harness UATs
        )
        when {
            project.name in koverRuleMinByModule -> {
                kover {
                    reports {
                        verify {
                            rule("D-013 branch coverage (anti-regression)") {
                                bound {
                                    minValue.set(koverRuleMinByModule.getValue(project.name))
                                }
                            }
                        }
                    }
                }
            }
            project.name in koverRuleDisabledByModule -> {
                kover {
                    reports {
                        verify {
                            rule("D-013 branch coverage (informational)") {
                                disabled = true
                            }
                        }
                    }
                }
            }
        }
    }
}

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
            source.setFrom(
                files(
                    "src/main/kotlin",
                    "src/test/kotlin",
                ),
            )
            // WU-RP-040 R5: initial honest debt snapshot (2026-09-23, HEAD 6f7c445f).
            // Baseline freezes pre-existing findings; the gate fails on ANY new
            // issue. Debt burn-down tracked in the slice receipt.
            val moduleBaseline = layout.projectDirectory.file("detekt-baseline.xml")
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
        tasks.named("check") {
            dependsOn(tasks.named("detekt"))
        }
    }
}

// WU-RP-040 R7: Kover-all. Root is the merging module: `./gradlew koverXmlReport`
// at the root now aggregates classes + coverage from every Kotlin module that
// has tests (the full per-module test suite runs first). Modules without test
// sources are omitted from the merge (Kover issue #706: empty kover deps fail).
dependencies {
    listOf(
        ":pipeline-domain",
        ":pipeline-application",
        ":pipeline-events",
        ":pipeline-event-harness",
        ":pipeline-scripting-api",
        ":pipeline-scripting-kotlin24",
        ":pipeline-step-sdk:api",
        ":pipeline-step-sdk:runtime",
        ":pipeline-step-sdk:scm-git",
        ":pipeline-step-sdk:http",
        ":pipeline-step-sdk:files",
        ":pipeline-step-sdk:utilities",
        ":pipeline-step-sdk:workflow-control",
        ":pipeline-credentials-api",
        ":pipeline-credentials-local",
        ":pipeline-credentials-multipart",
        ":pipeline-credentials-executor",
        ":pipeline-binding-factory",
        ":pipeline-artefacts-local",
    ).forEach { kover(project(it)) }
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
//   - pipeline-step-sdk:api: SDK contract for external plugin authors.
//   - pipeline-credentials-api: credentials SDK (SecretPatternRegistry, etc.).
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
)

val publishSdkForExternalPlugin by tasks.registering {
    group = "build"
    description = "Publishes the published-contract artifacts an external consumer compiles against."
    dependsOn(publishedContractModules.map { ":$it:publishSdkPublicationToSdkRepository" })
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

// S1-D: the directive analogue of buildExamplePlugin. The external directive
// plugin proves the directive kernel is open by key from outside the build.
val buildExternalDirectivePlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external directive plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("../examples/example-directive-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar")
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

    val pluginDir = file("../examples/example-block-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar")
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

/**
 * BLOCK 2: the independent consumer, run as a gate step.
 *
 * `apiCheck` and `PublishedContractBoundaryFitnessTest` both look at the published surface from
 * INSIDE this build, and all three of them can be satisfied by an artifact a real consumer still
 * cannot compile against. `examples/fabric-contract-consumer` is the outside: its own settings
 * file, no `project(...)` anywhere, resolving only the four published coordinates. It builds the
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

    val consumerDir = file("../examples/fabric-contract-consumer")
    inputs.dir(consumerDir.resolve("src"))
    inputs.files(consumerDir.resolve("build.gradle.kts"), consumerDir.resolve("settings.gradle.kts"))
    // The consumer reads the four CONTRACT modules, so those are the jars whose bytes it compiles
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
