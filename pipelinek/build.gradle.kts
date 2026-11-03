import kotlinx.validation.KotlinApiBuildTask
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
//
// 2026-10-23: the 0.62.0 that stood here could not be published: it collides
// with the published v0.62.0, which is a DIFFERENT artifact (the RESET
// re-founds the product identity). The published train closed at v0.75.0 on
// 2026-10-22, from the lineage this tree absorbed. This tree carries that
// work plus the whole integration, so the 0.76.0 train opens here
// (ProductVersion 0.76.0) and the next candidate can be tagged v0.76.0-rc1
// with PRODUCT IDENTITY LAW across all five surfaces.
// 2026-10-23: the 0.76.0 train closed at v0.76.0-rc1, and the same law that
// kept 0.62.0 from being published applies here: a version that names two
// different artifacts names nothing. The capability catalogue moved to the
// pipelinek.capabilities/2 wire format, so the 0.76.0-rc1 artifact and this
// one do NOT behave the same, and one version cannot carry both. The 0.77.0
// train opens here (ProductVersion 0.77.0) and its first candidate can be
// tagged v0.77.0-rc1.
// 2026-10-23: the 0.77.0 train closed at v0.77.0-rc1, and the law holds: this
// tree publishes an operation's identity where the previous one published a
// folded projection of it, so the two artifacts do NOT behave the same. The
// 0.78.0 train opens here (ProductVersion 0.78.0) and its first candidate can
// be tagged v0.78.0-rc1.
// 2026-10-23: the 0.78.0 train closed at v0.78.0-rc1, and the law holds: this
// tree can now refuse a transcript for two different reasons, and the record of
// the failure says which one, so the two artifacts do NOT behave the same. The
// 0.79.0 train opens here (ProductVersion 0.79.0) and its first candidate can
// be tagged v0.79.0-rc1.
// 2026-10-24: the 0.89.0 train closed at v0.89.0-rc1, and the law holds: this
// tree creates the directory that holds a run's event store instead of refusing
// it, and answers a relative store path with the directory it lives in instead
// of claiming it has none, so the two artifacts do NOT behave the same. The
// 0.89.0 train opens here (ProductVersion 0.89.0) and its first candidate can
// be tagged v0.89.0-rc1.
// 2026-10-24: the 0.89.0 train closed at v0.89.0-rc1, and the law holds here
// too: the optional fields of the event contract now have ONE authority that the
// writer and the decoder both read, so a field cannot be written under one name
// and read under another; and the durable launch returns the transcript state it
// produced instead of leaving it in fields that a later, separate step reads
// back. The two artifacts do NOT behave the same. The 0.90.0 train opens here
// (ProductVersion 0.90.0) and its first candidate can be tagged v0.90.0-rc1.
// 2026-10-26: the 0.90.0 train closed at v0.90.0, published from the bytes of
// v0.90.0-rc3, and the law holds here: this tree has moved since that
// candidate — the telemetry and the script-identity work that followed it, and
// now the three recovery invariants of the first milestone: the recover no
// longer takes the run's lease on a dry run, the re-attach deadline is an
// instant read from the adapter's own clock instead of one computed from a
// fencing token, and a replay cursor that cannot be READ is refused as a
// storage failure instead of read as an absent one. The two artifacts do NOT
// behave the same, so one version cannot carry both. The 0.91.0 train opens
// here (ProductVersion 0.91.0) and its first candidate can be tagged
// v0.91.0-rc1.
// 2026-10-30: the 0.91.0 train closed at v0.91.0, and the law holds here
// too: this tree has moved since that candidate. The closures of PKQ.M0
// through PKQ.M6 give the lease ownership, the event feed, the durable
// engine, the CLI and the Output Plane one owner each, so what this
// artifact does is not what v0.91.0 did; the two artifacts do NOT behave
// the same, and one version cannot carry both. The 0.92.0 train opens here
// (ProductVersion 0.92.0) and its first candidate can be tagged v0.92.0-rc1.
version = "0.92.0"

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
            // Each detekt task reads the source set it belongs to: `detektMain`
            // reads `src/main/kotlin` and `detektTest` reads `src/test/kotlin`.
            // Measured rather than assumed: an unused import added to a test file
            // failed `detektTest` naming that file, its line and the rule, and the
            // report counted the test files alone. So the `source` below does not
            // widen `detektTest` to the main sources, and the test sources are
            // reached by their own task.
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

