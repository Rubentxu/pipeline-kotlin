plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    // Binary-compatibility-validator, declared at the root project and applied
    // per-module via subprojects {... } below. See comments in
    // the application block for the phased rollout scope.
    alias(libs.plugins.binary.compatibility.validator) apply false
}

group = "dev.rubentxu.pipeline.v2"
// Root-level repositories. This block existed for the Kover merge configuration
// (kover(project) resolves external deps in the ROOT project, which
// `dependencyResolutionManagement` does not cover). Kover was removed;
// the block is kept because `buildSrc` and the root plugin classpath resolve
// through it, and removing it would be a change without a measured reason.
repositories {
    mavenCentral()
}
// The product version. One authority: this line. Every subproject inherits it,
// and it is the value compiled into the jar manifest that the `version`
// subcommand reports at runtime.
// The version is the identity the binary REPORTS, and it is final from the
// moment the candidate is built. Candidate state is NOT expressed as an
// `-rcN` suffix inside the product version, and it is NOT expressed by the
// presence of a tag: the candidate's identity is `CandidateId = SHA256(ZIP)`.
// The stable tag is the RESULT of a later promotion, so it can never be an
// input to building or certifying this candidate. What holds instead: the
// product version equals the version compiled into every identity surface of
// the artifact, and the tag is applied afterwards to exactly the bytes the
// external harness certified, or not at all.
// Fail-closed law: the artifact's `version` subcommand MUST equal the declared
// product version, and every identity surface in the archive MUST agree with
// it.
// This repository ships release CANDIDATES, not stable releases. A candidate
// is an immutable ZIP plus SBOM, SHA256SUMS and a manifest, delivered to the
// external release harness, which owns certification and stable promotion.
// Nothing here promotes to stable.
// Why the version is never chosen by hand: a version that disagrees with the
// bytes it names produces an artifact that is internally consistent and
// externally false. The history of each bump lives in the commit history,
// which is the only place it can stay true.
version = "0.62.0"

// Single-version provider. The root project.version is the SOLE authority
// for every subproject's publication version and for the jar manifest Implementation-Version
// (which pipelinek reads at runtime via `version`). Subprojects inherit by default; we make
// the policy explicit and refuse per-subproject overrides. Any future subproject MUST NOT
// declare its own `version = "..."` — that is a release-time defect.
// A candidate's certification does not transfer: when the bytes change, the
// certification of the previous bytes does not certify this candidate. The
// installed-distribution smoke MUST be re-run against the bytes built at this
// version before any tag.
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

// The root is an aggregate build. Its lifecycle check is the repository
// Gate and deliberately covers every active subproject declared in settings
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        rootProject.tasks.named("check") {
            dependsOn(tasks.named("check"))
        }
    }
}

// Kover was REMOVED . Coverage verification is derived
// from executed tests; with the Kotlin suites gone it measures 0% and every
// `bound` rule would fail `check` for a reason that says nothing about the
// product. Keeping a disabled-but-configured coverage gate is the same false
// signal as a green empty `Test` task, so the plugin and its per-module rule
// maps went with the suites they measured.
// Static analysis is NOT removed: detekt below is the "análisis estático" leg of
// the current verification pyramid and needs no tests to produce a verdict.

