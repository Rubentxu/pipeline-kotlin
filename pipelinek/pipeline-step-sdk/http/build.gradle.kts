import dev.rubentxu.pipeline.build.PluginCompatibilityPolicy
import dev.rubentxu.pipeline.build.ProvenanceDigest

plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-credentials-api"))
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

}

group = "dev.rubentxu.pipeline.v2"

/**
 * The one hashing primitive the provenance seam uses lives in `buildSrc`
 * (`dev.rubentxu.pipeline.build.ProvenanceDigest`), shared with scm-git and utilities.
 * Keeping three private copies is what let a fix be applied by hand in three places and
 * left the next fix able to miss two of them.
 */

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// ----------------------------------------------------------------------------
// HTTP OFFICIAL_PLUGIN provenance seam (STEP_ECOSYSTEM_POLICY)
// Mirrors the scm-git seam deliberately: the contributor refuses to register
// without the real SHA-256 of its own artefact. A plugin that cannot prove what
// it is must not be admitted into a run.
// Determinism: the digest is the SHA-256 of the sorted `sha256sum` output of
// every compiled class file and processed resource, so it depends on the source
// revision and NOT on JAR packaging timestamps.
// ----------------------------------------------------------------------------

val httpPublisher = providers.gradleProperty("pipeline.http.publisher").orElse("pipeline-kotlin")
val httpNamespace = providers.gradleProperty("pipeline.http.namespace").orElse("pipeline-plugin-http")
val httpVersion = providers.gradleProperty("pipeline.http.release.version").orElse(version.toString())

val httpReleaseProps = layout.buildDirectory.file("resources/main/META-INF/http-release.properties")

val httpClassesDir = layout.buildDirectory.dir("classes/kotlin/main")
val httpResourcesDir = layout.buildDirectory.dir("resources/main")

// Exclusions by EXACT relative path, never by name suffix. The previous filter dropped
// any file ending with `plugin-manifest.json`, which would also drop an unrelated
// `unrelated-plugin-manifest.json` from the identity. The three entries are the only documents
// the plugin generates into its own artifact, and hashing them is a fixed-point loop because
// they carry the digest.
val httpExcludedResourcePaths = setOf(
    "META-INF/http-release.properties",
    "META-INF/http-release.properties.digest",
    "META-INF/pipelinek/plugin-manifest.json",
)

val computeHttpDigest = tasks.register("computeHttpDigest") {
    group = "http"
    description = "Compute the HTTP OFFICIAL_PLUGIN provenance SHA-256 (deterministic over class files + resources)."

    val publisher = httpPublisher
    val namespace = httpNamespace
    val ver = httpVersion
    // The paths and the exclusion set are taken HERE, while the build is
    // configured, so that the action below carries the VALUES and not the
    // script that owns them. An action that reaches into the script it was
    // declared in cannot be stored in the configuration cache: the cache
    // refuses to serialize a reference to a build script, and the task then
    // runs against a script that is not there.
    val classesDir = httpClassesDir
    val resourcesDir = httpResourcesDir
    val releaseProps = httpReleaseProps
    val excludedResourcePaths = httpExcludedResourcePaths.toList()

    outputs.file(httpReleaseProps)
    dependsOn("compileKotlin", "processResources")

    // Declared inputs so Gradle can re-run this task when the material changes (it is no longer
    // unconditionally always-running). Class dirs and resources dirs are the hashed tree; the
    // three generated documents are excluded from the resource snapshot so that updating them
    // does not retrigger the task and cannot feed back into the digest. Path sensitivity is
    // RELATIVE: the task's own up-to-date key must be checkout-independent too.
    inputs.files(fileTree(httpClassesDir)).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(httpResourcesDir) { exclude(httpExcludedResourcePaths) })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("publisher", publisher)
    inputs.property("namespace", namespace)
    inputs.property("version", ver)
    inputs.property("module", "http")

    // The digest is PLAIN COMPUTATION rather than a shell pipeline, and the one hashing
    // primitive is shared with scm-git and utilities via buildSrc. It used to be
    // `tasks.register<Exec>`; keeping Exec without a commandLine fails with
    // "A problem occurred starting process 'command 'null''".
    doLast {
        val classesDirFile = classesDir.get().asFile
               require(classesDirFile.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDirFile = resourcesDir.get().asFile
        val out = releaseProps.get().asFile
        out.parentFile.mkdirs()
        // This used to be `sh -c "sha256sum <ABSOLUTE PATHS> | sha256sum"`, and
        // `sha256sum` PRINTS THE FILENAME IT WAS GIVEN, so two checkouts of identical bytes
        // produced different digests. The shared ProvenanceDigest frames only the root name and
        // the relative path, so the absolute checkout path never enters the material.
        val hex = ProvenanceDigest.computeDigestHex(
            listOf(
                ProvenanceDigest.Root("classes", classesDirFile.toPath()),
                ProvenanceDigest.Root("resources", resourcesDirFile.toPath()),
            ),
            excludedResourcePaths.mapTo(linkedSetOf()) { "resources/$it" },
        )
        require(hex.length == 64) { "Expected 64-hex SHA-256, got '${hex.take(80)}'" }
        val digest = "sha256:$hex"
        out.writeText(
            buildString {
                appendLine("pipeline.http.publisher=${publisher.get()}")
                appendLine("pipeline.http.namespace=${namespace.get()}")
                appendLine("pipeline.http.release.version=${ver.get()}")
                appendLine("pipeline.http.release.digest=$digest")
                appendLine("pipeline.http.module=http")
            },
        )
        println("http: provenance written to $out (digest=${digest.take(20)}...)")
    }
}