// The modules whose unit tests carry enough work to be worth a second JVM.
//
// The parallelism this build already has is BETWEEN the modules: `org.gradle.parallel`
// runs the modules' test tasks side by side, so the test stage is already using the
// machine's cores. This setting is the parallelism INSIDE one module: the test classes
// of that module are spread over several JVMs.
//
// A JVM is not free. Starting one costs about a second of classloading before a
// single test runs, so a module whose whole suite takes less than that gains nothing
// and pays the entire cost. The list below is the modules whose measured test work is
// above that line.
//
// What it buys, measured here as the median of three runs of ONE module's test task
// (warm daemon, no build cache): the application module goes from 5.65 s to 4.68 s,
// the events store from 4.00 s to 3.42 s, the domain from 3.39 s to 2.96 s, the
// credentials from 3.82 s to 3.59 s and the scripting module from 9.80 s to 9.54 s.
// Four forks measured fastest in the module with the largest volume, where one, two,
// four and eight were tried: 5.65 s, 4.99 s, 4.68 s and 4.84 s.
//
// What it does NOT buy is a faster whole stage, where every module's tests run at
// once: that stage is already saturated by the modules running side by side, and extra
// JVMs inside them only add startup work that competes for the same cores. That is
// why the setting is scoped to the modules that carry volume instead of being switched
// on everywhere.
//
// It is only safe because no test in this tree captures a global. The standard output
// of the JVM is a global: two classes running side by side that both capture it steal
// each other's bytes, and the assertion that read them reads the other's. Every verb
// that answers something takes the streams it writes to as arguments, and the tests
// hand it streams of their own, so the bytes a test reads are the bytes that verb
// wrote to the stream it was given.
val modulesWithTestVolume = setOf(
    "pipeline-application",
    "pipeline-credentials-local",
    "pipeline-domain",
    "pipeline-events-store",
    "pipeline-scripting-kotlin24",
)

subprojects {
    // Expose the repository root to tests that must read files from the repository.
    tasks.withType<Test>().configureEach {
        systemProperty("pipeline.repoRoot", repositoryRoot)
        if (project.name in modulesWithTestVolume) {
            maxParallelForks = 4
        }
    }
}

// The JDK the product is compiled with.
//
// The build used to inherit whatever `java` the machine happened to point at,
// and that made the build depend on the room rather than on the project: a
// JDK the Kotlin compiler cannot even parse its own version number fails the
// compilation with a message that names no source file and no rule, and the
// failure arrives only when a source file actually changes, because an
// unchanged tree is served from the cache and never reaches the compiler.
//
// So the toolchain is DECLARED, the way the version is declared: one place
// that says which JDK the product is built with, and the build finds it
// wherever the machine keeps it. A machine whose `java` is newer, older or
// simply different produces the same bytes.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }
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
//
// TWO directories, and the difference between them is the point:
//
//   - `sdk-repo` is where the publication LANDS: the modules publish their
//     artifacts there, and the release builder packages that directory into the
//     SDK ZIP the product ships.
//   - `sdk-repo-published` is what the external consumers RESOLVE from: the
//     published ZIP, unpacked.
//
// The first is an intermediate step of the release and the second is the
// artifact's own face. A consumer that resolved from the first would be
// resolving from something the product never ships, and the published SDK
// would go unconsumed by anything but the verifier.
//
// The landing directory is owned by `:pipeline-release`, which is where the
// publication and the ZIP builder live.

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

