import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.cyclonedx)
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

application {
    // WU-LPR-070: product name for the installed distribution (bin/pipelinek).
    applicationName = "pipelinek"
    mainClass.set("dev.rubentxu.pipeline.v2.application.MainKt")
}

// PK-0-57-RC1: Gradle 8.14.5 strict validation flags the application
// plugin's default `distZip` task for not declaring its dependency on
// `installDist`. The implicit dependency existed in earlier Gradle
// versions; the strict validator now requires an explicit declaration
// for the output of `installDist` that `distZip` consumes.
// Without this, `:pipeline-release:releaseGate` fails with
// "Task ':pipeline-application:distZip' uses this output of task
// ':pipeline-application:installDist' without declaring an explicit
// or implicit dependency".
tasks.named("distZip") { dependsOn(tasks.named("installDist")) }

// WU-LPR-071: single-version provider contract — the jar manifest MUST carry
// Implementation-Version populated from project.version (which is sourced from the
// git tag at release time via v2/build.gradle.kts root). Without this, the
// fail-closed `pipeline version` subcommand exits non-zero, which is the desired
// behaviour for an unversioned snapshot but breaks the published release contract.
//
// We also write the same attribute into every nested subproject jar so a future
// runtime that introspects a dependency's manifest reports the matching version
// instead of "unknown" / null.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
            "Implementation-Vendor" to project.group.toString(),
            "Built-By" to "Gradle",
        )
    }
}

// S6-COMPOSITION — the version the RUNTIME reads, as a classpath RESOURCE rather than as a jar
// manifest attribute.
//
// Why this exists, and why it is not redundant with the manifest above: `Package.getImplementationVersion()`
// only answers when the code was loaded from a JAR that declares a manifest. Thirty-four UAT and
// corpus harnesses legitimately launch the CLI with `java -cp <test classpath>`, where
// `pipeline-application` is a CLASSES DIRECTORY and no manifest exists. Reading the version only
// from the manifest made admission fail closed on every one of those runs — 143 tests, all dying
// with `FATAL - jar manifest is missing Implementation-Version` and exit 3. The fail-closed rule
// was right; the SOURCE was wrong, because it conflated "no manifest" with "not a build artifact".
//
// Both are populated from the same `project.version`, which is the sole authority (WU-LPR-071).
// `RuntimeApiVersionDriftTest` asserts the two agree, so this can never become a second authority
// free to disagree with the manifest that `pipeline-release` certifies against.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version-resources")
    val versionValue = project.version.toString()
    // H1.4 — provenance, recorded by the build that produced the bytes.
    //
    // A product version names a release LINE, not a commit: every commit on 0.60.0 reports
    // `pipeline 0.60.0`. So a harness holding two binaries cannot separate them by asking the
    // binary, and that is not hypothetical — the five corpus failures in
    // CDH_PLANE_DIAGNOSTIC_RECEIPT.md were a stale binary reporting the correct version.
    //
    // Read through a Provider so the value is resolved at execution time and participates in
    // up-to-date checking. When the commit cannot be read the build still succeeds and records
    // nothing: a working-tree or exported-source build legitimately has no provenance, and the
    // candidate gate is where refusing that belongs. Failing the whole build here would break
    // ordinary development to protect a property only certification needs.
    val buildCommit = providers.provider {
        val head = providers.environmentVariable("PIPELINEK_BUILD_COMMIT").orNull
        if (!head.isNullOrBlank()) {
            return@provider head.trim()
        }
        val process = runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return@provider ""
        val output = process.inputStream.bufferedReader().readText().trim()
        val finished = runCatching { process.waitFor(30, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            return@provider ""
        }
        // A 40-hex full SHA, or nothing. A truncated or non-SHA value would be a worse answer
        // than absence, because it would look like provenance while not being any.
        if (process.exitValue() == 0 && output.matches(Regex("[0-9a-f]{40}"))) output else ""
    }
    inputs.property("version", versionValue)
    inputs.property("buildCommit", buildCommit)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get()
            .file("dev/rubentxu/pipeline/v2/application/pipelinek-version.properties")
            .asFile
        target.parentFile.mkdirs()
        target.writeText(
            "# Generated from project.version. Do not edit; see build.gradle.kts.\n" +
                "# The runtime reads THIS, because Package.getImplementationVersion() is null\n" +
                "# whenever the CLI runs from a classes directory instead of a jar.\n" +
                "version=$versionValue\n" +
                "# Provenance. Absent when the build could not resolve a commit, which is a\n" +
                "# legitimate working-tree build and an unacceptable candidate.\n" +
                "buildCommit=${buildCommit.get()}\n",
        )
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource)
}

