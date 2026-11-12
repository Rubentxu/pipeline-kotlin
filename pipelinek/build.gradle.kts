import kotlinx.validation.KotlinApiBuildTask
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    // Binary-compatibility-validator, declared at the root project and applied
    // per-module via subprojects {... } below. See comments in
    // the application block for the phased rollout scope.
    alias(libs.plugins.binary.compatibility.validator) apply false
    // Dokka. Declared at the root project and applied per-module via
    // subprojects {... } below, to the modules whose public surface is frozen.
    // It generates the HTML reference of the published API, and no task of the
    // gate depends on it: the reference is asked for when someone wants it.
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.dokkaJavadoc) apply false
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
// 2026-11-04: the 0.92.0 train closed at v0.92.0-rc1, and the law holds here
// too: this tree has moved since that candidate. The published ABI of
// :pipeline-credentials-api changed: the constructor of CredentialScope no
// longer takes the list of the credentials that are active in the scope, a
// parameter the scope never read. A consumer that built a scope has to build
// it differently, so the two artifacts do NOT behave the same and one version
// cannot carry both. The 0.93.0 train opens here (ProductVersion 0.93.0) and
// its first candidate can be tagged v0.93.0-rc1.
// 2026-11-07: the 0.93.0 train closed at v0.93.0-rc1, and the law holds here
// too: this tree has moved since that candidate. The published ARTIFACT does
// not behave the same, and it is measured on the real distribution, hot, with
// JDK 25: the JVM no longer writes to the product's output, so `version`
// prints "pipeline 0.93.0" and nothing else where it used to print three JVM
// lines, `version --json` parses where it used to raise JSONDecodeError, and
// `doctor` no longer puts the JVM's lines in front of its facts. A consumer
// that parses that output has to read something else, so the two artifacts do
// NOT behave the same and one version cannot carry both. And the promotion
// reads a different document: the certification of promotion declares the
// shape `pipelinek-promotion-certification-2`, which carries the reason of
// the part that did not apply and REQUIRES it, while the shape that came
// before stays closed. The 0.94.0 train opens here (ProductVersion 0.94.0)
// and its first candidate can be tagged v0.94.0-rc1.
// 2026-11-08: the 0.94.0 train closed at v0.94.0, and the law holds here
// too: this tree has moved since that candidate. The published ARTIFACT does
// not behave the same, and it is measured on the real distribution: the
// launcher of the 0.94.0 artifact carries `-Xlog:cds=off`, which mutes the
// `cds` TAG but not the {cds,dynamic} tag set, so the JVM's talk can still
// reach the stream the product answers on. This tree's launcher carries
// `-Xlog:all=off:stdout` followed by `-Xlog:all=warning:stderr`: the two
// options are ONE decision (without the first, HotSpot's default stdout
// selection stays alive and the warning appears on BOTH streams), the
// product's stdout carries the answer and nothing else, and the JVM's
// diagnostics go to stderr, where a diagnostic belongs. Measured on the same
// JVM, with the same archive that cannot be loaded: the 0.94.0 options leave
// stdout at 15 bytes and stderr at 0 (the JVM's complaint is muted, and that
// muting is incomplete); these options leave stdout at 15 bytes and stderr at
// 80, with the complaint visible where it can be acted on. A consumer that
// parses the product's output can rely on that in this artifact and could not
// rely on it in v0.94.0, so the two artifacts do NOT behave the same and one
// version cannot carry both. The 0.94.1 train opens here (ProductVersion
// 0.94.1) and its first candidate can be tagged v0.94.1-rc1.
// 2026-11-08: the 0.94.1 train closed at v0.94.1, and the law holds here
// too: this tree mutes what the previous one only MOVED. The published
// 0.94.1 artifact leaves the JVM's talk about the shared class archive on
// stderr, and the operator reads it on every start: four lines on the cold
// one ("Skipping jdk/internal/event/Event: JFR event class", "Not in loaded
// state") and three on the hot one under JDK 25 ("An error has occurred
// while processing the shared archive file", "Mismatched values for property
// jdk.module.enable.native.access", "Disabling optimized module handling").
// What they say is about a file the product owns and rewrites by itself, so
// there is nothing for the person running the command to do about them.
// This tree mutes the tag set {cds,dynamic} on both streams: the answer's
// stream carries the answer and nothing else, and the diagnostic stream
// carries only what somebody can act on. MEASURED on a distribution of this
// tree, with the archive deleted so the JVM has to write it and talk about
// it, and with the SAME distribution carrying the 0.94.1 options as the
// only variable: with the old options stderr is 350 bytes cold and 325 hot
// on JDK 25 (and 350 cold on JDK 21); with these options stderr is 0 bytes
// cold and hot on JDK 21, 24 and 25, stdout is 16 bytes ("pipeline
// 0.94.2") in every reading, and the archive is still written (4.9 MB) and
// still mapped. Whoever wants those lines asks for them with the options
// named after the product, which are added last and win: measured,
// PIPELINEK_OPTS="-Xlog:cds=warning:stderr" brings them back. The artifact a
// person reads is not the artifact that was published, so the two do NOT
// behave the same and one version cannot carry both.
// The archive STAYS, and its value is measured, because a console that the
// muting keeps clean does not need the archive to go: on this host (Temurin
// 25.0.4, `pipelinek version`, 30 runs after 5 warmups, two passes in opposite
// order, medians) the start is 329 ms with no shared archive at all, 250 ms
// with the JDK's standard archive and 170 ms with the archive this launcher
// writes. The archive is a third faster than the JDK's own and about twice as
// fast as none, on JDK 21, 24 and 25, so the logic that writes it and
// fingerprints it earns its place: dropping it would make every start about
// 80 ms slower to fix a console that the muting already fixes. The 0.94.2
// train opens here (ProductVersion 0.94.2) and its first candidate can be
// tagged v0.94.2-rc1.
// 2026-11-09: the 0.94.2 train closed at v0.94.2, and the law holds here
// too: this tree has moved since that candidate. The published ARTIFACT does
// not resolve to the same coordinate, and it is measured on what a consumer
// observes: the 0.94.2 artifact is published under the group
// `dev.rubentxu.pipeline.v2` and under the names `pipeline-domain`,
// `pipeline-events`, `pipeline-output`, `pipeline-runtime-api`,
// `pipeline-scripting-api` and `pipeline-sdk-bom`, so the coordinates it
// publishes carry the Kotlin package root and NOT the name of the solution.
// This tree publishes the same six contracts under the group
// `dev.rubentxu.pipelinek` and under the names `pipelinek-*`, and the SDK
// bundle it carries resolves under exactly the coordinates it prints: a
// consumer that reads `pipelinek sdk-info` and asks for what it says gets
// the artifact that carries that name. The two do NOT resolve to the same
// coordinate, so one version cannot carry both, and the 0.94.2 coordinates are
// WITHDRAWN rather than left standing as a second way to name the same
// contracts: this release withdraws the six packages from the GitHub Packages
// registry, and the deployment of 0.94.2 in Maven Central is dropped by hand
// in the portal, because the API of the publisher cannot drop a deployment
// that is already published. The 0.97.0 train opens here (ProductVersion
// 0.97.0) and its candidates can be tagged v0.97.0-rcN.
version = "0.97.0"

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

// The modules whose public ABI is frozen by a committed `.api` dump. The list
// lives here, next to the static analysis that also reads it, because TWO parts
// of this build have to agree on it: the ABI guard that freezes the surface
// (the binary-compatibility-validator section below) and the documentation
// rules that hold the published surface to its contract. One list, two readers.