// ── the second ABI reading: the candidate against the PREVIOUSLY PUBLISHED artifact ──
//
// `apiCheck` compares the tree against its OWN committed baseline
// (`<module>/api/<module>.api`). That baseline is a file in this repository, so a
// rewritten baseline makes `apiCheck` green while the published surface breaks: the
// gate would be certifying the rewrite instead of the artifact.
//
// The second reading compares two PUBLISHED artifacts: the candidate this release
// publishes, and the artifact the previous release published. Neither side is the
// committed baseline, so no rewrite of a file in this tree can satisfy it.
//
// The dump is produced by the SAME engine that produced the tree's baseline: the
// binary-compatibility-validator plugin's own `KotlinApiBuildTask`, asked to read a
// JAR instead of a classes directory. Measured on 0.18.2: the plugin ships no
// command-line entry point, so its Gradle task is the only route to an arbitrary
// jar's surface; `inputJar` is `@Optional`, and the worker reads the jar through
// `inputJar` alone without consulting `inputDependencies`.
//
// Two properties a text diff between two `.api` files would not have:
//   - both sides are rendered by the same engine in the same build, so a change in
//     the validator's own output format cannot invent a break;
//   - the comparison never reads the committed `.api`, which is the one file a
//     rewrite would move.
//
// The sides are named by Gradle properties because the release script knows which
// published artifacts it is comparing and the build does not:
//   -PpreviousArtifactRepo=<maven repo>  -PpreviousArtifactVersion=<version>
//   -PcandidateArtifactRepo=<maven repo> -PcandidateArtifactVersion=<version>
// A missing property means "there is no artifact on that side", NOT "an empty
// surface": the dump task is skipped and the comparison reports that there is
// nothing to compare, instead of writing an empty dump that would read as a total
// removal of the module's ABI.

/**
 * The Maven layout of a published artifact: the group is one for every published
 * module, and the `sdk` publications declare no `artifactId`, so Gradle derives it
 * from the project name.
 */
val PUBLISHED_GROUP_PATH = "dev/rubentxu/pipeline/v2"

/**
 * The two sides of the comparison and the Gradle properties that name them. The
 * side name is also the task-name infix and the dump-directory prefix, so one
 * spelling travels through the whole chain.
 */
val publishedApiSides = mapOf(
    "candidate" to ("candidateArtifactRepo" to "candidateArtifactVersion"),
    "previous" to ("previousArtifactRepo" to "previousArtifactVersion"),
)

/**
 * A short digest of a string. The dump directory carries one so that two different
 * repositories at the SAME version cannot share a dump: a file left behind by a
 * repository that had the artifact must not be read as the artifact of a repository
 * that does not have it.
 */
fun shortDigestOf(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }

/**
 * The repository and the version of each side, read ONCE from the Gradle
 * properties.
 *
 * Read twice — once here for the dump directory and once inside the
 * subproject that dumps — the two readings would be two answers to the same
 * question, and a comparison whose sides came from different answers would
 * compare artifacts that were never named together.
 */
val publishedApiCoordinates: Map<String, Pair<String?, String?>> =
    publishedApiSides.mapValues { (_, properties) ->
        providers.gradleProperty(properties.first).orNull to
            providers.gradleProperty(properties.second).orNull
    }

/**
 * The dump directory of each side. Computed ONCE, here, and handed to the
 * comparison as an argument: computed in two places it would be two answers.
 */
val publishedApiDumpDirs: Map<String, String> = publishedApiCoordinates.mapValues { (side, coordinate) ->
    val (repo, version) = coordinate
    val name = if (repo == null || version == null) {
        "$side-none"
    } else {
        "$side-$version-${shortDigestOf(File(repo).absolutePath)}"
    }
    layout.buildDirectory.dir("api-artifacts/$name").get().asFile.absolutePath
}

// The geometry of the comparison, handed to `:pipeline-release` — the module
// that decides — so that the build script and the decision agree on which
// artifacts, which versions and which modules are being compared. The
// alternative, each side recomputing it, is the second authority this task
// exists to remove.
extra["publishedApiDumpDirs"] = publishedApiDumpDirs
extra["publishedApiVersions"] = publishedApiCoordinates.mapValues { it.value.second }
extra["publishedApiModules"] = publishedContractModules

