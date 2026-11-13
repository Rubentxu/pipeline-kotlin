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
    // Product name for the installed distribution (bin/pipelinek).
    applicationName = "pipelinek"
    mainClass.set("dev.rubentxu.pipeline.v2.application.MainKt")

    // The database driver loads a native library, and the JVMs that restrict the dynamic
    // loading of native code print a warning for it — four lines on stderr, in front of
    // everything the command has to say. A command whose stderr is polluted cannot be read
    // by a person who is looking for a refusal, and cannot be parsed by a program that
    // captures it.
    //
    // The permission is granted here rather than left to the person running the command,
    // because the alternative is not a warning forever: a JVM that blocks the loading
    // outright leaves the product without a database. The flag is accepted, and does
    // nothing, on the JVMs where the restriction does not exist.
    //
    // GRANTING THAT PERMISSION MAKES THE JVM TALK, AND THE JVM TALKS ON STDOUT.
    // A shared class archive records the module properties it was written with, and the
    // archive the product writes inherits the JDK's own table, in which that permission
    // is unset. Mapping the archive, the JVM finds a property that the run declares and
    // the archive does not have, and says so: as a warning you cannot see before JDK 25,
    // as an error you can. The unified log goes to stdout, so the report lands in front
    // of the product's answer, and `version --json` stops being JSON.
    //
    // THE PRODUCT'S STDOUT IS THE PRODUCT'S ANSWER, AND THE JVM'S TALK IS NOT IT.
    // So the JVM's warnings and errors go to STDERR, where a diagnostic belongs, and
    // stdout carries the product's answer and nothing else. This is not about the
    // archive: it is about the stream, so it holds for whatever the JVM decides to
    // say next, which the archive was only the instance of.
    //
    // The two options are ONE decision and they only work together:
    //
    //   -Xlog:all=off:stdout     turns OFF the JVM's default output, which is stdout.
    //                              Without this, the default selection stays and the
    //                              warning appears on BOTH streams: measured, stdout=2
    //                              AND stderr=2 for the same two lines.
    //   -Xlog:all=warning:stderr sends the warnings and the errors to stderr.
    //
    // MEASURED 2026-11-08 with the distribution's own archive and a classpath that
    // does not match it (JDK 21.0.8, 24.0.2 and 25.0.4): stdout=0 on all three, and
    // the JVM's lines on stderr, where an operator reads them.
    //
    // What was here before, and why it did not work:
    //
    //   -Xlog:cds=off         silences the `cds` TAG, and the message that stays is
    //                         the one of the TAG SET {cds,dynamic}. Measured: stdout=1.
    //   -Xlog:cds,dynamic=off matches nothing, and the JVM answers on stdout with
    //                         "No tag set matches selection: dynamic": a tag set is
    //                         written with a PLUS, not with a comma.
    //   -Xlog:all=stderr      is not a thing: `stderr` there is a LEVEL, and the JVM
    //                         refuses to start ("Invalid level 'stderr'").
    //
    // The report itself is not actionable -- the archive is mapped anyway, and what
    // is lost is the optimized module handling -- so it is not a diagnostic that
    // anybody can act on. In JDK 25 the JVM raised its severity to ERROR, which
    // upstream calls a bug (JDK-8369079, open): the same condition was a warning
    // before. The archive's talk is muted below; every OTHER diagnostic the JVM
    // writes still goes to stderr, where a diagnostic belongs.
    //
    // THE ARCHIVE'S TALK IS NOT THE OPERATOR'S BUSINESS
    //
    // Moving the JVM's talk to stderr was not the end of it: on stderr it is still
    // in front of the operator, and what it says is about a file the PRODUCT owns and
    // the PRODUCT rewrites when it goes stale. The archive is the product's own
    // file; the JVM's report about it is not actionable by the person running the
    // command, and a command whose stderr is full of lines nobody can act on is as
    // unreadable as one whose stdout is full of them.
    //
    // So the archive's talk is muted, and ONLY the archive's talk. The muted set is
    // the tag set {cds,dynamic}, which is the set the JVM uses for everything it
    // says about the shared archive, and the wildcard is the point: `-Xlog:cds=off`
    // mutes the TAG `cds` and leaves the SET alive, and the line that survives is
    // the one nobody could act on anyway ("Skipping jdk/internal/event/Event: JFR
    // event class", "Not in loaded state").
    //
    //   -Xlog:cds*=off:stdout  the archive's talk does not reach the answer's stream.
    //   -Xlog:cds*=off:stderr  and it does not reach the diagnostic stream either.
    //
    // MEASURED 2026-11-08 on a distribution of this tree, with the archive DELETED
    // so the JVM has to write it and talk about it, on JDK 21.0.8, 24.0.2 and
    // 25.0.4: stderr=0 bytes cold and hot on all three, stdout=16 bytes
    // ("pipeline 0.94.2") in every reading, and the archive is still written
    // (4.9 MB) and still mapped. The SAME distribution with the 0.94.1 options
    // and nothing else changed is the baseline: stderr=350 bytes cold on JDK 21
    // and JDK 25, and 325 bytes hot on JDK 25. The OTHER diagnostics are not
    // muted: what the JVM says that is not about the archive still goes to
    // stderr, where it belongs.
    //
    // Whoever wants to read those lines asks for them with the options named after
    // the product, which are added last and win.
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Xlog:all=off:stdout",
        "-Xlog:all=warning:stderr",
        "-Xlog:cds*=off:stdout",
        "-Xlog:cds*=off:stderr",
    )
}

