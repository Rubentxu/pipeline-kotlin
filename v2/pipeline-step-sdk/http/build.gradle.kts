import java.io.ByteArrayOutputStream
import java.security.MessageDigest

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

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

/**
 * The one hashing primitive the provenance seam uses.
 *
 * `sha256sum` is not reachable from here without a shell, and a shell is what made the digest
 * path-dependent in the first place (see AUD-01 in `computeHttpDigest`). One helper, used by the
 * digest and available to the tests that prove it is path-independent.
 */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// ----------------------------------------------------------------------------
// HTTP OFFICIAL_PLUGIN provenance seam (LFC-2E3 / WU-093 / STEP_ECOSYSTEM_POLICY).
//
// Mirrors the scm-git seam deliberately: the contributor refuses to register
// without the real SHA-256 of its own artefact. A plugin that cannot prove what
// it is must not be admitted into a run.
//
// Determinism: the digest is the SHA-256 of the sorted `sha256sum` output of
// every compiled class file and processed resource, so it depends on the source
// revision and NOT on JAR packaging timestamps.
// ----------------------------------------------------------------------------

val httpPublisher = providers.gradleProperty("pipeline.http.publisher").orElse("pipeline-kotlin")
val httpNamespace = providers.gradleProperty("pipeline.http.namespace").orElse("pipeline-plugin-http")
val httpVersion = providers.gradleProperty("pipeline.http.release.version").orElse(version.toString())

val httpReleaseProps = layout.buildDirectory.file("resources/main/META-INF/http-release.properties")

val computeHttpDigest = tasks.register("computeHttpDigest") {
    group = "http"
    description = "Compute the HTTP OFFICIAL_PLUGIN provenance SHA-256 (deterministic over class files + resources)."

    val publisher = httpPublisher
    val namespace = httpNamespace
    val ver = httpVersion

    outputs.file(httpReleaseProps)
    dependsOn("compileKotlin", "processResources")

    // The digest is now PLAIN COMPUTATION rather than a shell pipeline, so this task is a
    // DefaultTask with a doLast and no Exec. It used to be `tasks.register<Exec>`; keeping Exec
    // without a commandLine fails with "A problem occurred starting process 'command 'null''",
    // which is what the first run after the change reported.

    doLast {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        require(classesDir.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = httpReleaseProps.get().asFile
        out.parentFile.mkdirs()
        // AUD-01: this used to be `sh -c "sha256sum <ABSOLUTE PATHS> | sha256sum"`, and
        // `sha256sum` PRINTS THE FILENAME IT WAS GIVEN. Two checkouts of identical bytes
        // therefore produced different digests, and the unquoted expansion broke outright on
        // any path containing a space. Measured on this host before the change:
        //
        //   absolute paths   checkoutA 6d938320...  checkoutB c8b614a5...   <-- differs
        //   relative paths   checkoutA 666f3d32...  checkoutB 666f3d32...   <-- identical
        //
        // This is not cosmetics. The digest is the plugin's provenance identity: a receipt that
        // binds a candidate to a SHA cannot prove anything if the same tree hashes differently
        // depending on where it was checked out. No shell is involved any more, so there is
        // nothing to quote and nothing to inject.
        //
        // ONE hook, not doFirst-then-doLast: the previous shape passed the hex through a
        // `.digest` side file that doFirst wrote and doLast read and deleted, which is mutable
        // state between two hooks for no reason once the shell is gone.
        //
        // The provenance file and the PLUGIN MANIFEST are both EXCLUDED, and the second exclusion
        // is not optional. The manifest carries `releaseDigest`, so hashing it is a fixed-point
        // loop: emit manifest -> it states the digest -> re-hash changes the manifest -> the digest
        // moves. The S6/C comment already stated the convention ("the digest covers the artifact
        // content EXCLUDING this document"); AUD-01 implemented only half of it — the `.properties`
        // file but not the manifest. The digest was stable across runs only because the manifest
        // happened to be byte-identical each time, which is luck and not the property; measured on
        // utilities, one manifest byte moved it 6c035f25 -> 5efa299f and it did not return to
        // 6c035f25 when the byte was restored, because the manifest had been regenerated into the
        // hashed tree.
        val roots = listOf(classesDir to "classes") +
            listOfNotNull(resourcesDir.takeIf { it.exists() }?.let { it to "resources" })
        val entries = roots.flatMap { (root, prefix) ->
            root.walkTopDown().filter { it.isFile && it.path != out.path && it.name != out.name + ".digest" && !it.name.endsWith("plugin-manifest.json") }.map { file ->
                "$prefix/${root.toPath().relativize(file.toPath()).toString().replace('\\', '/')}" to file.readBytes()
            }
        }.sortedBy { it.first }
        require(entries.isNotEmpty()) { "No class or resource files to hash for HTTP OFFICIAL_PLUGIN provenance" }
        val hex = sha256Hex(
            entries.joinToString("\n") { (rel, bytes) -> "${sha256Hex(bytes)}  $rel" }
                .toByteArray(Charsets.UTF_8),
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
// S6/C — the manifest DOCUMENT, derived from the code, not typed by hand.
//
// The declaration object is the one authority: the contributor reads it at runtime
// and this task reads it at build time. Typing the JSON here instead would create a
// second authority able to describe Steps the code does not have.
//
// The digest task MUST run first: the manifest reports the digest and fails closed
// for lack of provenance. The declared digest covers the artifact content EXCLUDING
// this document, the same convention the release-properties file already uses.
// ----------------------------------------------------------------------------

val httpManifest = layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitHttpManifest = tasks.register<JavaExec>("emitHttpManifest") {
    group = "http"
    description = "S6/C: emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeHttpDigest)
    mainClass.set("dev.rubentxu.pipeline.v2.sdk.http.HttpPluginDeclarationKt")

    // The main reads its release properties off ITS OWN classpath, so the freshly
    // generated resources directory has to be on it.
    classpath = sourceSets["main"].runtimeClasspath + files(layout.buildDirectory.dir("resources/main"))

    inputs.files(httpReleaseProps)
    inputs.property("apiRange", "[0.47.0, 0.49.0)")
    outputs.file(httpManifest)

    val out = httpManifest
    val captured = ByteArrayOutputStream()
    standardOutput = captured

    doFirst {
        out.get().asFile.parentFile.mkdirs()
        // The buffer lives for the whole configuration, so a second execution in the
        // same build would APPEND to the first document and emit concatenated JSON.
        captured.reset()
    }

    doLast {
        val text = captured.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            throw GradleException(
                "S6/C: manifest emission produced no output. The http plugin would ship without " +
                    "META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("http: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") {
    finalizedBy(emitHttpManifest)
}

tasks.named("jar") {
    dependsOn(emitHttpManifest)
}
