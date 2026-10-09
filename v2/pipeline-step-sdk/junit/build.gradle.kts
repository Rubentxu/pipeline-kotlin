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

val computeJunitDigest = tasks.register<Exec>("computeJunitDigest") {
    group = "junit-plugin"
    description = "Compute the JUnit OFFICIAL_PLUGIN provenance SHA-256 (deterministic over class files + resources)."

    val publisher = junitPublisher
    val namespace = junitNamespace
    val ver = junitVersion

    outputs.file(junitReleaseProps)
    dependsOn("compileKotlin", "processResources")

    doFirst {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        require(classesDir.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = junitReleaseProps.get().asFile
        out.parentFile.mkdirs()
        val excludedOutput = out.absolutePath
        // R-BUILD-01: BOTH documents this task feeds must be outside its own input set.
        //
        // The properties file is where the digest is WRITTEN. The manifest is where the digest
        // is READ back from, and `emitJunitManifest dependsOn computeJunitDigest` writes it into
        // this very resources directory. Excluding only the properties file left the PREVIOUS
        // run's manifest inside the hash, so the digest of build N became an input to the digest
        // of build N+1 and two `distZip` builds of the same clean tree produced different
        // archives: 0a341cb8… and 3d4caf89… at 076982b9.
        //
        // Both exclusions are by absolute path, matching how the properties file was already
        // excluded. The relative form would be shorter and would reintroduce the exact class of
        // bug this fixes, since the digest task runs with an unspecified working directory.
        val excludedFromDigest: Set<String> = setOf(
            excludedOutput,
            junitManifest.get().asFile.absolutePath,
        )
        val classFiles: List<String> = classesDir.walkTopDown()
            .filter { it.isFile && it.absolutePath !in excludedFromDigest }
            .map { it.absolutePath }
            .toList()
            .sorted()
        val resourceFiles: List<String> = if (resourcesDir.exists()) {
            resourcesDir.walkTopDown()
                .filter { it.isFile && it.absolutePath !in excludedFromDigest }
                .map { it.absolutePath }
                .toList()
                .sorted()
        } else {
            emptyList()
        }
        val all = (classFiles + resourceFiles).joinToString(" ")
        require(all.isNotEmpty()) { "No class or resource files to hash for JUnit OFFICIAL_PLUGIN provenance" }
        commandLine = listOf("sh", "-c", "sha256sum $all | sha256sum | awk '{print $1}' > '${out.absolutePath}.digest'")
    }

    doLast {
        val out = junitReleaseProps.get().asFile
        val digestFile = File("${out.absolutePath}.digest")
        val hex = digestFile.readText().trim()
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
        digestFile.delete()
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