// SAST (detekt). Applied to every Kotlin subproject with a single
// shared config, and wired into each subproject's own `check` so the root
// `check` inherits it.
// This block used to assert that detekt was NOT in `check` and pointed at
// `lpr0-ci.yml` as the authority that ran it instead. That workflow was deleted
// after 60 consecutive cancelled runs against offline
// self-hosted runners. The comment therefore described a gate that no longer
// existed, and SAST quietly stopped being enforced by anything. The fix is not
// to restore the comment's claim or to recreate the workflow; it is to make the
// local command say what it actually does, so `./gradlew check` fails on a new
// static-analysis finding with no external dependency.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        pluginManager.apply("dev.detekt")
        // The frozen debt snapshot. The baseline suppresses the findings that
        // already existed when it was taken, so the analysis fails on any NEW
        // one and the existing ones stop being news.
        val moduleBaseline = layout.projectDirectory.file("detekt-baseline-main.xml")
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            buildUponDefaultConfig.set(true)
            parallel.set(true)
            // Only `src/main/kotlin`: `src/test/kotlin` no longer exists, and listing
            // a non-existent source directory is how a source-set guard silently
            // covers nothing.
            source.setFrom(files("src/main/kotlin"))
            if (moduleBaseline.asFile.exists()) {
                baseline.set(moduleBaseline)
            }
        }
        // The refresh task writes WHERE THE ANALYSIS READS.
        // Detekt names its own baseline after the source set twice, so the refresh
        // landed in a second file that no gate read: the snapshot looked refreshed
        // and changed nothing, while the file the analysis does read kept carrying
        // findings that no longer existed. One name, one file, one command:
        //./gradlew detektBaselineMain
        // The type-resolution variant is the one to use. The source-set variant
        // cannot see the findings that need resolved types, so a snapshot taken
        // with it would not suppress what the analysis reports.
        tasks.named("detektBaselineMain", dev.detekt.gradle.DetektCreateBaselineTask::class) {
            baseline.set(moduleBaseline)
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
        // It has to name `detektMain`, not `detekt`. In 2.0.0-alpha.6 the bare
        // `detekt` task carries no default source set: it analyses zero files and
        // reports success, so naming it made the gate pass without looking at the
        // code. Measured, not inferred: a deliberately unused import added to a
        // `src/main` file left `detekt` at BUILD SUCCESSFUL with a 79-byte, zero-file
        // report, while `detektMain` named the import, its line and its rule.
        // The first half of this comment was already half the fix — detekt 2.x moved
        // the work out of the task `check` used to reach, and the attachment was
        // restated without the task identity following it.
        tasks.named("check") {
            dependsOn(tasks.named("detektMain"))
        }
    }
}

// The Kover-all merge module was removed with the plugin . It aggregated
// coverage from the Kotlin suites; with those gone every dependency in the merge list
// resolves to zero coverage, so it reported a number that could not fail and could not
// pass either.

// Expose the repository root to tests. Tests that must read files from the
// repository previously hardcoded a developer-specific absolute path such as
// /var/home/<user>/Proyectos/kotlin/pipeline-kotlin, which pinned the suite to one
// machine and to one worktree. rootDir is the build root; its parent is the repository.
val repositoryRoot: String = rootDir.parentFile.absolutePath

subprojects {
    // Expose the repository root to tests that must read files from the repository.
    tasks.withType<Test>().configureEach {
        systemProperty("pipeline.repoRoot", repositoryRoot)
    }
}