tasks.named("jar") {
    dependsOn(computeHttpDigest)
}

val httpReleasePropsFile = httpReleaseProps

fun loadPropsAsMap(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val map = linkedMapOf<String, String>()
    file.useLines { lines ->
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
        }
    }
    return map
}

tasks.withType<Test>().configureEach {
    val props = loadPropsAsMap(httpReleasePropsFile.get().asFile)
    if (props.isNotEmpty()) {
        for ((k, v) in props) systemProperty(k, v)
        systemProperty("pipeline.http.release.digest.missing", "false")
    } else {
        systemProperty("pipeline.http.release.digest", "sha256:" + "0".repeat(64))
        systemProperty("pipeline.http.release.version", httpVersion.get())
        systemProperty("pipeline.http.publisher", httpPublisher.get())
        systemProperty("pipeline.http.namespace", httpNamespace.get())
        systemProperty("pipeline.http.release.digest.missing", "true")
    }
}


// ----------------------------------------------------------------------------
// The manifest DOCUMENT, derived from the code, not typed by hand.
// The declaration object is the one authority: the contributor reads it at runtime
// and this task reads it at build time. Typing the JSON here instead would create a
// second authority able to describe Steps the code does not have.
// The digest task MUST run first: the manifest reports the digest and fails closed
// for lack of provenance. The declared digest covers the artifact content EXCLUDING
// this document, the same convention the release-properties file already uses.
// ----------------------------------------------------------------------------

val httpCompatibilityResource = layout.buildDirectory
    .file("resources/main/META-INF/pipelinek/plugin-compatibility.json")

val generateHttpPluginCompatibility = tasks.register("generatePluginCompatibilityMetadata") {
    group = "http"
    description = "Emit the policy-derived compatibility resource for the artifact."

    // The version and the destination are read HERE, while the build is
    // configured, so that the action below carries the VALUES and not the
    // script that owns them. An action that reaches into the script it was
    // declared in cannot be stored in the configuration cache: the cache
    // refuses to serialize a reference to a build script, and the task then
    // runs against a script that is not there.
    val productVersion = rootProject.version.toString()
    val compatibilityResource = httpCompatibilityResource

    inputs.property("productVersion", productVersion)
    outputs.file(compatibilityResource)

    doLast {
        val policy = PluginCompatibilityPolicy(productVersion)
        val out = compatibilityResource.get().asFile
        out.parentFile.mkdirs()
        out.writeText(policy.toJson())
        println("http: compatibility metadata emitted to $out (apiRange=${policy.apiRange()})")
    }
}

val httpManifest = layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitHttpManifest = tasks.register<JavaExec>("emitHttpManifest") {
    group = "http"
    description = "Emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeHttpDigest, generateHttpPluginCompatibility)
    mainClass.set("dev.rubentxu.pipeline.v2.sdk.http.HttpPluginDeclarationKt")

    // The main reads its release properties off ITS OWN classpath, so the freshly
    // generated resources directory has to be on it.
    classpath = sourceSets["main"].runtimeClasspath + files(layout.buildDirectory.dir("resources/main"))

    inputs.files(httpReleaseProps)
    inputs.files(httpCompatibilityResource)
    outputs.file(httpManifest)

    val out = httpManifest
    // The declaration prints the manifest on its standard output, and the task has to
    // keep those bytes. It keeps them in a FILE, not in a stream held in memory: a
    // stream is part of the task's own state, and the configuration cache refuses to
    // store a task that owns one ("cannot serialize object of type
    // 'java.io.ByteArrayOutputStream'"). The file is opened when the task RUNS, so
    // the stored task state carries no stream at all.
    val captureFile = layout.buildDirectory.file("tmp/emitHttpManifest.out")

    doFirst {
        out.get().asFile.parentFile.mkdirs()
        val capture = captureFile.get().asFile
        capture.parentFile.mkdirs()
        // `standardOutput` only accepts System.out or System.err while the task is
        // being configured, so it is assigned here, when the task runs.
        (this as JavaExec).standardOutput = capture.outputStream()
    }

    doLast {
        val exec = this as JavaExec
        val stream = exec.standardOutput
        if (stream !== System.out && stream !== System.err) {
            (stream as? java.io.Closeable)?.close()
        }
        val text = captureFile.get().asFile.readText(Charsets.UTF_8)
        if (text.isBlank()) {
            throw GradleException(
                "Manifest emission produced no output. The http plugin would ship without " +
                    "META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("http: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") {
    dependsOn(generateHttpPluginCompatibility)
    finalizedBy(emitHttpManifest)
}

tasks.named("jar") {
    dependsOn(emitHttpManifest)
}

// Unit-test wiring RESTORED.
// The HTTP STEP's own guarantee is that a pipeline performs a real request, and that is the
// subject of the integration lane rather than of a JUnit class. The module also holds a pure
// decode rule, `StatusRange.parse`, reached from a JSON payload at request time, with no socket
// and no clock, and that has a test.
// The reason the wiring was pulled out is still true and is why the removal was the right call THEN:
// a `Test` task whose source set is empty reports success while executing zero classes -- a silent
// green. Gradle 8.14 exposes no `failOnNoDiscoveredTests` property, so nothing inside this build can
// turn that green into a failure, and a build file asserting otherwise would be describing a flag it
// cannot compile. What catches it lives outside Gradle: `scripts/verify-all.sh` enumerates the
// modules it asks from the filesystem and fails when a module that HAS a test source produces no
// JUnit XML. That check is what keeps this wiring honest -- the test source set is no longer empty,
// and if it ever goes empty again the gate fails here rather than reporting a vacuous pass.

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
