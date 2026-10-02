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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

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

val computeHttpDigest = tasks.register<Exec>("computeHttpDigest") {
    group = "http"
    description = "Compute the HTTP OFFICIAL_PLUGIN provenance SHA-256 (deterministic over class files + resources)."

    val publisher = httpPublisher
    val namespace = httpNamespace
    val ver = httpVersion

    outputs.file(httpReleaseProps)
    dependsOn("compileKotlin", "processResources")

    doFirst {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        require(classesDir.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = httpReleaseProps.get().asFile
        out.parentFile.mkdirs()
        val excludedOutput = out.absolutePath
        val classFiles: List<String> = classesDir.walkTopDown()
            .filter { it.isFile && it.absolutePath != excludedOutput }
            .map { it.absolutePath }
            .toList()
            .sorted()
        val resourceFiles: List<String> = if (resourcesDir.exists()) {
            resourcesDir.walkTopDown()
                .filter { it.isFile && it.absolutePath != excludedOutput }
                .map { it.absolutePath }
                .toList()
                .sorted()
        } else {
            emptyList()
        }
        val all = (classFiles + resourceFiles).joinToString(" ")
        require(all.isNotEmpty()) { "No class or resource files to hash for HTTP OFFICIAL_PLUGIN provenance" }
        commandLine = listOf("sh", "-c", "sha256sum $all | sha256sum | awk '{print $1}' > '${out.absolutePath}.digest'")
    }

    doLast {
        val out = httpReleaseProps.get().asFile
        val digestFile = File("${out.absolutePath}.digest")
        val hex = digestFile.readText().trim()
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
        digestFile.delete()
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