// FAST-LANE SPLIT (2026-10-17). Read the FAST-LANE INVARIANT note on `tasks.test`
// for the defect this removes.
//
// Three certification suites compile against the external example plugin JARs:
// `UppercaseStepContractSuiteTest`, `ExampleBlockPluginCertificationTest` and
// `DirectivePluginContractSuiteTest`. They are in-process certification runs of the
// PRODUCTION engine against a real plugin -- they import 25-40 production classes each
// (`CanonicalDurableRunCoordinator`, `StepRegistry`, the DSL, the domain nodes) -- so
// they belong in this module and cannot live in the plugin's own build, which compiles
// against the published SDK and never sees the coordinator.
//
// Their COMPILE classpath therefore needs the plugin JARs, and those JARs are produced by
// an `Exec` task that forks a second Gradle against `build/sdk-repo`. While they sat in
// the `test` source set, that made the inner loop pay for external certification on every
// `pipeline-domain` edit -- the SDK is republished six times over and a second Gradle is
// forked -- which is the exact cost the H1 TODO 3 comment claimed the fast lane did not
// have.
//
// The split is by SOURCE SET and NOT by duplication. `pluginContractTest` extends
// `testImplementation`, so it inherits junit, coroutines and the step-sdk plugins, and it
// additionally consumes `sourceSets.test.output`: the shared `CoordinatorFixture` (168
// lines, used by 31 classes) stays compiled once in `test` and is reused. Copying it would
// have been the cheaper-looking edit and the more expensive one to maintain.
val pluginContractTest: SourceSet by sourceSets.creating {
    // `main.output` is added EXPLICITLY: the java plugin wires it into the `test` source
    // set as a SourceSet-level entry, not as a member of `testCompileClasspath`, so a
    // source set created by hand inherits none of it. Without this line every production
    // class in the module -- the coordinator, the registry factory, the credential port --
    // resolves as Unresolved, which is a loud and instantly diagnosable failure.
    compileClasspath += sourceSets["main"].output +
        sourceSets["test"].output +
        configurations["testCompileClasspath"]
    runtimeClasspath += output + compileClasspath
}
configurations["pluginContractTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["pluginContractTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    // The shared test fixtures, compiled once by `test`.
    "pluginContractTestImplementation"(sourceSets["test"].output)

    // The three external example plugin JARs. Declared as a buildable file collection
    // over the Exec tasks that produce them, so the task dependency is carried BY THE
    // DEPENDENCY rather than by a hand-written `dependsOn` in another file. That is what
    // makes the fast-lane invariant meaningful: the only thing that can pull a plugin
    // build into this module is this declaration, in the lane that needs it.
    "pluginContractTestImplementation"(
        files(
            rootProject.tasks.named("buildExamplePlugin"),
            rootProject.tasks.named("buildExternalDirectivePlugin"),
            rootProject.tasks.named("buildExampleBlockPlugin"),
        ),
    )
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    // BLOCK 2 split the event plane: the contract above, the durable implementation below. The
    // runtime is the only thing that should be able to name OperationJournal, ReplayCursorStore or
    // SqliteEventStore, and this is the module that does.
    implementation(project(":pipeline-events-store"))
    // M1-P2: the Output Plane. Process transcript bytes are written here and nowhere else, and no
    // console event carries them. The dependency points ONE WAY - :pipeline-output depends on
    // neither :pipeline-events nor :pipeline-domain, so the event plane cannot reach back into the
    // output plane and re-acquire an authority over the bytes. See ADR-M1 D2/D3.
    //
    // BLOCK 2 splits the plane in two: the CONTRACT above, and the store below. Only the runtime
    // needs the store, so only the runtime names it - which is the point. The published artifact
    // carries no writer, so no external consumer can become a second one.
    implementation(project(":pipeline-output"))
    implementation(project(":pipeline-output-store"))
    implementation(project(":pipeline-event-harness"))
    implementation(project(":test-harness"))
    implementation(project(":pipeline-scripting-kotlin24"))
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-step-sdk:files"))
    implementation(project(":pipeline-step-sdk:scm-git"))
    // RP6-C / LFC-2E3: the HTTP OFFICIAL_PLUGIN ships in the distribution and is
    // discovered through ServiceLoader, like scm-git and junit.
    implementation(project(":pipeline-step-sdk:http"))
    // F5.2: JUnit OFFICIAL_PLUGIN is bundled into the distribution so the
    // external discovery seam can resolve it without `--plugin-jar`.
    implementation(project(":pipeline-step-sdk:junit"))
    // LFC-2E2: utilities OFFICIAL_PLUGIN (readJson / writeJson / sha256) is bundled
    // into the distribution so the external discovery seam can resolve it without
    // `--plugin-jar`. Has build-time provenance (sha256 + version) emitted by
    // `:pipeline-step-sdk:utilities:computeUtilitiesDigest`.
    implementation(project(":pipeline-step-sdk:utilities"))
    implementation(project(":pipeline-credentials-api"))
    implementation(project(":pipeline-credentials-local"))
    implementation(project(":pipeline-credentials-multipart")) // D2-rev: wire CredentialMaterializer for file-based credential dispatch
    implementation(project(":pipeline-credentials-executor")) // H0: port-driven withCredentials executor
    implementation(project(":pipeline-artefacts-local"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    // PK-RELEASE-CONSOLIDATION H1.4 follow-up: the three external example
    // plugins are mandatory in the release gate. Wiring them as
    // `runtimeOnly` files puts their JARs on the runtime classpath of
    // the application, which `installDist` then stages into `lib/`
    // (and the `distZip` packages alongside the four official
    // :pipeline-step-sdk plugins). The external JARs are produced by
    // :buildExamplePlugin, :buildExternalDirectivePlugin, and
    // :buildExampleBlockPlugin in the root build, all of which depend
    // on `publishSdkForExternalPlugin` so the JARs exist before the
    // application graph is wired. The test runtime uses the same JARs
    // (see `testImplementation` further down) so the gate and the
    // application suite observe the same bytes.
    runtimeOnly(files(rootDir.resolve("../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar")))
    runtimeOnly(files(rootDir.resolve("../examples/example-directive-plugin/build/libs/example-directive-plugin-0.1.0.jar")))
    runtimeOnly(files(rootDir.resolve("../examples/example-block-plugin/build/libs/example-block-plugin-0.1.0.jar")))

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":pipeline-step-sdk:scm-git"))
    testImplementation(project(":pipeline-step-sdk:http"))
    // F5.2: JUnit OFFICIAL_PLUGIN (typed report summary + XXE-hardened parser).
    testImplementation(project(":pipeline-step-sdk:junit"))
    // The three external example plugin JARs are NOT on the fast lane's compile
    // classpath. The suites that compile against them live in the `pluginContractTest`
    // source set (FAST-LANE SPLIT above); declaring the JARs here is what forced the
    // inner loop to build them. example.uppercase / example.lock /
    // example.repeat are unchanged in substance and are still certified, by `check`.[[
    // Override BOM-enforced wrong version (junit-platform-launcher uses 1.x not 5.x)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// Lane R: test *compilation* needs the plugin JAR on the test classpath. The
// JARs are declared above on `testImplementation` via `files(...)`. To avoid
// re-building the example plugins on every dev edit (the audit's P1 BQ2
// defect), the explicit `dependsOn` is removed. If the JARs are absent
// (a fresh checkout), the developer MUST run `:publishSdkForExternalPlugin`
// (or one of the example plugin builds) once before the first `:test`.
// The fast-lane build assumes the JARs are on disk, which is the steady
// state after any prior `:pipeline-application:installDist`.

tasks.test {
    // H1 TODO 3: the fast lane (`:test`) MUST NOT depend on
    // `:buildExamplePlugin` / `:buildExternalDirectivePlugin`
    // / `:buildExampleBlockPlugin`. Those three tasks build
    // the example plugin JARs and were a per-run cost of
    // ~tens of seconds for every inner-loop run, even when
    // the change under test does not exercise the installed
    // CLI at all. The installed CLI lane is `:installedContractTest`
    // (defined below); it carries the dependsOn for the
    // three example plugin builds.
    //
    // The `testImplementation(files(...))` lines at the top
    // of the dependencies block reference the same JARs.
    // Gradle's strict task-dependency validation refuses to
    // schedule `:test` if those JAR files are missing at
    // test time. The pre-existing JARs on disk (from any
    // prior `:publishSdkForExternalPlugin` or
    // `:installedContractTest` run) satisfy the file()s; a
    // truly fresh checkout will fail fast at the
    // `testImplementation` resolution step rather than at a
    // silent no-op later. The fast lane is honest about
    // its dependency on the JARs being on disk; it just
    // does not PAY for them on every run.
    //
    // BQ2 (2026-10-16 audit, RC3 close-out): the previous
    // configuration coupled `:test` to
    // `:pipeline-application:installDist` and three external
    // plugin builds. The fix splits the work into two
    // tasks:
    //   - `:test` (default) is the fast lane. It does NOT
    //     depend on `installDist` and runs the in-process,
    //     in-JVM contract tests. A typical dev edit hits this
    //     lane and completes in seconds.
    //   - `:installedContractTest` is the installed lane. It
    //     depends on `installDist` and the external plugin
    //     JARs and runs the tests that exercise the real
    //     CLI / real plugin artifacts (HF2 and above). It is
    //     the gate for release candidates and is invoked by
    //     `just gate-installed` or directly with
    //     `./gradlew :pipeline-application:installedContractTest`.
    //
    // Selection policy: a single Gradle property
    // `-Plane=fast` (default), `installed`, or `release`
    // picks the lane without re-typing the task name.
    //
    // The fast lane keeps the in-process contract test
    // fixture: the compile classpath already carries the
    // plugin JARs (declared above on `testImplementation`),
    // so a typical test that only references the plugin
    // from the JVM does not need `installDist` at all.
    //
    // S6/C: BuiltPluginManifestArtifactTest admits the REAL utilities artifact from the filesystem
    // at runtime, NOT from the compile classpath. The fast lane does NOT need this JAR to be
    // present at compile time; the test that needs it is in the installed lane.
    useJUnitPlatform()
    // S6-COMPOSITION: RuntimeApiVersionDriftTest compares the generated version resource against
    // the jar manifest's Implementation-Version, so this module's own jar must exist before the
    // test runs. Same reasoning as the plugin jars above: the subject is resolved from the
    // filesystem at runtime, not from the compile classpath.
    dependsOn(tasks.named("jar"))

    // H0..H4 closure (2026-10-16): the test classpath references the
    // three example plugin JARs. Gradle's strict task-dependency
    // validation refuses the configuration phase if no explicit
    // ordering exists. `mustRunAfter` (not `dependsOn`) preserves the
    // H1 TODO 3 fast-lane performance contract: the plugin builds
    // run only if the JARs are not already current, and a stale
    // build cache turns this into a no-op. The release gate
    // (`v2/pipeline-release/build.gradle.kts` `releaseGate` task)
    // adds the three plugin builds to its own `dependsOn` so the
    // gate always sees a fresh artifact set.
    mustRunAfter(":buildExamplePlugin", ":buildExternalDirectivePlugin", ":buildExampleBlockPlugin")

    // FAST-LANE INVARIANT (2026-10-17). The comment block above states the fast lane
    // MUST NOT pay for the three external plugin builds, and the ROOT build made it pay
    // anyway: `tasks.named("test") { dependsOn(buildExamplePlugin) }`. `mustRunAfter`
    // does not survive a `dependsOn` from anywhere in the build -- the dependency wins,
    // the plugin builds are scheduled, and every inner-loop run on a `pipeline-domain`
    // edit republished six SDK modules to `build/sdk-repo` and forked a second Gradle.
    //
    // This asserts the RESOLVED task graph rather than the text, because the text is
    // what disagreed with reality in the first place: the two wirings lived in different
    // files, so a reviewer reading either one saw a build that does not exist.
    // `taskDependencies` is fully resolved by execution time.
    //
    // LIMITATION, stated rather than implied: `doFirst` runs only when `:test` actually
    // executes. If the task is UP-TO-DATE the check is skipped -- the benign direction,
    // because a regression that adds a `dependsOn` also changes what must be scheduled.
    // It cannot certify the invariant; it can only refuse to let it pass silently on a
    // run that happened anyway.
    doFirst {
        val externalPluginBuilds = setOf(
            ":buildExamplePlugin",
            ":buildExternalDirectivePlugin",
            ":buildExampleBlockPlugin",
            ":publishSdkForExternalPlugin",
        )
        val leaked = taskDependencies.getDependencies(this)
            .map { it.path }
            .filter { it in externalPluginBuilds }
            .sorted()
        check(leaked.isEmpty()) {
            "the fast lane :test must not depend on the external plugin builds. Resolved " +
                "graph leaks: $leaked. Those tasks republish the SDK to build/sdk-repo and " +
                "fork a second Gradle, so every inner-loop run after a pipeline-domain " +
                "edit pays for external plugin certification. The plugin-coupled " +
                "certification suites belong to the pluginContractTest lane, which builds " +
                "them from the file collection in its own dependency block. See the " +
                "FAST-LANE INVARIANT note above."
        }
    }

    // BLOCK 2.5 / PK-R1: DirectivePluginShaReproducibilityTest rebuilds the example directive
    // plugin from a clean state, and the rebuild resolves its pipeline-domain coordinate from
    // v2/build/sdk-repo. Which version under that repo? The one this build just published via
    // :publishSdkForExternalPlugin (a transitive dep of :buildExternalDirectivePlugin, listed above).
    // Inject the version the build chose so the test does not have to guess from a directory listing
    // that may carry forward older published versions from prior builds on the same checkout.
    systemProperty("pipeline.domain.version", project.version.toString())

    // xerial sqlite-jdbc's static-state `loaded` cache collides with the
    // Gradle test JVM's multi-classloader hierarchy: the JNI symbol IS
    // exported in `org/sqlite/native/Linux/x86_64/libsqlitejdbc.so` (verified
    // via `nm -D`, `Java_org_sqlite_core_NativeDB__1open_1utf8` is `T`), the
    // Java NativeDB declares `synchronized native void _open_utf8(byte[], int)`,
    // and the .so signature matches. The failure is the CLASSLOADER, not the
    // JNI signature: under a multi-classloader JVM the driver's static
    // `loaded` field can resolve to a .so that was extracted by a different
    // thread / classloader than the one holding the NativeDB class. The
    // resulting `UnsatisfiedLinkError: 'void ... _open_utf8(byte[], int)'`
    // does NOT mean the symbol is missing; it means the JVM looked for the
    // symbol against the wrong library.
    //
    // Forcing `org.sqlite.lib.path` to the bundled .so inside the JAR
    // (via `classpath:` URI) makes the driver skip auto-extraction and load
    // the same library handle the NativeDB class can see. This is the
    // documented xerial escape hatch for multi-classloader deployments.
    systemProperty(
        "org.sqlite.lib.path",
        "classpath:org/sqlite/native/Linux/x86_64/libsqlitejdbc.so",
    )

    // ── Cross-module inputs, declared ────────────────────────────────────────────
    //
    // A handful of tests here read OTHER modules on purpose, because the law they enforce is about
    // a boundary rather than about code in this one: `RetentionAuthorityFitnessTest` scans
    // `:pipeline-output` and `:pipeline-output-store` and reads their `build.gradle.kts`, and
    // `A4_8LegacyRegistrySemanticParityTest` walks the source trees of `:pipeline-domain` and both
    // event plane modules.
    //
    // BLOCK 2 widened that set: the Output Plane and the event plane each became two modules, and a
    // fitness that named one of the halves was silently measuring half of a plane it claimed to
    // cover. Gradle's up-to-date check only sees declared inputs, and none of those reads went
    // through this test source set — so editing the module a fitness watches left the task
    // UP-TO-DATE and the guard reporting its previous answer.
    //
    // Per module rather than one tree rooted at `v2`: rooting there makes the declared input
    // overlap every other module's build directory and Gradle refuses the task. The obvious fix,
    // `exclude("**/build/**")`, is forbidden by `FArch011V2NoCompileExcludesTest`, which rejects the
    // token `exclude(` in any build file — and it is right to. Naming each module's own directories
    // avoids the overlap without the token.
    val crossModuleDirs = rootProject.subprojects.map { it.projectDir }

    inputs.files(crossModuleDirs.map { it.resolve("build.gradle.kts") })
        .withPropertyName("crossModuleBuildFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.files(crossModuleDirs.map { it.resolve("src/main/kotlin") })
        .withPropertyName("crossModuleProductionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // BQ2 (2026-10-16 audit, RC2): the fast lane is in-process
    // contract tests. They do not fork the `pipelinek` binary;
    // they do not need the wedge defense (maxParallelForks = 1,
    // forkEvery = 1) that PK-0-57-RC1 (T3) imposed on
    // installed-CLI tests. The defense is kept on
    // `:installedContractTest` below; the fast lane can run
    // forks in parallel. The WU-RP-005 measurement (2 parallel
    // forks halve wall time on 2-core CI) is restored for the
    // fast lane.
    //
    // `maxParallelForks` is left unset (Gradle default) so the
    // value is governed by the daemon's available cores. The
    // WU-RP-005 measure used 2 explicitly; here we trust the
    // daemon and rely on `-PpipelineTestForkEvery=0` from the
    // inner-loop recipe to opt out of fork-every entirely.
    //
    // PK-0-57-RC1 (T3) defence retained on the installed lane
    // (see `:installedContractTest` below) because THAT lane
    // forks the `pipelinek` binary and is the load wedge.
    useJUnitPlatform()

    // WU-RP-005 round 3: `--tests '!pkg.Class*'` negation on the command line
    // is silently IGNORED by Gradle in this configuration (verified locally:
    // the exact CI command still executed all 203 classes). CI now passes
    // -PexcludeSlowTests=true; the full suite (gate-app / release) runs
    // without the property and keeps every test.
    // WU-RP-005 r6: shard exclusions from CI. `--tests '!X'` on the command
    // WU-RP-005 round 3: `--tests '!pkg.Class*'` negation on the command line
    // is silently IGNORED by Gradle in this configuration (verified locally:
    // the exact CI command still executed all 203 classes). CI now passes
    // -PexcludeSlowTests=true; the full suite (gate-app / release) runs
    // without the property and keeps every test.
    // WU-RP-005 r6: shard exclusions from CI. `--tests '!X'` on the command
    // line is silently ignored by Gradle (verified in run 35646215918: the
    // engine shard re-ran every UatLocal class), so the workflow passes the
    // shard's exclusion patterns here instead.
    val shardExcludes = providers.gradleProperty("shard.excludes").orElse("")
    if (shardExcludes.get().isNotBlank()) {
        val patterns = shardExcludes.get().split(',').map { it.trim().removeSurrounding("'") }.filter { it.isNotBlank() }
        filter {
            patterns.forEach { excludeTestsMatching(it) }
        }
    }
    if (providers.gradleProperty("excludeSlowTests").isPresent) {
        // JUnit tag-based exclusion (WU-RP-005 r4). Gradle's test-source
        // exclude("...") trips the FArch011 textual fitness scanner (any
        // "exclude(" in a build file is a compile-excludes violation), so the
        // release-scale corpus classes are filtered by JUnit @Tag instead.
        useJUnitPlatform {
            excludeTags("release-scale")
        }
    }

    // LANE MEMBERSHIP, NOT A FILTER (2026-10-17). The comment block above this
    // block has always said "the fast lane does NOT need the plugin JAR; the
    // test that needs it is in the installed lane" and the lane-selection wiring
    // below has always said "the fast-lane :test excludes the tag so it never
    // sees an installed-CLI test". Neither was true: the ONLY excludeTags in
    // this module was `release-scale`, and it is behind an `excludeSlowTests`
    // property that CI does not set. So every `@Tag("installed-contract")` class
    // ran in BOTH lanes, and the 30 binary-spawning classes that carried no tag
    // at all ran in the fast lane only -- which is what made `:test` exceed a
    // 1500 s budget, and what turned one stale installed binary into 86 REDs.
    //
    // Membership is declared, not negotiated: a class is in exactly one lane by
    // carrying exactly one tag, and the two tasks partition on it. `installedContractTest`
    // is the lane that DECLARES its `installDist` dependency, which is the whole point --
    // a clean checkout gets a matching binary instead of inheriting whichever one was
    // last installed on the machine.
    //
    // `InstalledContractTagMembershipFitnessTest` is the guard. Without it, adding a
    // harness that calls `AppBinSupport.discover(` and forgetting the tag would put a
    // binary-spawning test back into the fast lane silently -- the failure is invisible
    // because the test still PASSES, just slowly.
    useJUnitPlatform {
        excludeTags("installed-contract")
    }
}

// The plugin-certification lane. It owns the three external example plugin JARs: they
// are on ITS compile classpath and it builds them, through the buildable file collection
// declared in the FAST-LANE SPLIT block. No wiring from the root build is involved, and
// none is needed -- the dependency travels with the declaration that requires it.
//
// It is wired into `check`, so the gate still certifies the plugins exactly as before.
// The suites were never optional; what changed is that the INNER LOOP stopped paying.
tasks.register<Test>("pluginContractTest") {
    group = "verification"
    description = "Certifies the external example plugins against the production engine (HF1)."
    testClassesDirs = pluginContractTest.output.classesDirs
    classpath = pluginContractTest.runtimeClasspath
    useJUnitPlatform()
    // Same fork economy as the other lanes in this module: one fork at a time.
    maxParallelForks = 1
    forkEvery = 1
}

// `check` must carry the plugin certification, or the split would have quietly retired
// the guarantee instead of relocating it.
tasks.named("check") {
    dependsOn(tasks.named("pluginContractTest"))
}

// BQ2 (2026-10-16 audit): a separate task for the installed-CLI
// contract lane. This task is the one that depends on
// `:pipeline-application:installDist`; the default `:test` task
// does not, so a typical dev edit does not pay for the cost of
// staging the distribution. The release-candidate gate invokes
// this task explicitly via `just gate-installed` or
// `./gradlew :pipeline-application:installedContractTest`.
//
// The lane selection is also exposed through the Gradle
// property `lane` (see below). Setting `-Plane=installed`
// makes `:test` defer to this task via the wiring added below.
//
// Test selection: the installed-CLI suite is the same
// `:pipeline-application:test` source set, restricted to
// JUnit-tagged classes via `@Tag("installed-contract")`.
// Tests that need `installDist` declare that tag; tests that
// run in-process do not. The fast-lane `:test` excludes the
// tag so it never sees an installed-CLI test; this task
// filters to the tag so it never sees a fast-only test.
tasks.register<Test>("installedContractTest") {
    group = "verification"
    description = "Runs the installed-CLI contract tests (HF2+). Depends on installDist."
    dependsOn(":pipeline-application:installDist")
    dependsOn(":buildExamplePlugin", ":buildExternalDirectivePlugin", ":buildExampleBlockPlugin")
    dependsOn(
        ":pipeline-step-sdk:http:jar",
        ":pipeline-step-sdk:scm-git:jar",
        ":pipeline-step-sdk:junit:jar",
        ":pipeline-step-sdk:utilities:jar",
    )
    dependsOn(tasks.named("jar"))
    useJUnitPlatform {
        includeTags("installed-contract")
    }
    // Same fork-economy as `:test`: the wedge documented in
    // PK-0-57-RC1 (T3) is still in force, so this lane runs
    // one fork at a time. A future ticket may relax this when
    // the wedge has a structural fix.
    maxParallelForks = 1
    forkEvery = 1
}

// Lane selection: a single Gradle property `-Plane=...` picks the
// lane without re-typing the task name. The default is `fast`
// (the inner-loop default). Setting `-Plane=installed` makes
// `:test` defer to `:installedContractTest` so the standard
// `./gradlew :pipeline-application:test` invocation runs the
// installed lane in CI and the fast lane locally.
val lane = (project.findProperty("lane") as? String) ?: "fast"
check(lane in setOf("fast", "installed", "release")) {
    "unknown -Plane=$lane; expected one of: fast, installed, release"
}
if (lane == "installed" || lane == "release") {
    // The release lane runs the full suite (default :test)
    // AND the installed-CLI lane. The harness-verdict path
    // and the cycle-runner close path invoke this lane.
    tasks.named("test").configure { dependsOn("installedContractTest") }
}

// BQ2 (2026-10-16 audit, RC2): wire the LanePlanner into the
// build so the lane partitioning is observable at the Gradle
// level, not just in unit tests. The task reads the same
// `LaneSpec` inputs the planner unit tests use and writes the
// partition to `build/reports/lane-planner.json` for human
// review. It is intentionally a no-side-effect task: it
// computes the partition, writes JSON, and exits. The
// plan-driven choice of `:test` vs `:installedContractTest`
// is made ABOVE via `-Plane=...`; this task is the receipt
// that proves the planner was consulted.
val laneReportClasspath = configurations.create("laneReportClasspath")
dependencies {
    // The LanePlanner lives in :test-harness (sibling project).
    // We depend on its runtime classpath directly so the
    // planner main class is reachable from this JavaExec task.
    laneReportClasspath(project(":test-harness"))
    laneReportClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    laneReportClasspath("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
}
val laneReportCp = laneReportClasspath
tasks.register<JavaExec>("laneReport") {
    group = "verification"
    description = "Runs LanePlanner.partition and writes build/reports/lane-planner.json"
    classpath = laneReportCp
    mainClass.set("dev.rubentxu.pipeline.v2.harness.lanes.LanePlannerMainKt")
    val out = layout.buildDirectory.file("reports/lane-planner.json")
    outputs.file(out)
    args("--output", out.get().asFile.absolutePath)
}
