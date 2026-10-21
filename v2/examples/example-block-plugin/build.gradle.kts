import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "example.block"
version = "0.1.0"

// INDEPENDENT BUILD (WU-RP-035 / slice D): separate Gradle boundary, same lane as the
// atomic example plugin. It depends ONLY on public SDK artifacts (pipeline-domain for the
// Step contract, body-continuation port and capability; pipeline-scripting-api for the DSL
// facade surface). No import of pipeline-application, no engine or journal dependency.
//
// A plugin that needed a core change to reach its own body could not live behind this
// boundary, which is the point: if the seam is not open, this build does not compile.
//
//   -PsdkRepo=<dir>     repository holding the SDK artifacts
//   -PsdkVersion=<ver>  SDK version to resolve
val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../v2/build/sdk-repo")

// No default version, on purpose. `0.1.0-SNAPSHOT` was here for a long time and it cannot resolve
// anything: the build then failed in a dependency-resolution message naming a version nobody asked
// for, hundreds of lines below the line that matters. The v2 build already omitted `-PsdkVersion`
// once, on 2026-09-19, and paid exactly that. Failing here names the real cause.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this plugin resolves the published SDK contracts and has no default
    version, because a default could only be one that fails to resolve. Pass the candidate's
    version explicitly, e.g. -PsdkVersion=0.47.0
    """.trimIndent()
}

// ----------------------------------------------------------------------------
// the apiRange is derived from the resolved SDK.
//
// It used to be a Kotlin literal, `PipelineKApiRange(SemVer(0,47,0), SemVer(0,53,0))`, which went
// stale the moment the host moved: 0.56.0 fell outside `[0.47.0, 0.53.0)` and admission refused
// the plugin on its own distribution. A literal in a plugin that does not control the host's
// release cadence cannot stay correct.
//
// Two derivations, deliberately not one:
//   - CEILING from sdkVersion, because the host version is the thing that moves.
//   - FLOOR from `blockApiRangeMin`, a property of THIS plugin, because how far back it works
//     is a claim about this plugin that no other build can make.
//
// Fail-closed on a malformed version: a ceiling that silently became "0.0.0" would produce a
// range that refuses every host, which looks like a plugin defect rather than a bad property.
val sdkCore: Triple<Int, Int, Int> = sdkVersion.substringBefore("-").let { raw ->
    val parts = raw.split(".")
    require(parts.size == 3) {
        "sdkVersion must be MAJOR.MINOR.PATCH[-qualifier] (got '$sdkVersion'); refusing to derive a range"
    }
    Triple(parts[0].toIntOrNull() ?: -1, parts[1].toIntOrNull() ?: -1, parts[2].toIntOrNull() ?: -1)
}
require(sdkCore.first >= 0 && sdkCore.second >= 0 && sdkCore.third >= 0) {
    "sdkVersion has a non-numeric component (got '$sdkVersion'); refusing to derive a range"
}

// Next MINOR, not the version itself: `accepts()` is `runtime < untilExclusive`, so a ceiling
// equal to sdkVersion would make this plugin refuse the host that loads it.
val nextMinor: String = "${sdkCore.first}.${sdkCore.second + 1}.0"

// D1 rule 3: the floor is the plugin's own property. D1 rule 4: with no measured evidence that
// this plugin works against an older SDK, the conservative floor IS the resolved SDK version.
// The previous value, 0.47.0, was inherited from the official plugins' certified floor and was
// never measured for this fixture.
val blockApiRangeMin: String = providers.gradleProperty("blockApiRangeMin").getOrElse(sdkVersion)
val blockApiRangeCeiling: String = nextMinor

repositories {
    mavenCentral()
    maven {
        name = "sdk"
        url = uri(sdkRepo)
    }
}

// The SDK is published as a SNAPSHOT. Gradle's default snapshot cache TTL is 24h,
// which would let this build compile against an SDK that no longer matches the
// repository. Re-resolve on every build instead.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    // The plugin JAR carries only its own classes. The ServiceLoader descriptor is a
    // resource; the SDK contracts come from the host at runtime.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ----------------------------------------------------------------------------
// S6/I — provenance and the manifest DOCUMENT, both derived, neither typed by hand.
//
// This plugin declared itself through META-INF/services and shipped no manifest, which made it an
// UNADMITTED plugin: once pass 1 of S6/COMPOSITION reached the product, `PreLoadPluginAdmission`
// refused this artifact and every run carrying it on the classpath exited 2. The ServiceLoader
// descriptor is a claim that the artifact CONTRIBUTES; the manifest is the claim about WHAT. This
// block supplies the second half, derived from the code rather than typed beside it.
//
// The digest is measured over this artifact's own content EXCLUDING the release-properties
// document it is about to write, so the value has a fixed point. A digest that included the file
// carrying it could not be computed at all.
// ----------------------------------------------------------------------------

val blockReleaseProps =
    layout.buildDirectory.file("resources/main/META-INF/example-block-release.properties")

val blockPublisher = providers
    .gradleProperty("pipeline.example.block.publisher")
    .orElse("example-block")
val blockNamespace = providers
    .gradleProperty("pipeline.example.block.namespace")
    .orElse("example-block-plugin")

/**
 * The SDK, on the runtime classpath of the BUILD-TIME emitter only.
 *
 * Every dependency above is `compileOnly`, and that is the plugin's whole point: it ships classes
 * only, and the host supplies the contracts at runtime. So `sourceSets.main.runtimeClasspath` is
 * EMPTY of them and the emitter would die with `NoClassDefFoundError: PluginManifestCodec`. This
 * configuration does not leak into the JAR, because widening the plugin's own runtime scope to
 * make a build task work would put PipelineK's contracts inside an artifact whose defining
 * property is that it carries only its own classes.
 */
val sdkForManifestEmission by configurations.creating {
    extendsFrom(configurations.compileOnly.get())
}

val computeBlockRelease = tasks.register("computeBlockRelease") {
    group = "block"
    description = "Compute the external block plugin's provenance SHA-256 and write its release properties."

    val propsFile = blockReleaseProps
    val publisherName = blockPublisher
    val namespaceName = blockNamespace
    val releaseVersion = version.toString()

    // R4 / WU-R4.0.0 — declare sdkVersion as an explicit input. Without this, the
    // `release-properties` document (and downstream the apiRange in
    // `plugin-manifest.json`) goes STALE when the host version changes but the
    // SDK jar content does not: Gradle's default fingerprint does not see the
    // `-PsdkVersion=...` Gradle property, so a host version bump from 0.59.0 to
    // 0.60.0 with no source change produced a build whose apiRange was still
    // [0.59.0, 0.60.0). The shipped installDist then refused the example
    // plugins for IncompatibleApiRange, which made every `pipelinek run` exit
    // 2 BEFORE the run body compiled.
    inputs.property("sdkVersion", sdkVersion)
    inputs.property("blockApiRangeMin", blockApiRangeMin)

    dependsOn("compileKotlin", "processResources")
    outputs.file(propsFile)

    doLast {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = propsFile.get().asFile
        // The digest MUST exclude both this provenance file (it carries the digest itself, so
        // including it would be a fixed-point equation with no solution) AND the
        // plugin-manifest.json emitted by `emitBlockManifest`, which can be left in
        // `resourcesDir` by a previous run of that task and so reintroduced into the digest input
        // on every re-execution of `computeBlockRelease`. Including the manifest's bytes
        // makes the digest depend on content whose own value contains the digest, so on a polluted
        // re-run the recorded SHA shifts in lockstep with the embedded one -- a different SHA per
        // worktree, per `--rerun-tasks` flag, and per build cache state. Excluding the manifest
        // breaks the loop and makes the digest a pure function of the plugin's source-derived
        // bytes. The manifest IS still packed into the artifact (by `jar`, downstream); it just
        // doesn't feed the digest this task measures. This is the same shape already applied and
        // verified in `example-directive-plugin`; see DirectivePluginShaReproducibilityTest.
        val manifestPath = layout.buildDirectory
            .file("resources/main/META-INF/pipelinek/plugin-manifest.json")
            .get().asFile.absolutePath
        val excluded = setOf(out.absolutePath, manifestPath)

        val files = listOf(classesDir, resourcesDir)
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.absolutePath !in excluded }
                    .map { it.absolutePath }
                    .toList()
            }
            .sorted()

        require(files.isNotEmpty()) {
            "no class or resource files to hash; refusing to emit provenance for an empty artifact"
        }

        // Hash each file's RELATIVE name with its bytes, in sorted order. The name matters as much
        // as the content: two artifacts that swap a class for a resource of the same length would
        // otherwise hash identically.
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in files) {
            val file = file(path)
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            digest.update(file.readBytes())
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }

        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("pipeline.example.block.publisher=${publisherName.get()}")
                appendLine("pipeline.example.block.namespace=${namespaceName.get()}")
                appendLine("pipeline.example.block.release.version=$releaseVersion")
                appendLine("pipeline.example.block.release.digest=sha256:$hex")
                // the compatibility range is DERIVED, not typed.
                //
                // The ceiling is the NEXT MINOR of the resolved SDK, not the resolved version itself:
                // PipelineKApiRange.accepts() is `runtime < untilExclusive`, so a ceiling equal to
                // sdkVersion would make the plugin refuse the very host that loads it.
                appendLine("pipeline.example.block.compatibility.min=$blockApiRangeMin")
                appendLine("pipeline.example.block.compatibility.untilExclusive=$nextMinor")
            },
        )
        println("block: provenance written to $out (digest=sha256:${hex.take(16)}...)")
        println("block: apiRange=[$blockApiRangeMin, $nextMinor) derived from sdkVersion=$sdkVersion")
    }
}

val blockManifest =
    layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitBlockManifest = tasks.register<JavaExec>("emitBlockManifest") {
    group = "block"
    description = "S6/I: emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeBlockRelease)
    mainClass.set("example.block.RepeatPluginDeclarationKt")

    classpath = files(
        sourceSets["main"].runtimeClasspath,
        layout.buildDirectory.dir("resources/main"),
        configurations.named("sdkForManifestEmission"),
    )

    inputs.files(blockReleaseProps)
    outputs.file(blockManifest)

    val out = blockManifest
    val captured = ByteArrayOutputStream()
    standardOutput = captured

    doFirst {
        out.get().asFile.parentFile.mkdirs()
        // The buffer lives for the whole configuration, so a second execution in the same build
        // would APPEND to the first document and emit concatenated JSON.
        captured.reset()
    }

    doLast {
        val text = captured.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            throw GradleException(
                "S6/I: manifest emission produced no output. The plugin would ship without " +
                    "META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("block: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") {
    finalizedBy(emitBlockManifest)
}

tasks.named("jar") {
    dependsOn(emitBlockManifest)
}
