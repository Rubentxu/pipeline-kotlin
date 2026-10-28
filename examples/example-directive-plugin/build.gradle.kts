import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "example.lock"
version = "0.1.0"

// INDEPENDENT BUILD: mirrors examples/example-uppercase-plugin. Depends ONLY on
// public SDK artifacts (pipeline-domain: DirectiveContributor/registry contracts
// + domain value types; pipeline-scripting-api is NOT needed: the directive DSL
// already accepts open-world keys as plain strings, so no DSL extension is
// required for directives).
//   -PsdkRepo=<dir>     repository holding the SDK artifacts
//   -PsdkVersion=<ver>  SDK version to resolve
val sdkRepo: String = providers.gradleProperty("sdkRepo").getOrElse("../../pipelinek/build/sdk-repo")

// No default version, on purpose. `0.1.0-SNAPSHOT` was here for a long time and it cannot resolve
// anything: the build then failed in a dependency-resolution message naming a version nobody asked
// for, hundreds of lines below the line that matters. The build already omitted `-PsdkVersion`
// once, on, and paid exactly that. Failing here names the real cause.
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    """
    -PsdkVersion is required: this plugin resolves the published SDK contracts and has no default
    version, because a default could only be one that fails to resolve. Pass the candidate's
    version explicitly, e.g. -PsdkVersion=0.47.0
    """.trimIndent()
}

// ----------------------------------------------------------------------------
// The apiRange is derived from the resolved SDK.
// It used to be a Kotlin literal, `PipelineKApiRange(SemVer(0,47,0), SemVer(0,57,0))`, which went
// stale the moment the host moved: 0.56.0 fell outside `[0.47.0, 0.57.0)` only by accident of how
// the official plugins evolved, and the directive plugin would have stayed broken. A literal held
// by a plugin that does not control the host's release cadence cannot stay correct.
// Two derivations, deliberately not one:
//   - CEILING from sdkVersion, because the host version is the thing that moves.
//   - FLOOR from `directiveApiRangeMin`, a property of THIS plugin, because how far back it works
//     is a claim about this plugin that no other build can make.
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

// The floor is the plugin's own property.: with no measured evidence that
// this plugin works against an older SDK, the conservative floor IS the resolved SDK version.
val directiveApiRangeMin: String = providers.gradleProperty("directiveApiRangeMin").getOrElse(sdkVersion)
val directiveApiRangeCeiling: String = nextMinor

repositories {
    mavenCentral()
    maven {
        name = "sdk"
        url = uri(sdkRepo)
    }
}

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    // Only the plugin's own classes + the ServiceLoader descriptor resource.
    // Reproducible by construction. Without these two, the archive carries
    // the source files' mtimes and orders entries by filesystem iteration,
    // so the same commit produces a different digest in every checkout --
    // Which makes the byte pin impossible to hold anywhere but the
    // machine that set it.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ----------------------------------------------------------------------------
// Provenance and the manifest DOCUMENT, both derived, neither typed by hand.
// This plugin declared itself through META-INF/services and shipped no manifest, so pass 1 of
// The composition would refuse it exactly as it refused example-block-plugin. The ServiceLoader
// descriptor asserts that the artifact CONTRIBUTES; the manifest asserts WHAT it contributes.
// The digest is measured over this artifact's own content EXCLUDING the release-properties
// document it is about to write, so the value has a fixed point. A digest that included the file
// carrying it could not be computed at all.
// ----------------------------------------------------------------------------

val directiveReleaseProps =
    layout.buildDirectory.file("resources/main/META-INF/example-directive-release.properties")

val directivePublisher = providers
    .gradleProperty("pipeline.example.directive.publisher")
    .orElse("example-directive")
val directiveNamespace = providers
    .gradleProperty("pipeline.example.directive.namespace")
    .orElse("example-directive-plugin")

/**
 * The SDK, on the runtime classpath of the BUILD-TIME emitter only.
 * Every dependency above is `compileOnly`, and that is the plugin's whole point: it ships classes
 * only, and the host supplies the contracts at runtime. So `sourceSets.main.runtimeClasspath` is
 * EMPTY of them and the emitter would die with `NoClassDefFoundError: PluginManifestCodec`. This
 * configuration never reaches `jar`: widening the plugin's own runtime scope to make a build task
 * work would put PipelineK's contracts inside an artifact whose defining property is that it
 * carries only its own classes.
*/
val sdkForManifestEmission by configurations.creating {
    extendsFrom(configurations.compileOnly.get())
}