// Reproducible archives (BUILD ONCE / PUBLISH SAME BYTES).
// Nested project jars feed the pipelinek distZip; without these flags the
// embedded jar CRCs change on every rebuild (observed in an earlier build).
subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// ── binary-compatibility-validator ──────────
// Opt-in to four modules with the most stable
// public Kotlin surface. The plugin emits one.api file per module under
// api/<module>.api (default location; explicit below for predictability) and
// exposes the `apiCheck` task. The block below wires that task into each selected
// module's normal `check` lifecycle.
// The four baselined public modules wire `apiCheck` into
// their own `check` lifecycle. The aggregate root `check` already depends on
// every Kotlin subproject check, so ABI drift now fails the normal repository gate.
// The chosen modules have the largest stable JVM ABI surfaces:
//   - pipeline-domain: 248 top-level types (Step contract, ReplayPolicy, durable).
//   - pipeline-events: 96 types (event algebra, sink interfaces).
//   - pipeline-credentials-api: credentials SDK (SecretPatternRegistry, etc.).
// BCV guards MORE modules than are published, and the two questions are different. `bcvModules`
// answers "which internal ABI do I want to see reviewed when it changes"; `publishedContractModules`
// below answers "which artifact can an external consumer resolve". `pipeline-step-sdk:api` and
// `pipeline-credentials-api` are in the first list and not the second, and that is measured, not
// assumed: neither applies `maven-publish`, so neither resolves as a Maven coordinate, and
// `examples/` imports nothing from `dev.rubentxu.pipeline.v2.sdk`.
// This comment used to read "pipeline-step-sdk:api: SDK contract for external plugin authors". That
// was false, and it was the same defect this session already paid for once in the KSP: a written
// claim that no evidence backs, pointing a future reader at a consumer program that does not exist.
// It also called this a four-module list while the set below has carried seven since the publication split.
// The consequence for a break: a BCV-only module gets no maturity classification and no exception
// receipt, because it has no consumer program that could break silently. Its internal callers are
// inside this build, so a removed public type fails `compileKotlin` immediately and loudly. What
// the dump buys is the review — a diff nobody has to be shown.
// Adding a new module to BCV requires two steps: (1) append its name to
// `bcvModules` below; (2) run `:pipeline-<x>:apiDump` to materialise the
// baseline, then commit.api file alongside the build change.
val bcvModules = setOf(
    "pipeline-domain",
    "pipeline-events",
    "pipeline-step-sdk:api".removePrefix(":"), // resolved below by project.path
    "pipeline-credentials-api",
    // The published output read contract. Added because `:pipeline-output` became a
    // PUBLISHED artifact, and the dump is what freezes exactly which types a consumer resolves.
    // Without it, adding a public type to the output plane would change the published surface with
    // nothing failing: no diff to review, and no `apiCheck` to refuse it.
    // `:pipeline-output-store` and `:pipeline-events-store` are deliberately NOT here. BCV protects
    // a published ABI; those modules are not published, and a baseline over an internal
    // implementation is maintenance with no consumer behind it.
    "pipeline-output",
    // Already PUBLISHED (the external plugin compiles against it) and
    // therefore already had no ABI guard. Found while running `apiCheck` across the published set:
    // the task did not exist. Guarding three published contracts and leaving the fourth free would
    // have been the inconsistency, not the fix.
    "pipeline-scripting-api",
    // Runtime ports publication. The new `:pipeline-runtime-api` module
    // (carved out of `:pipeline-runtime`) carries the publish/subscribe
    // ports and their ADTs. Guiding the published ABI here so `apiCheck` refuses an
    // unannounced surface change.
    "pipeline-runtime-api",
)

