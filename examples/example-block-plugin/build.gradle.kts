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

    dependsOn("compileKotlin", "processResources")
    outputs.file(propsFile)

    doLast {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = propsFile.get().asFile
        val excluded = out.absolutePath

        val files = listOf(classesDir, resourcesDir)
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.absolutePath != excluded }
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
            },
        )
        println("block: provenance written to $out (digest=sha256:${hex.take(16)}...)")
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