subprojects {
    val publishedModule = publishedContractModules.firstOrNull { ":$it" == project.path }
    if (publishedModule != null) {
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            // The engine that produced this tree's committed baseline. Copying its
            // worker classpath is what makes the two sides comparable: the same
            // renderer, in the same build.
            val apiBuildTask = tasks.named<KotlinApiBuildTask>("apiBuild")
            for ((side, coordinate) in publishedApiCoordinates) {
                val (repo, version) = coordinate
                val artifact = if (repo == null || version == null) {
                    null
                } else {
                    File(
                        repo,
                        "$PUBLISHED_GROUP_PATH/$publishedModule/$version/$publishedModule-$version.jar",
                    )
                }
                val outputFile = File(publishedApiDumpDirs.getValue(side), "$publishedModule.api")
                tasks.register<KotlinApiBuildTask>("${side}ArtifactApiDump") {
                    group = "verification"
                    description =
                        "Dump the public API surface of the $side published artifact of $publishedModule."
                    runtimeClasspath.setFrom(apiBuildTask.map { it.runtimeClasspath })
                    if (artifact != null && artifact.isFile) {
                        inputJar.set(artifact)
                        outputApiFile.set(outputFile)
                    } else {
                        // There is no artifact to read on this side. Skipped, not empty: an
                        // empty dump would read as a total removal of the module's ABI.
                        enabled = false
                    }
                }
            }
        }
    }
}

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

// The SDK the external consumers resolve from: the PUBLISHED artifact.
//
// The four external plugins below are the only real consumers of the public
// contracts in this tree, and they used to resolve from `build/sdk-repo` —
// a directory on the way to the ZIP. That made the ZIP, which is what the
// product PUBLISHES for the plugins that build against the contracts, an
// artifact nobody ever resolved. A change to its layout, to the group path,
// to the BOM or to the Gradle metadata would have been published, release
// after release, with a green gate: the gate proved the tree was healthy and
// never asked whether the thing it shipped could be used.
//
// The published artifact is now the only SDK these consumers see. The
// guarantee is not a sentence in a comment: the external builds resolve from
// the unpacked ZIP, so a ZIP that cannot be resolved stops the distribution
// build, and the failure names the resolution that failed.
//
// The unpacked repository is a DIRECTORY again, because that is what a Maven
// repository is to a resolver: the ZIP is the transport, the layout is the
// contract, and the layout is what a consumer resolves against.
val publishedSdkRepoDir = layout.buildDirectory.dir("sdk-repo-published")

val unpackPublishedSdk by tasks.registering(Sync::class) {
    group = "build"
    description = "Unpacks the published SDK ZIP into the Maven repository the external consumers resolve from."

    dependsOn(":pipeline-release:sdkZip")

    val sdkZip = project(":pipeline-application").layout.buildDirectory
        .file("distributions/pipelinek-${project.version}.sdk.zip")
    from(zipTree(sdkZip))
    into(publishedSdkRepoDir)
}