// Gradle 8.14.5 strict validation flags the application
// plugin's default `distZip` task for not declaring its dependency on
// `installDist`. The implicit dependency existed in earlier Gradle
// versions; the strict validator now requires an explicit declaration
// for the output of `installDist` that `distZip` consumes.
// Without this, `:pipeline-release:releaseGate` fails with
// "Task ':pipeline-application:distZip' uses this output of task
// ':pipeline-application:installDist' without declaring an explicit
// or implicit dependency".
tasks.named("distZip") { dependsOn(tasks.named("installDist")) }

// Single-version provider contract — the jar manifest MUST carry
// Implementation-Version populated from project.version (which is sourced from the
// git tag at release time via pipelinek/build.gradle.kts root). Without this, the
// fail-closed `pipeline version` subcommand exits non-zero, which is the desired
// behaviour for an unversioned snapshot but breaks the published release contract.
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

// The version the RUNTIME reads, as a classpath RESOURCE rather than as a jar
// manifest attribute.
// Why this exists, and why it is not redundant with the manifest above: `Package.getImplementationVersion()`
// only answers when the code was loaded from a JAR that declares a manifest. A run that launches
// the CLI with `java -cp <classpath>`, where `pipeline-application` is a CLASSES DIRECTORY, has no
// manifest at all. Reading the version only from the manifest made admission fail closed on every
// one of those runs, all dying with `FATAL - jar manifest is missing Implementation-Version` and
// exit 3. The fail-closed rule was right; the SOURCE was wrong, because it conflated "no
// manifest" with "not a build artifact".
// Both are populated from the same `project.version`, which is the sole authority
// `RuntimeApiVersionDriftTest` asserts the two agree, so this can never become a second authority
// free to disagree with the manifest that `pipeline-release` certifies against.
// The commit the bytes were made from.
//
// Provenance, recorded by the build that produced the bytes.
// A product version names a release LINE, not a commit: every commit on 0.60.0 reports
// `pipeline 0.60.0`. So two binaries of the same release cannot be separated by asking the binary
// for its version, and a stale binary reporting the correct version is a failure mode that a
// version check alone cannot see.
//
// It used to be read by a Provider evaluated while the build was being CONFIGURED. With the
// configuration cache enabled that Provider is evaluated once, while the entry is stored, and
// its value is frozen into the entry: a later build that reuses the entry reuses the OLD
// commit, and the binary reports a provenance that is not the tree it was built from. The
// value is therefore read by a task of its own, whose action runs at EXECUTION time, and it
// travels to the version resource through a marker file.
//
// The marker is an INPUT of the version task, not a side effect: when the commit changes, the
// marker's content changes and the version resource is rewritten; when it does not change, the
// version resource stays up to date and is not rewritten.
//
// When the commit cannot be read the build still succeeds and records nothing: a working-tree
// or exported-source build legitimately has no provenance, and the candidate gate is where
// refusing that belongs. Failing the whole build here would break ordinary development to
// protect a property only certification needs.
val resolveBuildCommit = tasks.register("resolveBuildCommit") {
    group = "application"
    description = "Resolve the commit the build is made from, for the version resource."

    // The marker and the working directory are taken HERE, while the build is
    // configured, so that the action below carries the VALUES and not the
    // script that owns them. An action that reaches into the script it was
    // declared in cannot be stored in the configuration cache: the cache
    // refuses to serialize a reference to a build script, and the task then
    // runs against a script that is not there.
    val marker = layout.buildDirectory.file("tmp/build-commit.txt")
    val gitDirectory = rootDir

    outputs.file(marker)
    // The commit lives in the working tree, and nothing the build declares as an input
    // says that it changed. A resolver with no declared inputs is never up-to-date, but
    // that is an implicit rule: this one says it outright, so the FIRST commit read is
    // never mistaken for the LAST one.
    outputs.upToDateWhen { false }

    doLast {
        val override = System.getenv("PIPELINEK_BUILD_COMMIT")
        val resolved = if (!override.isNullOrBlank()) {
            override.trim()
        } else {
            val process = runCatching {
                ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(gitDirectory)
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull()
            if (process == null) {
                ""
            } else {
                val output = process.inputStream.bufferedReader().readText().trim()
                val finished = runCatching { process.waitFor(30, TimeUnit.SECONDS) }
                    .getOrDefault(false)
                if (!finished) {
                    process.destroyForcibly()
                    ""
                } else if (process.exitValue() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
                    // A 40-hex full SHA, or nothing. A truncated or non-SHA value would be a worse answer
                    // than absence, because it would look like provenance while not being any.
                    output
                } else {
                    ""
                }
            }
        }
        val out = marker.get().asFile
        out.parentFile.mkdirs()
        out.writeText(resolved)
    }
}

val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version-resources")
    val versionValue = project.version.toString()
    // The marker the resolver wrote. Its CONTENT is the commit, and its content is
    // what makes the version resource up-to-date.
    val commitMarker = layout.buildDirectory.file("tmp/build-commit.txt")

    inputs.property("version", versionValue)
    inputs.file(commitMarker)
    outputs.dir(outputDir)
    dependsOn(resolveBuildCommit)

    doLast {
        val commit = commitMarker.get().asFile
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            .orEmpty()
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
                "buildCommit=$commit\n",
        )
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource)
}