val bcvModules = setOf(
    "pipeline-domain",
    "pipeline-events",
    "pipeline-step-sdk:api",
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
    // The service client's contract: the wire vocabulary of the local service and the typed
    // client that speaks it. The dump freezes exactly which types a consumer of the SDK
    // resolves, which is the whole point of publishing it.
    "pipeline-service-client",
    // The definition of a `.pipeline.kts`: the `@KotlinScript` class and its compilation
    // configuration. An IDE loads this class by name from the template marker, so a rename or
    // a removed declaration breaks every editor that resolves the artifact.
    "pipeline-scripting-definition",
)

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
        // The configuration this module is analysed with. The base applies to
        // every module; the ones whose public contract is published get the
        // overlay on top of it, which switches on the requirement that a public
        // type states its contract. The overlay is passed SECOND on purpose:
        // detekt merges the files it is handed and the last one wins.
        //
        // The membership test is the one the ABI guard uses, with the same list,
        // so the two cannot disagree about which modules carry a frozen
        // contract. The two guards read that contract from different sides —
        // this one from the sources, the ABI guard from the committed `.api`
        // dump — and the agreement between them is what says the published
        // surface is the one the sources describe.
        val isContractModule = bcvModules.any { project.path == ":$it" || project.path.endsWith(":$it") }
        val detektConfig = if (isContractModule) {
            rootProject.files(
                "config/detekt/detekt.yml",
                "config/detekt/detekt-published-contracts.yml",
            )
        } else {
            rootProject.files("config/detekt/detekt.yml")
        }
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            config.setFrom(detektConfig)
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

// ── the integration leg: real modules composed, the world outside faked ───────
//
// A unit test exercises one module with its collaborators replaced. An
// integration test exercises the SEAM between modules: the real objects of two
// or more modules, wired as the product wires them, with every effect that
// would reach outside the process replaced by a fake the test owns.
//
// The lane exists because the unit leg cannot observe a wiring mistake. Two
// modules can each pass every unit test they own and still disagree about the
// port they share; only a test that instantiates both of them can see it.
//
// What the lane does NOT do is reach outside the process. A filesystem, a
// subprocess, the clock and the network stay faked, exactly as in a unit
// test. The difference is which side of the boundary the code under test
// sits on: INSIDE for a unit test, and STRADDLING for an integration test.
//
// The source set is declared here rather than in each module, so every module
// gets the same lane and the same task names. A module without an
// `src/integrationTest/kotlin` directory simply has no integration test, and
// its task is skipped rather than reported as an empty success.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        // The Kotlin JVM plugin creates the `test` source set, and it does so
        // through the Java plugin's source-set machinery. The lane is declared
        // here, once, so every module gets the same one and the same task
        // names: a lane declared per module would drift, and a lane that
        // drifts is a lane whose reports nobody reads.
        //
        // The project is named explicitly because the receiver of this block
        // is the plugin, not the project, and an unqualified `sourceSets`
        // would not resolve. The extensions are reached by their TYPE rather
        // than by their accessors, because the type-safe accessors are
        // generated for the script's own receiver and not for the projects it
        // enumerates.
        val javaExtension =
            project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
        val lane: org.gradle.api.tasks.SourceSet = javaExtension.sourceSets.create("integrationTest")

        // The Kotlin half of the lane. The source set carries the CONVENTIONAL
        // directory, and it is added only when the plugin did not already map
        // it, so a module that declares its own directory is not given the
        // same sources twice.
        val kotlinExtension = project.extensions
            .getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java)
        val kotlinLane = kotlinExtension.sourceSets.maybeCreate("integrationTest")
        val kotlinDir = project.file("src/integrationTest/kotlin")
        if (kotlinLane.kotlin.srcDirs.none { it == kotlinDir }) {
            kotlinLane.kotlin.srcDir(kotlinDir)
        }

        // The lane compiles AGAINST the module's main, so it has to see the
        // module's own seams and not only its published ones. Kotlin's
        // `internal` is scoped to a compilation, and the lane is a separate
        // compilation, so without this the lane could not reach an internal
        // collaborator at all: the alternative would be to widen the product's
        // visibility for the sake of a test, and the lane is the wrong reason
        // to change what the product publishes.
        kotlinExtension.target.compilations.configureEach {
            if (name == "integrationTest") {
                associateWith(kotlinExtension.target.compilations.getByName("main"))
            }
        }

        project.configurations[lane.implementationConfigurationName]
            .extendsFrom(project.configurations.getByName("testImplementation"))
        project.configurations[lane.runtimeOnlyConfigurationName]
            .extendsFrom(project.configurations.getByName("testRuntimeOnly"))

        project.dependencies.add(lane.implementationConfigurationName, libs.junit.jupiter)
        project.dependencies.add(lane.runtimeOnlyConfigurationName, libs.junit.platform.launcher)

        // The lane's classpath. The lane compiles against the module's MAIN output
        // and against its own configuration, and NOT against its own output:
        // a classpath that names the lane's own output makes the Java and the
        // Kotlin compile tasks depend on each other, and the build fails with
        // a circular dependency.
        lane.compileClasspath += javaExtension.sourceSets["main"].output
        lane.runtimeClasspath += javaExtension.sourceSets["main"].output

        project.tasks.register<org.gradle.api.tasks.testing.Test>("integrationTest") {
            testClassesDirs = lane.output.classesDirs
            classpath = lane.runtimeClasspath
            useJUnitPlatform()
            // The lane's reports live beside the unit ones, in a directory of
            // their own, so a reader can tell which leg produced which number.
            reports.junitXml.outputLocation.set(
                project.layout.buildDirectory.dir("test-results/integrationTest"),
            )
            reports.html.outputLocation.set(
                project.layout.buildDirectory.dir("reports/tests/integrationTest"),
            )
        }

        project.tasks.named("check") {
            dependsOn(project.tasks.named("integrationTest"))
        }
    }
}

// The Kover-all merge module was removed with the plugin . It aggregated
// coverage from the Kotlin suites; with those gone every dependency in the merge list
// resolves to zero coverage, so it reported a number that could not fail and could not
// pass either.

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
    // A lane that reads a document of the repository receives the PATH of that
    // document from the module that declares it as an input of the lane.
    //
    // The repository root used to be handed over here instead, as a system
    // property, and the lanes composed the path of the document out of that
    // property and a relative path of their own. That made two derivations of
    // one path, and the document stayed invisible to Gradle: a task is
    // invalidated by its DECLARED inputs, and a document read at runtime
    // through a property is not one of them. The lane stayed UP-TO-DATE after
    // the document changed, and the report it left on disk was the report of a
    // document that no longer existed.
    tasks.withType<Test>().configureEach {
        if (project.name in modulesWithTestVolume) {
            maxParallelForks = 4
        }
    }
}

// THE ROWS THAT START THE PRODUCT ARE NOT CALLED BY THE GATE.
//
// The gate certifies THIS TREE, and a row that starts the product can go red
// for a reason the code does not have: the machine is busy, the port is
// taken, the clock of the machine is off. A gate that calls such a row
// publishes a verdict about the machine under the name of a verdict about the
// tree.
//
// So the rows that carry the tag below are EXCLUDED from the lanes when the
// gate asks for it, and the gate always asks. The rows stay in the tree: they
// are declared in `scripts/check-product-boot-layer.py` with the reason they
// are here, and whoever wants them runs the lane with the property absent.
//
// The word of the tag is written here, in the rows that carry it and in the
// guard that declares them, and the guard is what says that those three are
// the same word: a tag that only one of the three knows is a tag that does
// not exclude anything.
subprojects {
    tasks.withType<Test>().configureEach {
        if (providers.gradleProperty("pipelinek.excludeBootChecks").isPresent) {
            useJUnitPlatform { excludeTags("boot-check") }
        }
    }
}

