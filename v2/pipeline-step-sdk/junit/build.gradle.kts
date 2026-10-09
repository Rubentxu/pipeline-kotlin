import dev.rubentxu.pipeline.build.ProvenanceDigest
import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.serialization.json)
    // SAX XML parsing (JAXP is part of the JDK; we declare no external dep).

    testImplementation(project(":pipeline-application"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}

group = "dev.rubentxu.pipeline.v2"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// JUnit OFFICIAL_PLUGIN provenance (F5.2).
//
// Same shape as scm-git: a Gradle Exec task computes the SHA-256 of
// every compiled class + resource (excluding the provenance file
// itself to avoid chicken-and-egg), stored in
// `META-INF/junit-release.properties`, and the contributor fails
// closed if any of publisher/version/digest is missing.

val junitPublisher = providers.gradleProperty("pipeline.junit.publisher").orElse("pipeline-kotlin")
val junitNamespace = providers.gradleProperty("pipeline.junit.namespace").orElse("pipeline.junit")
val junitVersion = providers.gradleProperty("pipeline.junit.release.version").orElse(version.toString())

val junitReleaseProps = layout.buildDirectory.file("resources/main/META-INF/junit-release.properties")

val junitClassesDir = layout.buildDirectory.dir("classes/kotlin/main")
val junitResourcesDir = layout.buildDirectory.dir("resources/main")

// AUD-01 (B0.2): exclusions by EXACT FRAMED relative path, which is
// `<root name>/<relative path>`, NOT an absolute filesystem path.
//
// Two separate defects are fixed here, and they are distinct:
//
// 1. ABSOLUTE PATHS IN THE MATERIAL. This script used
//    `sha256sum $all | sha256sum` over ABSOLUTE paths, and `sha256sum` PRINTS THE
//    FILENAME IT WAS GIVEN, so the checkout path entered the digest. Measured on
//    6732863c: two checkouts of the same clean tree produced
//    `pipeline.junit.release.digest` 563804512db1e747… and 3ca0fce6ae20de23…, so the
//    shipped ZIP digests differed (ea9078de… vs ba0b1d07…) for byte-identical sources.
//    The shared ProvenanceDigest frames only the root NAME and the RELATIVE path, so
//    the absolute path never enters the material.
//
// 2. A LEAKED TEMPORARY FILE. The shell pipeline wrote `${out}.digest` inside the
//    resources directory and deleted it afterwards. A build interrupted between the
//    write and the delete — or simply ordered differently — left that scratch file
//    inside `META-INF/`, where `processResources` packaged it into the jar. Measured:
//    `http-0.48.0.jar` shipped `META-INF/http-release.properties.digest` in one checkout
//    and not the other. A plain in-process digest has no scratch file to leak.
//
//    The two exclusions are the documents this task feeds: the properties file the
//    digest is WRITTEN into, and the manifest that reads it back. Excluding both by
//    exact framed path is what keeps the digest a fixed point.
val junitExcludedFromDigest: Set<String> = setOf(
    "resources/META-INF/junit-release.properties",
    "resources/META-INF/pipelinek/plugin-manifest.json",
)

val computeJunitDigest = tasks.register("computeJunitDigest") {
    group = "junit-plugin"
    description = "Compute the JUnit OFFICIAL_PLUGIN provenance SHA-256 (deterministic over class files + resources)."

    val publisher = junitPublisher
    val namespace = junitNamespace
    val ver = junitVersion

    outputs.file(junitReleaseProps)
    dependsOn("compileKotlin", "processResources")

    // Declared inputs with RELATIVE path sensitivity, so the task's own up-to-date key is
    // checkout-independent too. An absolute up-to-date key would make Gradle re-run the
    // digest in one checkout and not the other for identical trees.
    inputs.files(fileTree(junitClassesDir)).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(junitResourcesDir) { exclude(junitExcludedFromDigest) })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("publisher", publisher)
    inputs.property("namespace", namespace)
    inputs.property("version", ver)

    // The digest is PLAIN COMPUTATION rather than a shell pipeline. It used to be
    // `tasks.register<Exec>`; keeping Exec without a commandLine fails with
    // "A problem occurred starting process 'command 'null''".
    doLast {
        val classesDir = junitClassesDir.get().asFile
        require(classesDir.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDir = junitResourcesDir.get().asFile
        val out = junitReleaseProps.get().asFile
        out.parentFile.mkdirs()
        val hex = ProvenanceDigest.computeDigestHex(
            listOf(
                ProvenanceDigest.Root("classes", classesDir.toPath()),
                ProvenanceDigest.Root("resources", resourcesDir.toPath()),
            ),
            junitExcludedFromDigest,
        )
        require(hex.length == 64) { "Expected 64-hex SHA-256, got '${hex.take(80)}'" }
        val digest = "sha256:$hex"
        out.writeText(
            buildString {
                appendLine("pipeline.junit.publisher=${publisher.get()}")
                appendLine("pipeline.junit.namespace=${namespace.get()}")
                appendLine("pipeline.junit.release.version=${ver.get()}")
                appendLine("pipeline.junit.release.digest=$digest")
                appendLine("pipeline.junit.module=junit")
            },
        )
        println("junit: provenance written to $out (digest=${digest.take(20)}...)")
    }
}

tasks.named("jar") {
    dependsOn(computeJunitDigest)
}

// Forward the release properties to test JVMs so the contributor can
// read them via System.getProperty at class load.
val junitReleasePropsFile = junitReleaseProps

fun loadPropsAsMap(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val map = linkedMapOf<String, String>()
    file.useLines { lines ->
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val k = trimmed.substring(0, idx).trim()
                val v = trimmed.substring(idx + 1).trim()
                map[k] = v
            }
        }
    }
    return map
}

tasks.withType<Test>().configureEach {
    val props = loadPropsAsMap(junitReleasePropsFile.get().asFile)
    if (props.isNotEmpty()) {
        for ((k, v) in props) systemProperty(k, v)
    } else {
        // Fallback so test runs do not fail-closed when the jar is not
        // built yet; the contributor still requires sha256:<64-hex> shape.
        systemProperty("pipeline.junit.release.digest", "sha256:" + "0".repeat(64))
        systemProperty("pipeline.junit.release.version", junitVersion.get())
        systemProperty("pipeline.junit.publisher", junitPublisher.get())
        systemProperty("pipeline.junit.namespace", junitNamespace.get())
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

val junitManifest = layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitJunitManifest = tasks.register<JavaExec>("emitJunitManifest") {
    group = "junit"
    description = "S6/C: emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeJunitDigest)
    mainClass.set("dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitPluginDeclarationKt")

    // The main reads its release properties off ITS OWN classpath, so the freshly
    // generated resources directory has to be on it.
    classpath = sourceSets["main"].runtimeClasspath + files(layout.buildDirectory.dir("resources/main"))

    inputs.files(junitReleaseProps)
    inputs.property("apiRange", "[0.47.0, 0.49.0)")
    outputs.file(junitManifest)

    val out = junitManifest
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
                "S6/C: manifest emission produced no output. The junit plugin would ship without " +
                    "META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("junit: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") {
    finalizedBy(emitJunitManifest)
}

tasks.named("jar") {
    dependsOn(emitJunitManifest)
}
