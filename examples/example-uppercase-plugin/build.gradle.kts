plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

// `java` resolves to JavaPluginExtension inside a Kotlin DSL script, so this import is what makes
// the unqualified name below mean java.io.ByteArrayOutputStream.
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

group = "example.uppercase"
version = "0.1.0"

// INDEPENDENT BUILD (EP-3): separate Gradle boundary. It depends ONLY on public
// SDK artifacts (pipeline-domain: StepDefinitionContributor/StepRegistry contracts
// + domain value types; pipeline-scripting-api: the DSL facade surface). No
// internal application/runtime imports.
//
// Lane R: the SDK is consumed as ordinary module coordinates resolved from a
// build-local Maven repository produced by the v2 build from the SAME source
// revision. It is deliberately NOT a `files(...)` dependency on a committed
// jar: a stale SDK must fail this build rather than silently satisfy it.
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
    // P3-D / S6.4: the Event Plane's published contract. Carrying the plugin's own event
    // definition and the emission seam as `compileOnly` is the point: the JAR ships only its
    // classes, and the host supplies the registry, the codec types and the capability at runtime.
    // A plugin that had to bundle the Event Plane would be able to run a SECOND registry beside
    // the host's, which is exactly the two-authorities split this seam exists to prevent.
    compileOnly("dev.rubentxu.pipeline.v2:pipeline-events:$sdkVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    // Nothing else: the plugin JAR carries only its own classes. The ServiceLoader
    // descriptor (EP-4) is a resource; the SDK contracts come from the host at runtime.
}

// ----------------------------------------------------------------------------
// S6/I — provenance and the manifest DOCUMENT, both derived, neither typed by hand.
//
// Until this block the plugin shipped no manifest at all, which meant it was never ADMITTED:
// `PluginAdmissionGate` refuses any contributor whose artifact lacks
// META-INF/pipelinek/plugin-manifest.json. It ran because `PluginComposition.resolve` discovers
// contributors on a path that does not consult the gate. Naming that here is the point; the two
// tasks below are what close it.
//
// The digest is measured over this artifact's own content EXCLUDING the release-properties
// document it is about to write, so the value has a fixed point. A digest that included the file
// carrying it could not be computed at all.
// ----------------------------------------------------------------------------

val uppercaseReleaseProps =
    layout.buildDirectory.file("resources/main/META-INF/example-uppercase-release.properties")

val uppercasePublisher = providers
    .gradleProperty("pipeline.example.uppercase.publisher")
    .orElse("example-uppercase")
val uppercaseNamespace = providers
    .gradleProperty("pipeline.example.uppercase.namespace")
    .orElse("example-uppercase-plugin")

/**
 * The SDK, on the runtime classpath of the BUILD-TIME emitter only.
 *
 * Every dependency above is `compileOnly`, and that is the plugin's whole point: it ships classes
 * only, and the host supplies the contracts at runtime. The consequence is that
 * `sourceSets.main.runtimeClasspath` is EMPTY of them, and the manifest emitter — which has to run
 * the real declaration against the real codec — dies with `NoClassDefFoundError:
 * PluginManifestCodec`. Measured, not predicted: that is the first run of this task.
 *
 * So the emitter gets its own configuration. It does not leak into the JAR: this configuration
 * never reaches `jar`, and widening the plugin's own runtime scope to make a build task work would
 * have put PipelineK's contracts inside an artifact whose defining property is that it carries
 * only its own classes.
 */
val sdkForManifestEmission by configurations.creating {
    extendsFrom(configurations.compileOnly.get())
}

val computeUppercaseRelease = tasks.register("computeUppercaseRelease") {
    group = "uppercase"
    description = "Compute the external plugin's provenance SHA-256 and write its release properties."

    val propsFile = uppercaseReleaseProps
    val publisherName = uppercasePublisher
    val namespaceName = uppercaseNamespace
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
                appendLine("pipeline.example.uppercase.publisher=${publisherName.get()}")
                appendLine("pipeline.example.uppercase.namespace=${namespaceName.get()}")
                appendLine("pipeline.example.uppercase.release.version=$releaseVersion")
                appendLine("pipeline.example.uppercase.release.digest=sha256:$hex")
            },
        )
        println("uppercase: provenance written to $out (digest=sha256:${hex.take(16)}...)")
    }
}

val uppercaseManifest =
    layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitUppercaseManifest = tasks.register<JavaExec>("emitUppercaseManifest") {
    group = "uppercase"
    description = "S6/I: emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeUppercaseRelease)
    mainClass.set("example.uppercase.UppercasePluginDeclarationKt")

    // The main() reads its release properties off ITS OWN classpath, so the freshly generated
    // resources directory has to be on it — and so do the SDK contracts, which are compileOnly and
    // therefore absent from runtimeClasspath. See sdkForManifestEmission for why that is right.
    // `files(...)` rather than `+`: mixing a FileCollection with a NamedDomainObjectProvider
    // resolves to a List at script-compile time, and JavaExec.classpath is a FileCollection.
    classpath = files(
        sourceSets["main"].runtimeClasspath,
        layout.buildDirectory.dir("resources/main"),
        configurations.named("sdkForManifestEmission"),
    )

    inputs.files(uppercaseReleaseProps)
    inputs.property("apiRange", "[0.47.0, 0.49.0)")
    outputs.file(uppercaseManifest)

    val out = uppercaseManifest
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
        println("uppercase: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") {
    finalizedBy(emitUppercaseManifest)
}

tasks.named("jar") {
    dependsOn(emitUppercaseManifest)
}