/**
 * The published coordinate, written into a Kotlin source the CLI reads.
 *
 * The coordinate is DECLARED once, in the root build, and this task writes it where
 * the CLI can consume it. The alternative was a second list inside the CLI, and a
 * second list is a second authority: the rename of the coordinates would have had
 * to be applied in two places, and the one that was forgotten would have published
 * a CLI that tells consumers to depend on artifacts that do not exist.
 *
 * It is a SOURCE and not a resource on purpose. The CLI's answer is a published
 * contract, and a contract that is only there when the file is there is a contract
 * that can be absent: a compile-time constant cannot.
 */
val generateSdkCoordinates by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sdk-coordinates")
    val publishedGroup = rootProject.extra["sdkBundleGroup"] as String
    val publishedArtifactNames = rootProject.extra["sdkBundleArtifactNames"] as Map<*, *>

    // The order the CLI publishes, and the scope each coordinate carries. The BOM is
    // the one a consumer pins as a platform; the contracts are the ones it
    // compiles against. The contracts are read from the root build's single
    // declaration (`publishedContractModules`) and sorted by name, so the answer
    // has one order whatever order the declaration lists them in.
    val contracts = (rootProject.extra["publishedContractModules"] as List<*>)
        .map { it as String }
        .sorted()
    val bom = "pipeline-sdk-bom"

    val coordinates = (contracts.map { it to "implementation" } + (bom to "bom")).map { (module, scope) ->
        Triple(publishedGroup, publishedArtifactNames[module] as String, scope)
    }

    inputs.property("group", publishedGroup)
    inputs.property("coordinates", coordinates)
    outputs.dir(outputDir)

    doLast {
        val target = outputDir.get()
            .file("dev/rubentxu/pipeline/v2/application/SdkCoordinates.kt")
            .asFile
        target.parentFile.mkdirs()
        target.writeText(
            buildString {
                appendLine("// GENERATED FILE. Do not edit: the build writes it.")
                appendLine("//")
                appendLine("// The published coordinate of the SDK, from the ONE declaration the root")
                appendLine("// build owns (`sdkBundleGroup` and `sdkBundleArtifactNames`). The CLI reads")
                appendLine("// THIS, so the coordinates it publishes cannot drift from the coordinates")
                appendLine("// the publication declares.")
                appendLine("package dev.rubentxu.pipeline.v2.application")
                appendLine()
                appendLine("internal object SdkCoordinates {")
                appendLine("    /** The group every published coordinate carries. */")
                appendLine("    const val GROUP: String = \"$publishedGroup\"")
                appendLine()
                appendLine("    /** One published coordinate, as the CLI exposes it. */")
                appendLine("    data class Entry(val group: String, val artifact: String, val scope: String)")
                appendLine()
                appendLine("    /** Every published coordinate, in the order the CLI publishes them. */")
                appendLine("    val ENTRIES: List<Entry> = listOf(")
                coordinates.forEach { (group, artifact, scope) ->
                    appendLine("        Entry(\"$group\", \"$artifact\", \"$scope\"),")
                }
                appendLine("    )")
                appendLine("}")
            },
        )
    }
}