subprojects {
    val isBcvModule = bcvModules.any { project.path == ":$it" || project.path.endsWith(":$it") }
    if (isBcvModule) {
        pluginManager.apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        // Wait until the Kotlin JVM plugin has created the
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

// ── external plugin reproducibility ─────────────────────────────────────────────
// example-uppercase-plugin is an INDEPENDENT Gradle build (its own settings file),
// not a subproject of the build: that independence is the property it certifies. It
// compiles against SDK artifacts, so those artifacts must be produced from THIS
// revision and handed to it explicitly.
// Before this, the plugin consumed committed libs/*.jar snapshots (silent drift)
// and its own jar had no producing task anywhere in the build, so:pipeline-application
// could not compile from a clean checkout.
val sdkRepoDir = layout.buildDirectory.dir("sdk-repo")

/**
 * The modules whose contract is PUBLISHED, as an explicit list.
 * The publication split turned this from two entries into the published set. An external consumer — `pipelinek-fabric`
 * first of all — has to resolve these as ordinary Maven coordinates produced from THIS source
 * revision, with no source or composite dependency on this repository.
 * The list is here, and not spelled out one task per module, so that "what is published" is a
 * single readable sentence. A module that is not on it is not published, and its absence is a
 * decision rather than an oversight: `:pipeline-output-store` and `:pipeline-events-store` carry
 * the filesystem, JDBC and replay implementations precisely so that they CANNOT be on it.
 * A module joins by being appended here AND applying `maven-publish` with a `sdk` publication AND
 * declaring every dependency that reaches its public ABI as `api`. All three, because each of the
 * other two without this one is a publication whose POM cannot compile a consumer.
*/
val publishedContractModules = listOf(
    "pipeline-domain",
    "pipeline-scripting-api",
    // The event contract (envelope, cursor, read/paging) and the output read contract.
    "pipeline-events",
    "pipeline-output",
    // The runtime publish/subscribe ports. The four existing entries
    // (read-side contracts for events, output, scripting, and domain) gave consumers a
    // complete read plane; this fifth entry adds the write plane, and the BOM picks up the
    // matching `api(...)` constraint (see pipelinek/pipeline-sdk-bom/build.gradle.kts).
    // This comment used to name `PublishedContractBoundaryFitnessTest` as the guard on the
    // size of this list, and pointed at its source under
    // `pipelinek/pipeline-architecture-tests/`. That fitness was deleted on with the rest
    // of the QA harness, so the list above now has NO automated guard on its membership.
    // `apiCheck` proves the published surface is internally consistent; it does not prove the
    // list is the right list. Anyone adding a sixth published contract must decide here, in
    // review, whether it earns publication.
    "pipeline-runtime-api",
)

/**
 * The SDK BOM, published alongside the five contracts and deliberately OUTSIDE
 * [publishedContractModules].
 * The two lists answer different questions. `publishedContractModules` is the set of artifacts whose
 * TYPE surface a consumer compiles against and whose bytes `apiCheck` freezes — and it must stay
 * exactly the five. `sdkBomModules` is the set
 * of published RESOLUTION documents: `:pipeline-sdk-bom` carries no classes, no ABI and no tests, so
 * adding it to the contract list would be a claim that it has a surface to guard. It does not; it
 * has five version constraints derived from `rootProject.version`.
 * It is published by the same task, into the same `sdk-repo`, from the same revision, so a consumer
 * resolving the platform and the coordinates finds a consistent set or finds nothing.
*/
val sdkBomModules = listOf(
    "pipeline-sdk-bom",
    // The runtime API module is also in the BOM, so a consumer that
    // pins the platform version pulls the runtime ports automatically. The BOM is the
    // single resolution document, and leaving the runtime ports out of it would force
    // every consumer to declare the version independently.
    "pipeline-runtime-api",
)

val publishSdkForExternalPlugin by tasks.registering {
    group = "build"
    description = "Publishes the published-contract artifacts an external consumer compiles against."
    dependsOn(publishedContractModules.map { ":$it:publishSdkPublicationToSdkRepository" })
    // The BOM is published by this same task, not by a second mechanism. Its presence here
    // (and its absence from `publishedContractModules`) is the whole "convenience, not contract"
    // distinction, expressed in the build rather than only in a comment.
    dependsOn(sdkBomModules.map { ":$it:publishSdkPublicationToSdkRepository" })
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
    // The plugin now contributes an event definition and
    // emits it through the published emission seam, so it compiles against that contract too.
    // Omitting it from these inputs would let the task consider its output after a
    // change to the Event Plane — and the plugin would then be shipping against a stale seam.
    inputs.files(":pipeline-domain:jar", ":pipeline-scripting-api:jar", ":pipeline-events:jar")
    // Declare rootProject.version as an explicit input. See
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
        // failure mode observed was caused by this omission: the example
        // build re-resolved the SDK from scratch, hit the default version, and could
        // not find the module.
        "-PsdkVersion=" + rootProject.version.toString(),
        "jar",
    )
}

// The external plugin is now the SUBJECT of a test:pipeline-application, and a test
// whose subject is optional is a test that passes by omission. `the artifact really carries a
// manifest and all four ServiceLoader descriptors` would report the absence of every family as a
// clean sheet rather than as a missing build.
// The cost is real and is stated rather than discovered later: every `check` now publishes to
// sdk-repo and forks a second Gradle against the working tree, which is exactly what the three
// external builds below were kept out of `check` to avoid. It is paid here because the other
// tasks produce artifacts nothing under `check` inspects, while this one produces the artifact
// that decides whether the plugin contract is real.
// FAST LANE : the subject test now lives in `:pipeline-application`'s
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
// The `withPlugin` hook this block used is gone with it. The BCV block below hooks the
// same way for the same reason (the root script is evaluated before the Kotlin JVM plugin
// creates the subproject's tasks).

// The Saga consumer's analogue. It is the external proof that the Saga
// contract is reachable from OUTSIDE the build, through the published
// capabilities, with the engine owning the state: the scenarios drive a
// compensation loop with a plugin that knows nothing of the runtime's
// internals.
val buildExampleSagaPlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external Saga consumer against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("../examples/example-saga-plugin")
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

// The directive analogue of buildExamplePlugin. The external directive
// plugin proves the directive kernel is open by key from outside the build.
val buildExternalDirectivePlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external directive plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("../examples/example-directive-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    inputs.files(":pipeline-domain:jar")
    // Declare rootProject.version as an explicit input. See
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

// The BODY-bearing analogue. This plugin is the proof that the
// Corrected claim holds from outside the build: its handler drives its own body
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
    // Declare rootProject.version as an explicit input. The
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

// Stage the four external example
// plugin JARs into the application distribution so the releaseGate (and
// DistributionMandatoryPluginsTest) can observe them.
// The example plugins are independent builds outside the build root, so they cannot
// be wired as project(":example-block-plugin") dependencies. The
// staging task copies the built JARs into pipeline-application/lib/ so
// the distZip and installDist both pick them up alongside the four
// official:pipeline-step-sdk plugins.
// Each input is a single external JAR, declared as a file. The
// external build must run first (the dependsOn below), and the
// inputs force re-staging if the JARs change.
val stageExternalExamples by tasks.registering {
    group = "distribution"
    description = "Stage the four external example " +
        "plugins (block, directive, uppercase, saga) into pipeline-application/lib/ so " +
        "the distribution ZIP carries them as bundled plugins."

    dependsOn(buildExamplePlugin, buildExternalDirectivePlugin, buildExampleBlockPlugin, buildExampleSagaPlugin)

    val appLibDir = file("pipeline-application/build/install/pipelinek/lib")
    val targets = listOf(
        file("../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar") to "example-uppercase-plugin-0.1.0.jar",
        file("../examples/example-directive-plugin/build/libs/example-directive-plugin-0.1.0.jar") to "example-directive-plugin-0.1.0.jar",
        file("../examples/example-block-plugin/build/libs/example-block-plugin-0.1.0.jar") to "example-block-plugin-0.1.0.jar",
        file("../examples/example-saga-plugin/build/libs/example-saga-plugin-0.1.0.jar") to "example-saga-plugin-0.1.0.jar",
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

// The:pipeline-application:distZip task (and the installDist task it
// depends on) must run AFTER the external JARs are built, because
// pipeline-application/build.gradle.kts declares them as `runtimeOnly`
// files. The implicit file dependency makes Gradle fail the build
// with "Declare an explicit dependency... using Task#mustRunAfter"
// unless we declare the ordering here. Once the external JARs
// exist, `installDist` stages them into `lib/` and `distZip` packages
// the lib directory.
// `mustRunAfter` is necessary but not sufficient: Gradle would still
// See installDist as if the staged JARs are not declared as
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
        "example-saga-plugin-0.1.0.jar",
    )
    val externalBuildTasks = listOf(
        "buildExamplePlugin",
        "buildExternalDirectivePlugin",
        "buildExampleBlockPlugin",
        "buildExampleSagaPlugin",
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