// The FOUR external example plugins are built by ONE task, and the four
// STANDALONE builds they live in are built CONCURRENTLY.
//
// What this used to be: four `Exec` tasks, one per plugin, each forking its
// own `gradlew -p <pluginDir>` against the published SDK. Four tasks of the
// SAME project never run in parallel, so those four nested builds ran one
// after another — four client starts, four daemon negotiations, and no
// concurrency between them.
//
// What it is now: one `Exec` task that forks ONE Gradle invocation over the
// composite in `examples/example-plugins`. That composite INCLUDES the four
// standalone plugin builds — they are still the ROOT of their own build, which
// is what the open-world claim they certify depends on — and asks for their
// `jar` task. Included builds of a composite are independent projects of ONE
// build, so Gradle builds them CONCURRENTLY.
//
// MEASURED on this tree (2026-11-03), six trials each, alternating so that a
// drifting machine cannot favour one variant, every trial from scratch (the
// plugins' build directories deleted before it):
//
//   four nested builds in series: 40,7 / 13,1 / 12,6 / 12,0 / 11,9 / 11,4 s
//   one composite invocation:    10,3 /  8,2 /  6,4 /  6,4 /  6,6 /  6,1 s
//
// The first trial of each sequence pays for starting the Gradle daemon, so the
// STEADY STATE is the last five of each: in series, 11,4 s to 13,1 s (median
// 11,9 s); in composite, 6,1 s to 8,2 s (median 6,4 s). The composite saves
// about 5,6 s — roughly 47% — on the work it replaces, and it produces the same
// four JARs, byte for byte.
//
// WHEN that cost is paid is stated rather than assumed. The published SDK is
// this task's INPUT, and the published SDK is REPRODUCIBLE: `gradlew clean`
// followed by a rebuild produces a byte-identical repository, so this task
// stays UP-TO-DATE and the nested build does not run at all (measured: the four
// former tasks reported UP-TO-DATE after a clean, in 3,3 s of configuration).
// The cost above is paid when the published SDK CONTENT changes, or when a
// plugin source changes: exactly the moments when a developer is changing the
// contracts these plugins compile against.
val buildExamplePlugins by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the four independent external example plugins against this revision's SDK, concurrently."
    dependsOn(unpackPublishedSdk)

    val compositeDir = file("../examples/example-plugins")
    val pluginDirs = listOf(
        file("../examples/example-uppercase-plugin"),
        file("../examples/example-directive-plugin"),
        file("../examples/example-block-plugin"),
        file("../examples/example-saga-plugin"),
    )
    pluginDirs.forEach { pluginDir ->
        inputs.dir(pluginDir.resolve("src"))
        inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    }
    // The composite's own files are inputs too: the aggregation IS part of the
    // build that produces the JARs, and editing it has to re-run that build.
    inputs.files(compositeDir.resolve("settings.gradle.kts"), compositeDir.resolve("build.gradle.kts"))
    // The published SDK is this build's INPUT, and it is declared as one.
    //
    // The plugin's output depends on the SDK it compiles against, not on the
    // repository's timestamped snapshot filenames. The plugin contributes an
    // event definition and emits it through the published emission seam, so it
    // compiles against that contract too, and omitting the SDK from these
    // inputs would let the task consider its output after a change to the
    // Event Plane — the plugin would then be shipping against a stale seam.
    //
    // What is declared is the PUBLISHED repository, not the modules' jars:
    // the published artifact is what a consumer resolves from, and a change
    // to it — a contract that moved, a BOM that lost a constraint — has to
    // re-run this build. A build that skips the resolution because nothing
    // it remembers changed would keep the plugin it compiled against the
    // previous SDK, and the resolution would never happen again.
    inputs.dir(publishedSdkRepoDir)
    // Declare rootProject.version as an explicit input. The example plugins'
    // Gradle property `-PsdkVersion=` is derived from this value at
    // commandLine time, but Gradle's default fingerprint for an Exec task does
    // NOT see the version string when only the SDK jar content is declared as
    // an input. A bump from 0.59.0 to 0.60.0 with no source change previously
    // left the example plugin's apiRange at [0.59.0, 0.60.0), which the
    // shipped installDist then refused as IncompatibleApiRange. Without this
    // property declared, every `pipelinek run` against that installDist
    // exited 2 BEFORE the run body compiled.
    inputs.property("rootProject.version", rootProject.version.toString())
    // Each plugin's JAR is an output of this task.
    //
    // Gradle does NOT fail a task whose declared output was never produced. MEASURED
    // on this tree (2026-11-03): a composite that included three of the four builds
    // left the whole build SUCCESSFUL with the fourth JAR absent. So the check
    // below is not a redundancy — it is the only thing that stands between a
    // composite that quietly builds three plugins and a distribution that ships
    // three plugins and calls it four.
    val pluginJars = pluginDirs.map { pluginDir -> pluginDir.resolve("build/libs/${pluginDir.name}-0.1.0.jar") }
    outputs.files(pluginJars)

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", compositeDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + publishedSdkRepoDir.get().asFile.absolutePath,
        // Pass the SDK version the external plugin must resolve. Without this the
        // plugin's default sdkVersion is 0.1.0-SNAPSHOT, which is not in the local
        // sdk-repo (it carries the root project version). The --rerun-tasks
        // failure mode observed was caused by this omission: the example
        // build re-resolved the SDK from scratch, hit the default version, and could
        // not find the module. The property reaches the four included builds
        // because Gradle propagates command-line project properties to every
        // build of a composite.
        "-PsdkVersion=" + rootProject.version.toString(),
        "buildExamplePlugins",
    )

    doLast {
        val missing = pluginJars.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "buildExamplePlugins: the composite did not produce " +
                    "${missing.joinToString(", ") { it.name }}. The composite in " +
                    "${compositeDir.absolutePath} must include every example plugin build; " +
                    "a build that reports success while shipping a missing plugin is not " +
                    "a build.",
            )
        }
    }
}