// THE LANES DO NOT TOUCH THE OPERATOR'S CACHE OF COMPILED SCRIPTS.
//
// A row that compiles a script in process, and every child of the product a
// row starts, finds the cache through `PIPELINEK_SCRIPT_CACHE_DIR`, and with
// no value there the cache is the operator's own, under the home of the user.
// A lane that uses that cache is served artifacts another build stored, and
// leaves artifacts of its own in the operator's machine.
//
// So every lane gives the variable a directory of its own, in the temporary
// directory of the task, and empties it before it runs. The children of the
// rows inherit the environment of the JVM of the lane, so the directory
// reaches them without a seam of the product that only a test would use. A
// row that is about the cache itself names a directory of its own.
subprojects {
    tasks.withType<Test>().configureEach {
        val scriptCache = temporaryDir.resolve("script-cache")
        environment("PIPELINEK_SCRIPT_CACHE_DIR", scriptCache.absolutePath)
        doFirst { scriptCache.deleteRecursively() }
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
// The consequence for a break depends on whether the module's bytes TRAVEL with the release.
// A BCV-only module that nothing ships gets no maturity classification and no exception receipt,
// because it has no consumer program that could break silently: its internal callers are inside
// this build, so a removed public type fails `compileKotlin` immediately and loudly. What the dump
// buys there is the review — a diff nobody has to be shown.
// A BCV-only module whose JAR travels inside the distribution IS a published surface, even though
// it resolves from no Maven coordinate: the ABI door compares it against the JAR that the previous
// distribution shipped, so a break of it is named by the instrument, it carries a maturity
// classification, and it needs an exception receipt like any other published break.
// Adding a new module to BCV requires two steps: (1) append its name to
// `bcvModules` above; (2) run `:pipeline-<x>:apiDump` to materialise the
// baseline, then commit.api file alongside the build change.

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

// ── the HTML reference of the published API ─────────────────────────────────────
//
// Dokka generates the reference a third party reads to learn what this product
// publishes. It is applied to the modules whose public ABI is frozen, and to
// NO other: a reference of an internal module would describe a surface nobody
// consumes.
//
// The membership test is the one the ABI guard uses, with the same list, so the
// reference and the frozen surface cannot disagree about which modules carry a
// published contract. `bcvModules` is now read by three guards — the ABI
// baseline, the documentation rules and this one — and ONE list is what keeps
// them from drifting apart.
//
// The reference is ONE site PER CONTRACT, and not a single aggregated site.
// That is a measured decision, not a preference: Dokka 2.2.0 can aggregate the
// modules into one site, and the aggregated site was measured at 2764 broken
// internal links out of 193281 (the paths it writes carry a module prefix that
// the aggregation does not rewrite), while the per-module site of the same
// contract measures ZERO broken links out of 4446. A reference whose links do
// not resolve is a worse artifact than seven references that each resolve.
//
// Why Dokka enters the build at all: it brings an engine (KDoc analysis over
// K2) and an output format (HTML) that this project does not own and would not
// reimplement for an artifact that is not part of the product. It runs at build
// time and reaches no published jar.
//
// The output lands in `build/dokka/html` inside each module, inside `build/`
// and outside the versioned tree: a reference is a build artifact, and this
// repository carries no documentation tree.
//
// Nothing here is wired into `check`: the generation is an INDEPENDENT task
// that a reader asks for when they want the reference, and it is never part of
// the cost of a unit test or of the gate.
subprojects {
    val isBcvModule = bcvModules.any { project.path == ":$it" || project.path.endsWith(":$it") }
    if (isBcvModule) {
        pluginManager.apply("org.jetbrains.dokka")
        // The javadoc FORMAT, and not only the HTML one. A Maven repository that a third party
        // resolves from asks for a `-javadoc.jar` beside the JAR, and Gradle's own `javadoc` task
        // produces an EMPTY jar for a Kotlin module (there are no Java sources to document). An
        // empty artifact named `javadoc` is a promise nobody keeps, so the reference these
        // modules already publish is generated a second time, in the format the name promises.
        pluginManager.apply("org.jetbrains.dokka-javadoc")
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
                // The output is declared here and not left to the tool's
                // default: "the reference lives under build/" is this
                // repository's contract, and a contract that depends on a
                // default is a contract one upgrade can break.
                dokkaPublications.getByName("html").outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
                dokkaPublications.getByName("javadoc").outputDirectory.set(layout.buildDirectory.dir("dokka/javadoc"))
            }
        }
    }
}

// The one task that asks for the whole reference: `./gradlew apiReferenceHtml`.
// It depends on the per-contract generation and on nothing else. It is
// deliberately NOT a dependency of `check`, of `build` or of any test task, so
// neither a unit test nor the gate pays for a web page.
tasks.register("apiReferenceHtml") {
    group = "documentation"
    description = "Generates the HTML reference of every published contract under each module's build/dokka/html."
    dependsOn(bcvModules.map { module -> ":$module:dokkaGeneratePublicationHtml" })
}

// The review of the published reference, as the gate asks for it.
//
// A contract is read through its reference, and a link in that reference which
// names a symbol the reader cannot open is a promise the artifact does not
// keep. The generator emits a warning for each of those links, and a warning
// that nobody counts is a warning nobody fixes.
//
// The generation is FORCED when this task is what was asked for. A generation
// Gradle finds up to date emits no warning at all, so reading the log of one
// would report zero unresolved links whatever the tree says. The generator
// has no `failOnWarning` of its own, and the re-run is what makes the
// measurement real.
//
// The contracts are the SAME ones the ABI guard freezes, from the SAME list,
// so the reference and the frozen surface cannot disagree about which modules
// carry a published contract.
tasks.register("publishedReferenceCheck") {
    group = "documentation"
    description = "Generates the reference of every published contract and reports the KDoc links that do not resolve."
    dependsOn(bcvModules.map { module -> ":$module:dokkaGeneratePublicationHtml" })
}

