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
version = "0.41.0-rc1"

// WU-LPR-071: single-version provider. The root project.version is the SOLE authority
// for every subproject's publication version and for the jar manifest Implementation-Version
// (which pipelinek reads at runtime via `version`). Subprojects inherit by default; we make
// the policy explicit and refuse per-subproject overrides. Any future subproject MUST NOT
// declare its own `version = "..."` — that is a release-time defect.
//
// Snapshot policy (v0.41-train, first release-candidate line):
//   - v0.40.0 is the last stable release and remains immutable.
//   - v0.41.0-rc1 is the candidate produced from this main commit.
//
// Fail-closed law: the released artifact's `version` subcommand MUST equal the git tag.
// If they ever diverge the build is broken at the source, not in the artifact.
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
// shared config; NOT wired into `check` (the gate runs it as an explicit CI job
// over a curated security/correctness subset — see lpr0-ci.yml). This keeps the
// incremental round gate cheap while the SAST report stays per-run evidence.
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
