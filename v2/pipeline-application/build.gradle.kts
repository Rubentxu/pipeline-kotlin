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
    // `:test-harness` was DELETED (2026-10-18, testing RESET). Its 35 sources were QA
    // scaffolding (ContractCatalogue, Gate, CycleRunner) and its only consumer in the whole
    // build was the `testImplementation` line that this comment used to justify. Removing
    // the module removes the dependency with it; nothing in `src/main` referenced it.
    //
    // `:pipeline-event-harness` below stays `implementation`: it is the `pipeline events
    // verify` CLI verb, dispatched from Main.kt, and is PRODUCT functionality, not harness.

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
    // `runtimeOnly` files puts their JARs on the runtime classpath of
    // the application, which `installDist` then stages into `lib/`
    // (and the `distZip` packages alongside the four official
    // :pipeline-step-sdk plugins). The external JARs are produced by
    // :buildExamplePlugin, :buildExternalDirectivePlugin, and
    // :buildExampleBlockPlugin in the root build, all of which depend
    // on `publishSdkForExternalPlugin` so the JARs exist before the
    // application graph is wired.
    //
    // The `pluginContractTest` source set that used to reach these same three JAR files is
    // gone (see TEST LANES REMOVED below), so this `runtimeOnly` line is now the ONLY
    // declaration of them. That is the right shape: they are PRODUCT plugins of the shipped
    // distribution, and the certification that consumed them was QA scaffolding.
    runtimeOnly(files(rootDir.resolve("examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar")))
    runtimeOnly(files(rootDir.resolve("examples/example-directive-plugin/build/libs/example-directive-plugin-0.1.0.jar")))
    runtimeOnly(files(rootDir.resolve("examples/example-block-plugin/build/libs/example-block-plugin-0.1.0.jar")))

    // The `testImplementation(...)` / `testRuntimeOnly(...)` block was REMOVED (2026-10-18,
    // testing RESET) together with the JUnit launcher and the `useJUnitPlatform()` wiring that
    // needed it. `src/test/kotlin` is empty in every module, so a JUnit engine on the test
    // classpath had no class to discover. The runtime classpath is unchanged: the
    // `implementation(project(":pipeline-step-sdk:junit"))` line above is the PRODUCT's
    // `junit.results` OFFICIAL_PLUGIN, not a test dependency, and it stays.

}

// TEST LANES REMOVED (2026-10-18, testing RESET). Five separate registrations were deleted
// from this build file, all of them reading a source set that is now empty:
//
//   * `pluginContractTest`            -- its own SourceSet + `Test` task + `check` wiring.
//   * `installedContractTest`         -- the shipped-binary certification task.
//   * `-Plane=fast|installed|release` -- the selector that chose between the two tasks.
//   * `tasks.test { ... }`            -- useJUnitPlatform, tag exclusions, shard switches,
//                                         the SQLite native system property, and two
//                                         cross-module `inputs.files(...)` fitness inputs.
//   * `forkEveryPerClass`             -- declared once, read only by `tasks.test`.
//
// The mechanism they shared is the reason the whole set had to go together. Gradle 8.14 has no
// `Test.failOnNoDiscoveredTests` property, and a `Test` task whose source set is empty reports
// GREEN while executing zero classes. Each surviving registration would have printed a
// certification that never ran: a plugin certification, a shipped-binary certification, and a
// fast lane passing over an empty classpath. `check` aggregates all of them. This is exactly
// the false compliance the current testing policy exists to prevent.
//
// Two guards that were inside those blocks are removed with them, and neither is a loss:
//   * the FAST-LANE INVARIANT `doFirst { check(...) }` refused to let `:test` depend on the
//     three external plugin builds. Its subject was a lane split that no longer exists --
//     there is one test configuration left in this build and it has no plugin-build edge.
//   * `forkEveryPerClass` was read by `tasks.test` and by nothing else. Left declared, an
//     unread `-PpipelineTestForkEvery` would have looked like a live tuning knob.
//
// Their replacement is the executable E2E layer: `.pipeline.kts` pipelines under
// `v2/examples/`, validated by `scripts/run-e2e-pipelines.sh`, which stage the real
// distribution once and run the real binary. That path executes the shipped artifact, so it
// certifies behaviour rather than asserting a tag on an empty source set.