// The re-run is forced for the review alone. A reader who asks for the
// reference gets the one Gradle already holds, because an unchanged input
// produces an unchanged reference; and the review asks for a fresh one,
// because it counts warnings that a held reference no longer emits.
gradle.projectsEvaluated {
    val asksForTheReview = gradle.startParameter.taskNames.any {
        it.substringAfterLast(':') == "publishedReferenceCheck"
    }
    if (asksForTheReview) {
        bcvModules.forEach { module ->
            project(":$module").tasks.named("dokkaGeneratePublicationHtml").configure {
                outputs.upToDateWhen { false }
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
 *
 * Being on this list is not a promise that every declaration of the module is a promise too. A
 * published surface carries decisions, and the decisions that took a declaration out of it are
 * recorded in `contract/published-contract-exceptions.json` with the census of the consumers they
 * reached. The one that touches this list today is the carrier of git credentials: `pipeline-domain`
 * published `dev.rubentxu.pipeline.v2.domain.scm.GitCredentials` and `SecretHandleRef` until the
 * checkout stopped being able to receive them, and the withdrawal is recorded there.
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
    // The list above has NO automated guard on its membership: `apiCheck` proves the published
    // surface is internally consistent; it does not prove the list is the right list. Anyone
    // adding a sixth published contract must decide here, in review, whether it earns
    // publication.
    "pipeline-runtime-api",
    // The client of the local service: the wire vocabulary and the typed client. It is
    // published because a consumer OUTSIDE this repository has to resolve it to talk to
    // the service, and a module nobody can resolve is a module nobody can use.
    "pipeline-service-client",
    // The definition of a `.pipeline.kts`. It is published because an IDE outside this
    // repository resolves it to compile a script with the same imports and the same refused
    // diagnostics the runner applies.
    "pipeline-scripting-definition",
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
 * The published coordinate of the SDK: the group every published module carries, and
 * the artifact name each one carries inside it.
 *
 * ONE declaration, read by everything that has to name a published artifact: the
 * publication itself, the BOM that pins the five contracts, the path the ABI
 * comparison walks to find a published JAR, and the mirror that checks the staged
 * bundle against the fresh build. A second declaration of the same fact is a second
 * authority, and the two drift the first time only one of them is edited.
 *
 * The group is `dev.rubentxu.pipelinek`. The namespace the publisher holds
 * (`dev.rubentxu`) covers it by the prefix rule, and the name of the solution is in
 * the coordinate a consumer writes.
 *
 * The artifact name is NOT derived from the Gradle project name. The modules are
 * `:pipeline-domain` and friends, and their PUBLISHED names are `pipelinek-domain`
 * and friends: the name a consumer resolves carries the product's name, and the name
 * the build uses internally is a different thing.
 */
val sdkBundleGroup = "dev.rubentxu.pipelinek"

/**
 * The published name of every published module, by its Gradle project name.
 */
val sdkBundleArtifactNames: Map<String, String> = mapOf(
    "pipeline-domain" to "pipelinek-domain",
    "pipeline-scripting-api" to "pipelinek-scripting-api",
    "pipeline-events" to "pipelinek-events",
    "pipeline-output" to "pipelinek-output",
    "pipeline-runtime-api" to "pipelinek-runtime-api",
    "pipeline-service-client" to "pipelinek-service-client",
    "pipeline-scripting-definition" to "pipelinek-scripting-definition",
    "pipeline-sdk-bom" to "pipelinek-sdk-bom",
)

/**
 * The group and the artifact name a published module carried BEFORE the rename.
 *
 * A published version is immutable: the 0.94.2 that carries
 * `dev.rubentxu.pipeline.v2:pipeline-domain` will carry it forever, and the release
 * that comes after has to be able to FIND it to compare against it. The names are
 * kept here for that reading, and only for it: they are the name of the past, not a
 * second name for the present.
 */
val legacyPublishedGroup = "dev.rubentxu.pipeline.v2"

val legacyPublishedArtifactNames: Map<String, String> = mapOf(
    "pipeline-domain" to "pipeline-domain",
    "pipeline-scripting-api" to "pipeline-scripting-api",
    "pipeline-events" to "pipeline-events",
    "pipeline-output" to "pipeline-output",
    "pipeline-runtime-api" to "pipeline-runtime-api",
    "pipeline-sdk-bom" to "pipeline-sdk-bom",
    // `pipeline-service-client` and `pipeline-scripting-definition` are NOT
    // here, and their absence is the honest entry: a module that a release
    // publishes for the FIRST time never
    // carried a legacy name, because there is no legacy version of it. An
    // entry that named one would assert a publication that never happened,
    // and the reading that looks for a previous artifact would go looking
    // for a JAR that was never released.
)

/**
 * The Maven layout of a published artifact: the group is one for every published
 * module, and the artifact name is the one this build declares for it.
 */
val PUBLISHED_GROUP_PATH = sdkBundleGroup.replace('.', '/')

/**
 * Where the published artifact of a module lives in a Maven repository, for the side
 * of the comparison that reads it.
 *
 * The candidate side carries the current names. The previous side may carry the
 * names the module had when THAT version was published, because a published version
 * never changes: the release before the rename still carries the old names, and the
 * release after it does not. Both are tried, in this order, and the one that is
 * there wins. When neither is there there is no artifact to read on this side, and
 * the caller says so instead of reading an empty surface as a total removal of the
 * module's ABI.
 */
fun publishedArtifactCandidates(module: String): List<Pair<String, String>> = buildList {
    sdkBundleArtifactNames[module]?.let { add(sdkBundleGroup to it) }
    // A module with no legacy name is a module that was never published
    // under the old coordinates: there is nothing older to look for, and the
    // absence is not a defect to be papered over with an invented name.
    legacyPublishedArtifactNames[module]?.let { add(legacyPublishedGroup to it) }
}

/**
 * The published JAR of a module in a Maven repository, or null when that repository
 * does not carry it under any of the names it was published under.
 */
fun publishedArtifactFile(repo: File, module: String, version: String): File? =
    publishedArtifactCandidates(module).firstNotNullOfOrNull { (group, artifact) ->
        File(
            repo,
            "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar",
        ).takeIf { it.isFile }
    }

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
 * Whether the previous release's repository is there to be read.
 *
 * The repository is the FACT about what the previous release published: the
 * release script downloads it WHOLE and verifies it against the digest that
 * release published, so an artifact that is not in it was not published.
 *
 * A repository that is missing, that is not a directory, or that is EMPTY is
 * not a fact about the previous release: it is a reading that did not happen,
 * and it must keep refusing rather than being read as "the previous release
 * published nothing".
 */
fun previousRepositoryIsReadable(repo: String?): Boolean {
    if (repo == null) return false
    val directory = File(repo)
    if (!directory.isDirectory) return false
    return directory.listFiles()?.isNotEmpty() == true
}

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

/**
 * The modules whose ABI is guarded but whose artifact is NOT published as a Maven
 * coordinate.
 *
 * For a published module the previously published artifact is an artifact in a Maven
 * repository, and the second reading finds it there. For a guarded module that is not
 * published, the previously published bytes are the JAR the previous release's
 * DISTRIBUTION shipped: the product's own ZIP carries every module it runs with, so
 * the last published bytes of such a module live inside the last published ZIP. The
 * comparison is the same one and the engine is the same one on both sides; only the
 * place the previous artifact comes from differs.
 *
 * The previous artifact is named by the same property the published modules use
 * (`-PpreviousArtifactVersion`) and the ZIP that carries it by
 * `-PpreviousDistributionZip`. Without both, the previous side is SKIPPED and not
 * empty: an empty dump would read as a total removal of the module's ABI.
 *
 * The list names the guarded modules whose JAR travels inside the distribution.
 * A module that nothing ships is NOT here: with no published bytes to compare
 * against, the second reading would have nothing to say, and the guard it does
 * have is the review of the committed baseline.
 */
val guardedUnpublishedModules = listOf("pipeline-credentials-api", "pipeline-step-sdk:api")

// The geometry of the comparison, handed to `:pipeline-release` — the module
// that decides — so that the build script and the decision agree on which
// artifacts, which versions and which modules are being compared. The
// alternative, each side recomputing it, is the second authority this task
// exists to remove.
extra["publishedApiDumpDirs"] = publishedApiDumpDirs
extra["publishedApiVersions"] = publishedApiCoordinates.mapValues { it.value.second }
extra["abiComparedModules"] = publishedContractModules + guardedUnpublishedModules
// The published coordinate, handed to the modules that have to name it: the release
// module that writes the JSON manifest of the contracts and the application that
// generates the coordinates `pipelinek sdk-info` answers with. Read from here, so the
// coordinate is written in ONE place in this build.
extra["sdkBundleGroup"] = sdkBundleGroup
extra["sdkBundleArtifactNames"] = sdkBundleArtifactNames
// The set of contracts, handed to the same two modules for the same reason: a module
// that joins `publishedContractModules` joins the JSON manifest and the CLI's answer
// without a second list to edit.
extra["publishedContractModules"] = publishedContractModules

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
                    publishedArtifactFile(File(repo), publishedModule, version)
                }
                val outputFile = File(publishedApiDumpDirs.getValue(side), "$publishedModule.api")
                if (artifact != null && artifact.isFile) {
                    tasks.register<KotlinApiBuildTask>("${side}ArtifactApiDump") {
                        group = "verification"
                        description =
                            "Dump the public API surface of the $side published artifact of $publishedModule."
                        runtimeClasspath.setFrom(apiBuildTask.map { it.runtimeClasspath })
                        inputJar.set(artifact)
                        outputApiFile.set(outputFile)
                    }
                } else if (side == "previous" && previousRepositoryIsReadable(repo)) {
                    // The previous release's repository IS there, whole and
                    // verified by its digest, and it does NOT carry this
                    // module. That is a FACT about what the previous release
                    // published, and the fact is the repository: the module is
                    // NEW.
                    //
                    // So the previous surface of a module that did not exist is
                    // an EMPTY surface, and it is written here. The comparison
                    // then says what it is: everything is added, and nothing
                    // the previous release published is gone.
                    //
                    // What is NOT done here is writing an empty surface for a
                    // module whose artifact could not be FOUND because the
                    // repository itself is missing or empty: that reading did
                    // not happen, so the task stays disabled and the comparison
                    // refuses. An absent dump is still no reading at all.
                    tasks.register("${side}ArtifactApiDump") {
                        group = "verification"
                        description =
                            "Record the empty previous surface of a module the previous release " +
                                "did not publish."
                        val target = outputFile
                        outputs.file(target)
                        doLast {
                            target.parentFile?.mkdirs()
                            // A blank line and not nothing: the reader of a
                            // surface accepts a blank line, and a file that is
                            // visibly there cannot be read as a dump that was
                            // never written.
                            target.writeText("\n")
                        }
                    }
                } else {
                    tasks.register<KotlinApiBuildTask>("${side}ArtifactApiDump") {
                        group = "verification"
                        description =
                            "Dump the public API surface of the $side published artifact of $publishedModule."
                        runtimeClasspath.setFrom(apiBuildTask.map { it.runtimeClasspath })
                        // There is no artifact to read on this side. Skipped, not empty: an
                        // empty dump would read as a total removal of the module's ABI.
                        enabled = false
                    }
                }
            }
        }
    }
}