/**
 * The SDK this plugin compiles against is an INPUT of the compile task, declared here.
 *
 * ## What was measured
 * The compile task did not track the resolved SDK on its own. In the SAME tree, pointing
 * `-PsdkRepo`/`-PsdkVersion` at another published SDK left `compileKotlin` UP-TO-DATE, while the
 * release properties and the manifest WERE recomputed -- they declare `sdkVersion` as an input.
 * The artifact then declared a compatibility range for an SDK whose classes it had not been
 * compiled against: a claim the bytes could not back. Measured, not predicted: the resolved
 * compile classpath was printed, its jar digest confirmed to be the modified one, and the task
 * still reported UP-TO-DATE.
 *
 * ## Why nothing noticed
 * Every observation that reads the artifact's DECLARATION agreed with itself: the manifest, the
 * range and the provenance all moved together. Only the BINARY disagreed, and nothing in the
 * build compared the binary with the SDK it claims to be compiled against.
 *
 * ## The fix
 * The resolved SDK is declared as an input, normalized by CONTENT, so a change in the SDK's API
 * re-runs the compilation. The classes and the declaration then come from the same SDK, which is
 * the only way the declared range means anything.
 */
tasks.named("compileKotlin") {
    inputs.files(configurations.named("sdkForManifestEmission"))
        .withNormalizer(ClasspathNormalizer::class)
}

val computeDirectiveRelease = tasks.register("computeDirectiveRelease") {
    group = "directive"
    description = "Compute the external directive plugin's provenance SHA-256 and write its release properties."

    val propsFile = directiveReleaseProps
    val publisherName = directivePublisher
    val namespaceName = directiveNamespace
    val releaseVersion = version.toString()

    // Declare sdkVersion as an explicit input so the
    // apiRange is regenerated when the host version changes (see
    // example-block-plugin for the same change with the full story).
    inputs.property("sdkVersion", sdkVersion)
    inputs.property("directiveApiRangeMin", directiveApiRangeMin)

    dependsOn("compileKotlin", "processResources")
    outputs.file(propsFile)

    doLast {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = propsFile.get().asFile
        // The digest MUST exclude both this provenance file (it carries the digest itself, so
        // including it would be a fixed-point equation with no solution) AND the
        // plugin-manifest.json emitted by `emitDirectiveManifest`, which can be left in
        // `resourcesDir` by a previous run of that task and so reintroduced into the digest input
        // on every re-execution of `computeDirectiveRelease`. Including the manifest's bytes
        // makes the digest depend on content whose own value contains the digest, so on a polluted
        // re-run the recorded SHA shifts in lockstep with the embedded one — a different SHA per
        // worktree, per `--rerun-tasks` flag, and per build cache state. Excluding the manifest
        // breaks the loop and makes the digest a pure function of the plugin's source-derived
        // bytes. The manifest IS still packed into the artifact (by `jar`, downstream); it just
        // doesn't feed the digest this task measures.
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
                appendLine("pipeline.example.directive.publisher=${publisherName.get()}")
                appendLine("pipeline.example.directive.namespace=${namespaceName.get()}")
                appendLine("pipeline.example.directive.release.version=$releaseVersion")
                appendLine("pipeline.example.directive.release.digest=sha256:$hex")
                // The compatibility range is DERIVED, not typed.
                // The ceiling is the NEXT MINOR of the resolved SDK, not the resolved version itself:
                // PipelineKApiRange.accepts is `runtime < untilExclusive`, so a ceiling equal to
                // sdkVersion would make the plugin refuse the very host that loads it.
                appendLine("pipeline.example.directive.compatibility.min=$directiveApiRangeMin")
                appendLine("pipeline.example.directive.compatibility.untilExclusive=$nextMinor")
            },
        )
        println("directive: provenance written to $out (digest=sha256:${hex.take(16)}...)")
        println("directive: apiRange=[$directiveApiRangeMin, $nextMinor) derived from sdkVersion=$sdkVersion")
    }
}

val directiveManifest =
    layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitDirectiveManifest = tasks.register<JavaExec>("emitDirectiveManifest") {
    group = "directive"
    description = "emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeDirectiveRelease)
    mainClass.set("example.lock.LockPluginDeclarationKt")

    classpath = files(
        sourceSets["main"].runtimeClasspath,
        layout.buildDirectory.dir("resources/main"),
        configurations.named("sdkForManifestEmission"),
    )

    inputs.files(directiveReleaseProps)
    outputs.file(directiveManifest)

    val out = directiveManifest
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
                "manifest emission produced no output. The plugin would ship without " +
                    "META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("directive: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") {
    finalizedBy(emitDirectiveManifest)
}

tasks.named("jar") {
    dependsOn(emitDirectiveManifest)
}