kotlin {
    // The generated source is part of the Kotlin source set, so the compile depends
    // on the generator and the CLI cannot be compiled against a file that is not there.
    sourceSets.getByName("main").kotlin.srcDir(generateSdkCoordinates)
}

dependencies {
    // OpenTelemetry is an implementation detail of the CLI adapter, not a published contract.
    // The SDK/autoconfigure/OTLP exporter provide the standard engine, environment configuration,
    // and wire protocol that this product should not reimplement. Kept behind explicit opt-in in
    // OpenTelemetryRuntime so the default CLI path creates no SDK threads or network exporters.
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.sdk.autoconfigure)
    implementation(libs.opentelemetry.exporter.otlp)
    // The OFFICIAL OTLP marshalers, declared explicitly because the historical
    // export writes the wire format with them. They arrive with the OTLP
    // exporter above, so this line adds NO byte to the distribution: what it
    // does is make the dependency honest — the product writes OTLP with the
    // upstream encoder instead of a second, hand-written encoder of its own,
    // which is the only way two producers of the same format stay the same
    // format.
    implementation(libs.opentelemetry.exporter.otlp.common)

    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    // The event plane is split in two: the contract above, the durable implementation below. The
    // runtime is the only thing that should be able to name OperationJournal, ReplayCursorStore or
    // SqliteEventStore, and this is the module that does.
    implementation(project(":pipeline-events-store"))
    // The durable PORTS (the journal, the cursor, the saga obligations and the lease) live in
    // `:pipeline-runtime-api`, which is the published contract. This module depends on the ports
    // DIRECTLY and not only through the adapters: a consumer that holds a journal has to be
    // able to name the port it holds.
    implementation(project(":pipeline-runtime-api"))
    // The client of the local service: the wire vocabulary of `pipelinek.service/2` and the
    // typed client that speaks it. The service and the command line both compile against
    // it, so the cable has ONE owner and the CLI's `--endpoint` path speaks exactly the
    // protocol the server serves.
    implementation(project(":pipeline-service-client"))
    // The Output Plane. Process transcript bytes are written here and nowhere else, and no
    // console event carries them. The dependency points ONE WAY - :pipeline-output depends on
    // neither :pipeline-events nor :pipeline-domain, so the event plane cannot reach back into the
    // Output plane and re-acquire an authority over the bytes.
    // The plane is split in two: the CONTRACT above, and the store below. Only the runtime
    // needs the store, so only the runtime names it - which is the point. The published artifact
    // carries no writer, so no external consumer can become a second one.
    implementation(project(":pipeline-output"))
    implementation(project(":pipeline-output-store"))
    implementation(project(":pipeline-event-harness"))
    // `:test-harness` was DELETED. Its 35 sources were QA
    // scaffolding (ContractCatalogue, Gate, CycleRunner) and its only consumer in the whole
    // build was the `testImplementation` line that this comment used to justify. Removing
    // the module removes the dependency with it; nothing in `src/main` referenced it.
    // `:pipeline-event-harness` below stays `implementation`: it is the `pipeline events
    // verify` CLI verb, dispatched from Main.kt, and is PRODUCT functionality, not harness.

    implementation(project(":pipeline-scripting-kotlin24"))
    // `ide init` reads the compiler options of the script definition, so the Gradle project it
    // writes refuses what the runner refuses. The runner reaches the definition through
    // `:pipeline-scripting-kotlin24`, which keeps it as an implementation detail.
    implementation(project(":pipeline-scripting-definition"))
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-step-sdk:files"))
    implementation(project(":pipeline-step-sdk:scm-git"))
    // The HTTP OFFICIAL_PLUGIN ships in the distribution and is
    // discovered through ServiceLoader, like scm-git and junit.
    implementation(project(":pipeline-step-sdk:http"))
    // JUnit OFFICIAL_PLUGIN is bundled into the distribution so the
    // external discovery seam can resolve it without `--plugin-jar`.
    implementation(project(":pipeline-step-sdk:junit"))
    // Utilities OFFICIAL_PLUGIN (readJson / writeJson / sha256) is bundled
    // into the distribution so the external discovery seam can resolve it without
    // `--plugin-jar`. Has build-time provenance (sha256 + version) emitted by
    // `:pipeline-step-sdk:utilities:computeUtilitiesDigest`.
    implementation(project(":pipeline-step-sdk:utilities"))
    implementation(project(":pipeline-credentials-api"))
    implementation(project(":pipeline-credentials-local"))
    implementation(project(":pipeline-credentials-multipart")) // wire CredentialMaterializer for file-based credential dispatch
    implementation(project(":pipeline-credentials-executor")) // port-driven withCredentials executor
    implementation(project(":pipeline-artefacts-local"))
    // The control plane's adapters. This module is the ONE place that composes
    // them, because it is the one place that owns the event store, the journal,
    // the lease store and the Output Plane at the same time. The module is
    // internal (not in `publishedContractModules`), so depending on it here
    // does not put an adapter on any consumer's classpath.
    implementation(project(":pipeline-runtime"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    // The four external example
    // plugins are mandatory in the release gate. Wiring them as
    // `runtimeOnly` files puts their JARs on the runtime classpath of
    // the application, which `installDist` then stages into `lib/`
    // (and the `distZip` packages alongside the four official
    // :pipeline-step-sdk plugins). The external JARs are produced by
    // `:buildExamplePlugins` in the root build, which builds the four
    // standalone plugin builds concurrently in ONE invocation.
    // These four external plugins reach the application through this `runtimeOnly` line and
    // through nothing else. That is the right shape: they are PRODUCT plugins of the shipped
    // distribution, and the release gate consumes them from there.
    runtimeOnly(files(rootDir.resolve("../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar")))
    runtimeOnly(files(rootDir.resolve("../examples/example-directive-plugin/build/libs/example-directive-plugin-0.1.0.jar")))
    runtimeOnly(files(rootDir.resolve("../examples/example-block-plugin/build/libs/example-block-plugin-0.1.0.jar")))
    // The external CONSUMER of the Saga contract, and the only bundled plugin
    // that drives a real compensation loop. It is bundled for the same reason the other three
    // are: a consumer has to reach the Saga contract the way a real plugin does —
    // from OUTSIDE, through published capabilities, with the engine owning the state.
    runtimeOnly(files(rootDir.resolve("../examples/example-saga-plugin/build/libs/example-saga-plugin-0.1.0.jar")))

    // Test wiring RESTORED because `src/test/kotlin` is no longer empty, in this
    // module or in the other 15 that have tests. The earlier removal was correct then: a JUnit
    // engine on a classpath with no class to discover is a silent green. It stopped being correct
    // as soon as there was something to discover, and leaving it out would have meant a permanent
    // green with no way to run the tests that do exist.
    // Nothing QA-shaped comes back with it. The removed lanes (`pluginContractTest`,
    // `installedContractTest`, the `-Plane` selector, tag exclusions and sharding) stay gone;
    // what returns is only enough for a plain unit test of a pure decision.
    // The runtime classpath is unchanged: the `implementation(project(":pipeline-step-sdk:junit"))`
    // line above is the PRODUCT's `junit.results` OFFICIAL_PLUGIN, not a test dependency.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The official in-memory metric reader, and nothing else from that jar.
    //
    // WHY THIS DEPENDENCY EXISTS AND WHY IT IS NOT REIMPLEMENTED HERE: the reader has to
    // agree with the SDK that produced the metric. A reader written in this project would
    // be a second understanding of the SDK's own aggregation, and a metric that this
    // project's reader accepts while a real collector rejects is a metric that was never
    // verified. The upstream artifact is the same reader the SDK's own tests use, it is
    // linked by the tests alone (nothing in the distribution links it), and it is the only
    // reason the dimension and cardinality guarantees can be asserted against something
    // other than this project's own arithmetic.
    testImplementation(libs.opentelemetry.sdk.testing)
    // The OFFICIAL OTLP model and the protobuf runtime it is generated
    // against, so the tests can read back what the historical export writes
    // with a decoder nobody in this project wrote. Both are for the tests
    // alone: neither is on the runtime classpath, neither reaches the
    // distribution, and neither is reachable from any published artifact.
    //
    // WHY THIS IS A DEPENDENCY AND NOT CODE HERE: the OTLP protobuf schema is
    // a FILE FORMAT and protobuf-java is the ENGINE that reads it. This
    // project does not own either, and a hand-written decoder to check our own
    // output would be a second understanding of the format rather than a check
    // of it — the classic way a product ends up shipping a format only it
    // can read.
    testImplementation(libs.opentelemetry.proto)
    testImplementation(libs.protobuf.java)
}

tasks.test {
    useJUnitPlatform()

    // The drift rules read documents that live OUTSIDE this module's sources:
    // the README, the policy and the observability example. Gradle's
    // up-to-date check only sees DECLARED inputs, so without this declaration
    // the task is UP-TO-DATE after a change to any of them and the rules that
    // read them never run — a green over a document nobody looked at. The
    // subject of the rule is the document, so the document is an input of the
    // task that checks it.
    inputs.files(
        rootProject.file("../README.md"),
        rootProject.file("../README.es.md"),
        rootProject.file("../AGENTS.md"),
        // The maturity manifest is the OTHER document the drift rules read: it
        // classifies the published surfaces, and the rule that compares it with
        // the product asks the product. A manifest that changed without the
        // lane noticing would leave a report about a classification that is
        // no longer the one in the tree.
        rootProject.file("contract/published-contract-maturity.json"),
        rootProject.file("../examples/observability"),
        // The OCI profile is a file of the tree and not a document about it,
        // and the rules that read its shape read these two. Without the
        // declaration the lane is UP-TO-DATE after they change, and the
        // report it leaves is the report of a profile that is no longer the
        // one in the tree.
        file("oci/Containerfile"),
        rootProject.file("../scripts/build-oci-image.sh"),
    )
    // The example plugins are a subject by NAME and not by content: the rule
    // asks which plugin directories exist, so hashing their build outputs
    // would invalidate this task on every build in exchange for nothing.
    inputs.property("driftExamplePluginNames", rootProject.provider {
        rootProject.file("../examples").listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    })
}

// The integration lane reads the README as well: it checks that every command
// form the README's quickstart names is a form this CLI dispatches. The lane
// declares the document it reads for the same reason the unit lane does — a
// document that is not an input is a document the lane does not look at, and
// the report it leaves behind is the report of a README that is no longer
// there.
//
// The lane also measures the DISTRIBUTION: the ZIP the installer publishes is
// an input of the lane, and the lane is what builds it. A lane that read a
// ZIP that another build wrote is a lane that measures a build nobody ran,
// and a distribution measured from a stale ZIP is a distribution nobody holds.
//
// The three facts the lane needs are handed over as system properties, and
// they are handed over as VALUES: a path that the lane composed itself would
// be a second derivation of a fact the build already knows.
val distributionArchive = tasks.named<Zip>("distZip").flatMap { it.archiveFile }

tasks.named<Test>("integrationTest") {
    inputs.file(rootProject.file("../README.md"))
    dependsOn(tasks.named("distZip"))
    systemProperty("pipelinek.distribution.zip", distributionArchive.get().asFile.absolutePath)
    systemProperty("pipelinek.distribution.version", project.version.toString())
    systemProperty("pipelinek.repository.root", rootProject.projectDir.parentFile.absolutePath)
}

// TEST LANES REMOVED. Five separate registrations were deleted
// from this build file, all of them reading a source set that is now empty:
//   * `pluginContractTest`            -- its own SourceSet + `Test` task + `check` wiring.
//   * `installedContractTest`         -- the shipped-binary certification task.
//   * `-Plane=fast|installed|release` -- the selector that chose between the two tasks.
//   * `tasks.test { ... }`            -- useJUnitPlatform, tag exclusions, shard switches,
//                                         the SQLite native system property, and two
//                                         cross-module `inputs.files(...)` fitness inputs.
//   * `forkEveryPerClass`             -- declared once, read only by `tasks.test`.
// The mechanism they shared is the reason the whole set had to go together. Gradle 8.14 has no
// `Test.failOnNoDiscoveredTests` property, and a `Test` task whose source set is empty reports
// GREEN while executing zero classes. Each surviving registration would have printed a
// certification that never ran: a plugin certification, a shipped-binary certification, and a
// fast lane passing over an empty classpath. `check` aggregates all of them. This is exactly
// the false compliance the current testing policy exists to prevent.
// Two guards that were inside those blocks are removed with them, and neither is a loss:
//   * the INVARIANT `doFirst { check(...) }` refused to let `:test` depend on the
//     three external plugin builds. Its subject was a lane split that no longer exists --
//     there is one test configuration left in this build and it has no plugin-build edge.
//   * `forkEveryPerClass` was read by `tasks.test` and by nothing else. Left declared, an
//     unread `-PpipelineTestForkEvery` would have looked like a live tuning knob.
// Their replacement is the gate: `scripts/verify-all.sh` builds the real distribution once
// (`:pipeline-application:installDist`, stage 2) and then runs the unit tests, the integration
// tests and the mutants against it. That path exercises the shipped artifact, so it certifies
// behaviour rather than asserting a tag on an empty source set.

// The launcher of the distribution.
//
// The `application` plugin generates a start script that knows how to find a
// JVM and how to spell a classpath, and nothing else. The product needs to
// know three more things before it starts, and the only place that can know
// them is the place that is about to run the JVM:
//
//   * where the distribution is installed, so that what the product keeps
//     beside its jars stays beside its jars when the installation moves;
//   * which JVM is running it, because a shared class archive is only valid
//     for the JVM that wrote it;
//   * whether the jars on disk are still the jars the archive was built from,
//     because the JVM does NOT check that, and an archive written against
//     yesterday's jars runs yesterday's classes.
//
// So the distribution ships the product's own launcher, and the generated one
// is replaced by it. The three values the launcher needs that are facts about
// the BUILD — the main class, the default JVM options and the classpath — are
// written into it from the same source the application plugin uses, so the
// distribution keeps ONE authority for what it is made of.
//
// It is the START SCRIPT TASK that writes it, and that is not a detail: the
// distribution — the ZIP that is downloaded and the directory that is
// installed — is assembled from what that task writes, so a launcher written
// anywhere else would leave the distribution shipping the generated script.
val launcherTemplate = layout.projectDirectory.file("launcher/pipelinek")

tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    // The template IS the script this task writes, so it is one of its inputs:
    // a launcher that changed and a build that did not run would ship the
    // launcher of the last build.
    inputs.file(launcherTemplate)
    unixStartScriptGenerator = org.gradle.jvm.application.scripts.ScriptGenerator { details, writer ->
        // The classpath is the one the application plugin computed for its own
        // start script, in the same order: the product reads it from
        // `java.class.path` to find its bundled plugins, and a different order
        // would be a different distribution.
        // The entries are relative to the home of the distribution, and the
        // launcher resolves them against ITS OWN home, so an installation that
        // is moved keeps working and a launcher started from another directory
        // does not look for the product in the current one.
        val classpath = details.classpath.joinToString(File.pathSeparator) {
            "\$APP_HOME/lib/" + File(it).name
        }
        check(classpath.isNotEmpty()) {
            "the distribution's classpath is empty, so the launcher would start the product " +
                "with nothing to run: refusing to write a launcher that cannot work"
        }
        val rendered = launcherTemplate.asFile.readText()
            .replace("@MAIN_CLASS@", details.mainClassName)
            .replace("@DEFAULT_JVM_OPTS@", details.defaultJvmOpts.joinToString(" "))
            .replace("@CLASSPATH@", classpath)
        // A launcher that still names a placeholder starts nothing, and the
        // distribution would ship it as if it worked.
        val placeholder = Regex("@[A-Z_]+@").find(rendered)
        check(placeholder == null) {
            "the rendered launcher still holds the placeholder ${placeholder?.value}: " +
                "refusing to write a launcher that cannot start the product"
        }
        writer.write(rendered)
    }
}