// The two sides of the comparison for a module that is GUARDED but not published.
//
// The published modules take their previous artifact from a Maven repository, because
// that is where their previous release put it. A guarded module that is not published
// has no Maven coordinate, so its previously published bytes are the JAR the previous
// release's DISTRIBUTION shipped: the product's own ZIP carries every module it runs
// with. Same comparison, same engine, different place the previous artifact comes from.
subprojects {
    val guardedModule = guardedUnpublishedModules.firstOrNull { ":$it" == project.path }
    if (guardedModule != null) {
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            // The engine that produced this tree's committed baseline. Copying its
            // worker classpath is what makes the two sides comparable: the same
            // renderer, in the same build.
            val apiBuildTask = tasks.named<KotlinApiBuildTask>("apiBuild")
            val jarTask = tasks.named<Jar>("jar")

            // The CANDIDATE side: the bytes this tree builds.
            tasks.register<KotlinApiBuildTask>("candidateArtifactApiDump") {
                group = "verification"
                description =
                    "Dump the public API surface of the candidate artifact of $guardedModule."
                runtimeClasspath.setFrom(apiBuildTask.map { it.runtimeClasspath })
                inputJar.set(jarTask.flatMap { it.archiveFile })
                outputApiFile.set(
                    File(publishedApiDumpDirs.getValue("candidate"), "$guardedModule.api"),
                )
                dependsOn(jarTask)
            }

            val previousZip = providers.gradleProperty("previousDistributionZip")
            val previousVersion = providers.gradleProperty("previousArtifactVersion")
            val extracted = layout.buildDirectory.file("previous-published/$guardedModule.jar")
            // The name the ARTIFACT carries, which is not always the name of the
            // project: the distribution names the JAR of a nested module by the
            // module's own name, not by the path that leads to it.
            val archiveBaseName = jarTask.flatMap { it.archiveBaseName }

            // The JAR the previous release's distribution shipped, taken OUT of that
            // distribution. It is not rebuilt from a tag: the bytes that were published
            // are the bytes the comparison has to read.
            tasks.register("extractPreviousPublishedJar") {
                group = "verification"
                description =
                    "Extract the $guardedModule JAR the previous release's distribution shipped."
                inputs.property("previousDistributionZip", previousZip)
                inputs.property("previousArtifactVersion", previousVersion)
                outputs.file(extracted)
                doLast {
                    val zipFile = File(previousZip.orNull ?: "")
                    val version = previousVersion.orNull
                    if (!zipFile.isFile) {
                        throw GradleException(
                            "the previous distribution named by -PpreviousDistributionZip does not " +
                                "exist at $zipFile, so the previously published surface of " +
                                "$guardedModule cannot be read",
                        )
                    }
                    if (version.isNullOrBlank()) {
                        throw GradleException(
                            "-PpreviousArtifactVersion is required to name the $guardedModule JAR " +
                                "inside the previous distribution",
                        )
                    }
                    val entrySuffix = "lib/${archiveBaseName.get()}-$version.jar"
                    java.util.zip.ZipFile(zipFile).use { zip ->
                        // The distribution carries its payload under a top-level directory
                        // that carries the version, so the entry is matched by its END and
                        // not by its whole name. Exactly one entry has to match: none means
                        // the artifact is not in there, and more than one means the
                        // distribution carries two candidates for it.
                        val matches = zip.entries().asSequence()
                            .filter { it.name.endsWith(entrySuffix) }
                            .toList()
                        if (matches.size != 1) {
                            throw GradleException(
                                "the previous distribution carries ${matches.size} entries ending in " +
                                    "'$entrySuffix': the published artifact of $guardedModule at " +
                                    "version $version is not exactly there",
                            )
                        }
                        val entry = matches.single()
                        val target = extracted.get().asFile
                        target.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }

            // The PREVIOUS side: the JAR the previous release's distribution shipped.
            tasks.register<KotlinApiBuildTask>("previousArtifactApiDump") {
                group = "verification"
                description =
                    "Dump the public API surface of the previously published artifact of $guardedModule."
                runtimeClasspath.setFrom(apiBuildTask.map { it.runtimeClasspath })
                if (previousZip.isPresent && previousVersion.isPresent) {
                    dependsOn("extractPreviousPublishedJar")
                    inputJar.set(extracted)
                    outputApiFile.set(
                        File(publishedApiDumpDirs.getValue("previous"), "$guardedModule.api"),
                    )
                } else {
                    // There is no artifact to read on this side. Skipped, not empty: an
                    // empty dump would read as a total removal of the module's ABI.
                    enabled = false
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
    // The service client, for the same reason: a consumer that pins the platform version
    // pulls the SDK of the service with it, instead of repeating its version.
    "pipeline-service-client",
    // The script definition, so an IDE project that pins the platform resolves the definition
    // of the same release as the DSL it compiles against.
    "pipeline-scripting-definition",
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

// ── the SDK bundle for a REMOTE Maven repository ─────────────────────────────────
//
// Six coordinates, ONE version, and the shape a Maven repository on the other side of the
// network expects: a POM, the JAR, the sources, the javadoc, a detached signature and a
// checksum per file.
//
// WHY A SECOND REPOSITORY, AND NOT A MUTATION OF THE FIRST
//
// The repository that lands in `sdk-repo` is the SOURCE of the SDK ZIP the product publishes.
// Every file in it is therefore part of a released artifact, and its digest is named in the
// release's `SHA256SUMS`. A bundle that added `sources.jar`, `javadoc.jar` and signatures to
// that repository would change the SDK ZIP of the next candidate — and the SDK ZIP of the
// candidate that is ALREADY published is a fact nobody may rewrite. So the bundle goes
// somewhere else: `sdk-repo-staging`, a repository with the SAME shape as a remote one
// (`group/artifact/version/`), published by a SECOND publication built from the SAME component.
//
// One component, two publications, one set of coordinates: the six are DERIVED from the two
// lists that already exist, so the bundle cannot publish a module the RC does not publish, and
// cannot omit one it does.
//
// WHY THE POM IS WRITTEN ONCE
//
// Central requires a POM to declare a name, a description, a url, a license, an SCM and a
// developer, and a POM without them describes nothing to the person who has to decide whether
// to depend on it. Those strings are written HERE, once, and BOTH publications read them: the
// one that lands in the RC's SDK ZIP and the one that lands in the staging repository. Two
// spellings of the same sentence would be two truths about one artifact, and the checker
// refuses a bundle whose POM differs from the POM the RC carries.
val sdkBundleModules = (publishedContractModules + sdkBomModules).distinct()

val sdkBundleProjectUrl = "https://github.com/Rubentxu/pipeline-kotlin"
val sdkBundleScmConnection = "scm:git:https://github.com/Rubentxu/pipeline-kotlin.git"
val sdkBundleScmDeveloperConnection = "scm:git:ssh://git@github.com/Rubentxu/pipeline-kotlin.git"

/**
 * The POM's human-facing strings, per module.
 *
 * The name is what a repository listing shows and the description is what a reader decides
 * with. They are written here and nowhere else: the publication that lands in the RC's SDK ZIP
 * and the publication that lands in the staging repository read the same two strings.
 */
val sdkPomMetadata = mapOf(
    "pipeline-domain" to (
        "PipelineK Domain" to
            "The domain value types and the durable-run contracts a PipelineK plugin compiles against."
        ),
    "pipeline-scripting-api" to (
        "PipelineK Scripting API" to
            "The public authoring surface of the PipelineK pipeline DSL: the step, the context and the result."
        ),
    "pipeline-events" to (
        "PipelineK Events" to
            "The event contract of PipelineK: the envelope, the cursor and the read contract of the event plane."
        ),
    "pipeline-output" to (
        "PipelineK Output" to
            "The read contract of the PipelineK output plane: the frames, the offsets and the query seam."
        ),
    "pipeline-runtime-api" to (
        "PipelineK Runtime API" to
            "The publish/subscribe ports of the PipelineK runtime and the ADTs they carry."
        ),
    "pipeline-service-client" to (
        "PipelineK Service Client" to
            "The wire contract of the PipelineK local service and the typed client that speaks it."
        ),
    "pipeline-scripting-definition" to (
        "PipelineK Scripting Definition" to
            "The definition of a PipelineK .pipeline.kts: the @KotlinScript class, its compilation configuration and the template marker an IDE discovers it by."
        ),
    "pipeline-sdk-bom" to (
        "PipelineK SDK BOM" to
            "The resolution document that pins every published PipelineK SDK contract to one version."
        ),
)

// The staging repository: a local directory with the LAYOUT of a remote Maven repository, so
// that what a consumer resolves from it is what it would resolve from the network. It lives
// under the build directory, which is not versioned and is not among the published assets:
// nothing here can be published by accident, because publishing is somebody else's decision
// (the publication contract does not exist yet) and this path has no remote repository to
// publish to.
val sdkBundleRepositoryDir = layout.buildDirectory.dir("sdk-repo-staging")

// The SECOND remote destination: the Maven registry of GitHub Packages.
//
// It exists because the first one (Central) needs a namespace and a token that are
// facts about somebody else's database, and a second channel that needs NEITHER
// keeps the SDK resolvable while those facts are pending.
//
// The coordinates do NOT change here. The Maven registry of GitHub Packages accepts
// any groupId — measured in its own documentation, which uses `com.example` as its
// example — so the six coordinates keep the group this build declares
// (`dev.rubentxu.pipelinek`) and a consumer's build does not change between the
// two channels. The only thing that changes is WHERE the bytes are served from.
//
// The credentials are read from the environment and never from a file of this
// repository: `GITHUB_ACTOR` is the account that publishes and `GITHUB_TOKEN` is a
// personal access token with the `write:packages` scope.
val sdkGithubPackagesUrl = providers.gradleProperty("sdkGithubPackagesUrl")
    .orElse("https://maven.pkg.github.com/Rubentxu/pipeline-kotlin")
    .get()

subprojects {
    val moduleProject = this
    val isBundleModule = sdkBundleModules.contains(moduleProject.name)
    val isBomModule = moduleProject.name == "pipeline-sdk-bom"

    if (isBundleModule) {
        // The POM metadata is applied to EVERY Maven publication of the module, which today is
        // exactly the two: the one that lands in the RC's SDK ZIP and the one that lands in the
        // staging repository. Applying it by type rather than by name means a publication added
        // later cannot arrive without the metadata Central requires.
        moduleProject.pluginManager.withPlugin("maven-publish") {
            moduleProject.extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    // The published coordinate is DECLARED, not derived. The group and the
                    // artifact name come from the one declaration this build owns, so the
                    // publication that lands in the RC's SDK ZIP and the publication that
                    // lands in the staging repository cannot carry two different names for
                    // the same artifact. Deriving the artifact name from the Gradle project
                    // name would publish `:pipeline-domain` as `pipeline-domain`, and the
                    // name a consumer resolves is the product's name.
                    group = sdkBundleGroup
                    artifactId = sdkBundleArtifactNames.getValue(moduleProject.name)

                    val metadata = sdkPomMetadata[moduleProject.name] ?: return@configureEach
                    pom {
                        name.set(metadata.first)
                        description.set(metadata.second)
                        url.set(sdkBundleProjectUrl)
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/license/mit")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("rubentxu")
                                name.set("Rubén Torres")
                                url.set("https://github.com/Rubentxu")
                            }
                        }
                        scm {
                            connection.set(sdkBundleScmConnection)
                            developerConnection.set(sdkBundleScmDeveloperConnection)
                            url.set(sdkBundleProjectUrl)
                        }
                    }
                }

                repositories {
                    maven {
                        name = "staging"
                        url = uri(sdkBundleRepositoryDir)
                    }
                    // The mirror in GitHub Packages. Same coordinates, same POM, same
                    // bytes: a second place to serve them, not a second build of them.
                    maven {
                        name = "githubPackages"
                        url = uri(sdkGithubPackagesUrl)
                        credentials {
                            // Read from the environment and never from a file of this
                            // repository. An empty value is not a credential: the
                            // preconditions refuse before anything is published.
                            username = providers.environmentVariable("GITHUB_ACTOR").getOrElse("")
                            password = providers.environmentVariable("GITHUB_TOKEN").getOrElse("")
                        }
                    }
                }
            }
        }

        moduleProject.pluginManager.withPlugin("maven-publish") {
            moduleProject.extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("sdkBundle") {
                    // The SAME component the RC's publication carries: the bundle is a second
                    // destination for these bytes, not a second build of them.
                    from(if (isBomModule) components["javaPlatform"] else components["java"])
                    if (!isBomModule) {
                        // The sources and the javadoc are the two artifacts Central asks for and
                        // that this product did not publish. The sources jar is the Kotlin
                        // plugin's own; the javadoc jar carries the KDoc reference Dokka already
                        // generates for these modules, in the javadoc format. The BOM carries
                        // neither: it is a document of versions, not a library.
                        artifact(moduleProject.tasks.named("kotlinSourcesJar"))
                        artifact(moduleProject.tasks.register<Jar>("sdkJavadocJar") {
                            group = "documentation"
                            description = "Packages the Dokka javadoc reference as the publication's javadoc artifact."
                            dependsOn("dokkaGeneratePublicationJavadoc")
                            from(
                                moduleProject.extensions
                                    .getByType<org.jetbrains.dokka.gradle.DokkaExtension>()
                                    .dokkaPublications.getByName("javadoc").outputDirectory,
                            )
                            archiveClassifier.set("javadoc")
                            isPreserveFileTimestamps = false
                            isReproducibleFileOrder = true
                        })
                    }
                }

                // The publication to the mirror in GitHub Packages cannot run before the
                // door has said yes. The task is found by its NAME and not by its
                // identity, because the plugin creates it when the publication is
                // created, and this block runs before that happens.
                moduleProject.tasks
                    .matching { it.name == "publishSdkBundlePublicationToGithubPackagesRepository" }
                    .configureEach {
                        dependsOn(
                            ":sdkGithubPackagesPreconditions",
                            ":sdkSigningTrustCheck",
                            ":sdkStagedBytesCheck",
                            ":pipeline-release:mavenPublication",
                        )
                    }
            }
        }
    }
}

// The one task that stages the bundle: it publishes the six coordinates into the staging
// repository. It does NOT sign, and it does NOT verify: the signature is the act of the
// publisher's key (a script that knows the trust set), and the verification is an instrument
// that can fail. `scripts/release/stage-sdk-bundle.sh` runs the three in that order.
tasks.register("sdkBundle") {
    group = "build"
    description = "Publishes the six SDK coordinates into the staging Maven repository, with the shape a remote repository expects."
    dependsOn(sdkBundleModules.map { ":$it:publishSdkBundlePublicationToStagingRepository" })
}

// ── the remote destination of the six coordinates ────────────────────────────────
//
// Central Portal is NOT a Maven-layout HTTP repository: the service that accepted a push
// per file is retired, and what the portal accepts is a BUNDLE — a ZIP with the layout of
// a Maven repository — uploaded to ONE endpoint with a token.
//
// So the remote destination has two halves, and both are declared here next to the
// publication that produces the bytes: the endpoint the bundle travels to, and the
// repository with the shape of a remote that carries it. That repository is the staging
// one — the same directory the bundle task publishes to and the signing script signs. The
// bytes that travel are the bytes that were verified, and a second copy of them would be
// a second set of bytes to keep in sync.
//
// The endpoint is a property, so the operator names where it publishes to and this tree
// does not pretend to own a server.
val sdkRemoteUploadUrl = providers.gradleProperty("sdkRemoteUploadUrl")
    .orElse("https://central.sonatype.com/api/v1/publisher/upload")
    .get()

// The name the destination shows for the deployment. It names the PUBLICATION and not the
// artifact: the destination groups the files it receives into one deployment, and a name
// that said "six coordinates" would say nothing.
val sdkRemoteDeploymentName = providers.gradleProperty("sdkRemoteDeploymentName")
    .orElse("pipelinek-${project.version}")
    .get()

// ── the upload of the bundle to the remote destination ──────────────────────────
//
// The upload cannot run until the checks have passed. They are DEPENDENCIES of the upload
// task and not steps inside it: a task that runs a check and then decides whether to
// upload reads as "it checks", and the graph of Gradle is where the order is visible to
// the reader.
//
// The order is the guarantee:
//
//   preconditions → the trust guard → the admission decision → the upload
//
// The preconditions refuse first, because a task that refuses before it does the expensive
// work is a task an operator can run to find out what is missing. The trust guard refuses
// a tree whose keys cannot be attributed. The admission decision is the verdict of the
// product's own model, and it runs in THIS build: a nested invocation of the build would
// be refused by the lock this checkout takes, and a second build is a second set of bytes.
// The documents the admission reads, and what each one is. They are the SAME for
// every channel: the request, the manifest, the certification, the bytes to
// publish, the SDK ZIP of the candidate and the state of the destination. Two
// channels that published the same version against two different sets of documents
// would be two publications, and only one of them would be accountable.
val sdkAdmissionDocuments = listOf(
    "maven.request" to "the request that asks for the publication",
    "maven.manifest" to "the manifest that describes the bytes to publish",
    "maven.certification" to "the certification of the candidate",
    "maven.bundle" to "the bytes to publish, the directory the bundle lives in",
    "maven.sdkZip" to "the SDK ZIP of the candidate",
    "maven.published" to "the state of the target repository",
    "maven.verdict" to "the file the admission writes its verdict to",
)

val sdkUploadPreconditions = tasks.register("sdkUploadPreconditions") {
    group = "verification"
    description = "Refuses when the documents of the admission or the credentials of the destination are missing."
    doLast {
        val missingDocuments = sdkAdmissionDocuments
            .filter { (name, _) -> providers.gradleProperty(name).orElse("").get().isBlank() }
            .map { (name, what) -> "$name ($what)" }

        val missingCredentials = listOf("SONATYPE_USERNAME", "SONATYPE_PASSWORD")
            .filter { System.getenv(it).isNullOrBlank() }

        if (missingDocuments.isNotEmpty() || missingCredentials.isNotEmpty()) {
            val problems = mutableListOf<String>()
            if (missingDocuments.isNotEmpty()) {
                problems += "  the documents of the admission are missing: ${missingDocuments.joinToString(", ")}"
            }
            if (missingCredentials.isNotEmpty()) {
                problems += "  the credentials of the destination are missing: ${missingCredentials.joinToString(", ")}"
            }
            throw GradleException(
                "sdkUploadPreconditions: the publication cannot start, and nothing was uploaded.\n" +
                    problems.joinToString("\n") + "\n" +
                    "  The documents are the request, the manifest, the certification, the bytes to\n" +
                    "  publish, the SDK ZIP of the candidate and the state of the target repository.\n" +
                    "  The credentials are read from the environment and never from a file of this\n" +
                    "  repository: the account is a fact about somebody else's database, and\n" +
                    "  nothing here depends on it.",
            )
        }
    }
}

val sdkSigningTrustCheck = tasks.register<Exec>("sdkSigningTrustCheck") {
    group = "verification"
    description = "The guard of the trust sets: both declarations, and the separation between them."
    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "scripts/check-signing-key-trust.py")
}