// The external plugin builds are kept OUT of `check` and out of `:test`, and
// this is the note that says why.
//
// The subject of the plugin certification used to be a test in
// `:pipeline-application`, and a test whose subject is optional is a test that
// passes by omission: `the artifact really carries a manifest and all four
// ServiceLoader descriptors` reported the absence of every family as a clean
// sheet rather than as a missing build. The `pluginContractTest` lane that
// carried that certification was REMOVED (see "TEST LANES REMOVED" in
// `pipeline-application/build.gradle.kts`), together with the `doFirst`
// invariant that refused to let `:test` depend on the external plugin builds:
// its subject was a lane split that no longer exists.
//
// What survives is the constraint this build still honours: the external plugin
// builds do not re-enter the RESOLVED `check`/`test` graph. The FAST LANE
// contract in `pipeline-application/build.gradle.kts` says the same thing from
// the other side, and the two files can be read in isolation without the
// contradiction being visible — which is why the constraint is stated in both.
//
// The `withPlugin` hook this block used is gone with it. The BCV block below
// hooks the same way for the same reason (the root script is evaluated before
// the Kotlin JVM plugin creates the subproject's tasks).

// WHY THERE ARE FOUR PLUGINS. Each one is a DIFFERENT proof, and each one is
// built as an INDEPENDENT Gradle project on purpose: a plugin that needed
// anything from pipeline-application would make the open-world claim it
// certifies false.
//
//   * uppercase — the plugin contract is reachable at all from outside.
//   * directive — the directive kernel is open by key from outside the build.
//   * block     — the handler drives its own body through the bound
//                  continuation, compiled against the public SDK only; the
//                  Corrected claim holds from outside the build.
//   * saga      — the Saga contract is reachable from OUTSIDE the build,
//                  through the published capabilities, with the engine owning
//                  the state: the scenarios drive a compensation loop with a
//                  plugin that knows nothing of the runtime's internals.
//
// Their BUILD is one task (above) and their PROOF is four different claims.
// The composite changes WHEN they are built, not WHAT they are: the four JARs
// stay four JARs, and the distribution still ships four plugins.

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

    dependsOn(buildExamplePlugins)

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
        "buildExamplePlugins",
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
    // The test task of the application consumes the SAME four JARs, because
    // `runtimeOnly` is inherited by `testRuntimeOnly`: the JARs are on the
    // runtime classpath of the tests as well. The dependency on the task that
    // produces them is therefore just as real here as it is for the
    // distribution tasks above.
    //
    // It was NOT declared, and nothing noticed. The gate used to run the tests
    // in an invocation of their own, where the producer of the JARs was not in
    // the graph, so the undeclared dependency stayed invisible: the JARs were
    // already on disk, left there by the invocation of the build, and the
    // tests found them. ONE invocation that carries the build AND the tests
    // puts the producer and the consumer in the same graph, and Gradle refuses
    // to run them: "Task ':pipeline-application:test' uses this output of task
    // ':buildExamplePlugins' without declaring an explicit or implicit
    // dependency". The dependency is declared here, which is what the ordering
    // needed in the first place: without it the tests could start before the
    // JARs existed.
    //
    // The staged copies in `lib/` are NOT declared as inputs of the test task.
    // The tests read the originals, which are the output of
    // `:buildExamplePlugins`; the staged copies are the output of
    // `:pipeline-application:installDist`, and declaring them here would trade
    // one undeclared dependency for another.
    appProject.tasks.matching { it.name == "test" }.configureEach {
        dependsOn(":buildExamplePlugins")
    }
}