// The upload itself. The bundle it sends is the one the signing script signed and the
// checker proved, and the script re-verifies it before it packages it: what travels is
// what was verified, and the verification happens against the bytes that travel.
val sdkUpload = tasks.register<Exec>("sdkUpload") {
    group = "distribution"
    description = "Uploads the signed bundle to the remote destination. It cannot run until the trust guard and the admission decision have passed."
    dependsOn(sdkUploadPreconditions, sdkSigningTrustCheck, ":pipeline-release:mavenPublication")
    workingDir = rootProject.projectDir.parentFile

    // The bundle is NOT defaulted here. The admission names the bytes to publish in
    // `maven.bundle`, and the upload sends the same name: a default on this side would be a
    // second authority for what is published, and the two could name different bytes.
    val bundle = providers.gradleProperty("maven.bundle").orElse("").get()
    val verdict = providers.gradleProperty("maven.verdict").orElse("").get()

    commandLine(
        "bash",
        "scripts/release/upload-sdk-bundle.sh",
        "--bundle", bundle,
        "--verdict", verdict,
        "--url", sdkRemoteUploadUrl,
        "--deployment", sdkRemoteDeploymentName,
        "--out", layout.buildDirectory.file("sdk-upload-receipt.txt").get().asFile.absolutePath,
    )
}

// ── the second channel: the mirror in GitHub Packages ───────────────────────────
//
// The mirror serves the SAME bytes the first channel serves, under the SAME
// coordinates: the group does not change, so a consumer's build does not change
// between the channels. What changes is where the bytes are served from, and
// that the mirror needs a token of the publisher instead of a namespace.
//
// The bytes are the same because the build is reproducible, and that is MEASURED
// here and not assumed: the digest of the JAR this build produces and the digest
// of the JAR in the signed staging bundle have to be the same one, or the mirror
// would publish bytes that nobody signed.
//
// The order is the guarantee, and it is the same order as in the first channel:
//
//   preconditions → the trust guard → the admission decision → the publication
//
// EVERY publish task depends on the admission, so a request the door refused
// never reaches the network: the tasks are siblings of each other, and a sibling
// is not a barrier.
val sdkGithubPackagesPreconditions = tasks.register("sdkGithubPackagesPreconditions") {
    group = "verification"
    description = "Refuses when the documents of the admission or the credentials of the mirror are missing."
    doLast {
        val missingDocuments = sdkAdmissionDocuments
            .filter { (name, _) -> providers.gradleProperty(name).orElse("").get().isBlank() }
            .map { (name, what) -> "$name ($what)" }

        val missingCredentials = listOf("GITHUB_ACTOR", "GITHUB_TOKEN")
            .filter { System.getenv(it).isNullOrBlank() }

        if (missingDocuments.isNotEmpty() || missingCredentials.isNotEmpty()) {
            val problems = mutableListOf<String>()
            if (missingDocuments.isNotEmpty()) {
                problems += "  the documents of the admission are missing: ${missingDocuments.joinToString(", ")}"
            }
            if (missingCredentials.isNotEmpty()) {
                problems += "  the credentials of the mirror are missing: ${missingCredentials.joinToString(", ")}"
            }
            throw GradleException(
                "sdkGithubPackagesPreconditions: the publication cannot start, and nothing was published.\n" +
                    problems.joinToString("\n") + "\n" +
                    "  The documents are the request, the manifest, the certification, the bytes to\n" +
                    "  publish, the SDK ZIP of the candidate and the state of the target repository.\n" +
                    "  The credentials are read from the environment and never from a file of this\n" +
                    "  repository: the account is a fact about somebody else's database, and\n" +
                    "  nothing here depends on it.",
            )
        }
    }
}

// The bytes the mirror would publish are the bytes the bundle was signed with.
// The comparison is a measurement of the two digests, and a difference refuses
// the publication naming both digests: a mirror that serves other bytes is a
// second set of bytes, and nobody can say which one was certified.
val sdkStagedBytesCheck = tasks.register("sdkStagedBytesCheck") {
    group = "verification"
    description = "Proves that the bytes the mirror would publish are the bytes the bundle was signed with."
    // The JARs this build produces are the ones the comparison measures, so they
    // have to be BUILT before the comparison runs: a check that measured a jar
    // that does not exist yet would be a check that measured nothing.
    dependsOn(sdkBundleModules.filter { it != "pipeline-sdk-bom" }.map { ":$it:jar" })
    doLast {
        val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
        fun digestOf(file: java.io.File): String {
            messageDigest.reset()
            file.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    messageDigest.update(buffer, 0, read)
                }
            }
            return messageDigest.digest().joinToString("") { "%02x".format(it) }
        }

        val groupPath = sdkBundleGroup.replace('.', '/')
        val releaseVersion = project.version.toString()
        val staging = sdkBundleRepositoryDir.get().asFile
        val differences = mutableListOf<String>()
        val missing = mutableListOf<String>()

        sdkBundleModules.filter { it != "pipeline-sdk-bom" }.forEach { module ->
            // The published name and the built name are TWO names for the same bytes: the
            // publication renames the JAR to the artifact name, and the build leaves it
            // under the module name. The check compares the bytes, so it has to read
            // both sides under the name each one carries.
            val artifactName = sdkBundleArtifactNames.getValue(module)
            val builtFileName = "$module-$releaseVersion.jar"
            val publishedFileName = "$artifactName-$releaseVersion.jar"
            val staged = staging.resolve("$groupPath/$artifactName/$releaseVersion/$publishedFileName")
            val fresh = rootProject.projectDir.resolve("$module/build/libs/$builtFileName")
            if (!staged.isFile) {
                missing += "$publishedFileName is not in the staging bundle ($staging)"
            } else if (!fresh.isFile) {
                missing += "$builtFileName was not built ($fresh)"
            } else {
                val stagedDigest = digestOf(staged)
                val freshDigest = digestOf(fresh)
                if (stagedDigest != freshDigest) {
                    differences += "$publishedFileName: the staging bundle holds $stagedDigest and this build " +
                        "produced $freshDigest. The mirror would publish bytes that the bundle " +
                        "that was signed does not carry."
                }
            }
        }

        if (missing.isNotEmpty() || differences.isNotEmpty()) {
            val problems = mutableListOf<String>()
            if (missing.isNotEmpty()) {
                problems += "  the bytes to compare are missing: ${missing.joinToString("; ")}"
            }
            if (differences.isNotEmpty()) {
                problems += "  the bytes are not the same: ${differences.joinToString("; ")}"
            }
            throw GradleException(
                "sdkStagedBytesCheck: the mirror would not publish these bytes.\n" +
                    problems.joinToString("\n") + "\n" +
                    "  The mirror serves the bytes the bundle was signed with. A build that\n" +
                    "  produces other bytes has to be signed again before it is published.",
            )
        }
    }
}

// The publication in the mirror. It is six publications, one per coordinate, and
// the aggregator exists so that the operator runs ONE command and the graph
// shows what it does. Each of the six tasks carries the same dependencies (they
// are configured where the publication is created, because the plugin creates
// them there).
tasks.register("sdkPublishToGithubPackages") {
    group = "distribution"
    description = "Publishes the six SDK coordinates to the mirror in GitHub Packages. It cannot run until the trust guard and the admission decision have passed."
    dependsOn(
        sdkGithubPackagesPreconditions,
        sdkSigningTrustCheck,
        ":pipeline-release:mavenPublication",
        sdkBundleModules.map { ":$it:publishSdkBundlePublicationToGithubPackagesRepository" },
    )
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

    // The console of the NESTED build, captured instead of inherited.
    //
    // The nested build is a DIFFERENT build, and its task lines are not tasks
    // of this one. Leaving them in this build's console made them look like
    // they were, and the gate's census counts a task the console declares as
    // one the clock has to have measured. The clock is THIS build's, so the
    // nested lines opened a hole in the census: the console declared 285
    // tasks that ran and the clock measured 271. A census with a hole in it is
    // not published as a census, so the gate refused to publish one.
    //
    // The console is not discarded: it is this task's declared output, and a
    // nested build that fails prints its own console before failing. The
    // nested cost is still measured, once, as the duration of THIS task.
    val nestedConsole = layout.buildDirectory.file("reports/example-plugins/build.log")
    outputs.file(nestedConsole)
    // The exit value is read here instead of letting the task fail on the
    // spot, because a failure has to say WHAT the nested build said. Gradle's
    // own failure message would name the exit code and nothing else.
    isIgnoreExitValue = true

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

    doFirst {
        // The stream is opened HERE, while the task RUNS, and not while the
        // build is configured: a stream is not something the configuration
        // cache can store, and a task that carried one would make the entry
        // unstorable. Opening it in the action keeps the task serializable.
        val consoleFile = nestedConsole.get().asFile
        consoleFile.parentFile.mkdirs()
        standardOutput = consoleFile.outputStream()
    }

    doLast {
        val exitCode = executionResult.get().exitValue
        if (exitCode != 0) {
            val consoleFile = nestedConsole.get().asFile
            val consoleText = if (consoleFile.isFile) consoleFile.readText() else ""
            throw GradleException(
                "buildExamplePlugins: the composite build of the four example plugins failed " +
                    "with exit code $exitCode. Its console is in " +
                    "${consoleFile.absolutePath}:" + "\n" + consoleText,
            )
        }
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
//                  the state. The plugin knows nothing of the runtime's
//                  internals, and the compensation loop it drives is not
//                  observed by any test in this repository: it is a declared
//                  gap.
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
    // The test tasks of the application consume the SAME four JARs, because
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
    // The INTEGRATION lane carries the same classpath and had the same hole:
    // the lane inherits `testRuntimeOnly` from `runtimeOnly`, so it consumes
    // the same JARs, and an invocation that carried the build AND the
    // integration lane failed with the same message and the name of the other
    // task. The two lanes are declared together because they consume the same
    // thing for the same reason.
    //
    // The staged copies in `lib/` are NOT declared as inputs of the test task.
    // The tests read the originals, which are the output of
    // `:buildExamplePlugins`; the staged copies are the output of
    // `:pipeline-application:installDist`, and declaring them here would trade
    // one undeclared dependency for another.
    appProject.tasks.matching {
        it.name == "test" || it.name == "integrationTest"
    }.configureEach {
        dependsOn(":buildExamplePlugins")
    }
}
