plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // LFC-2E2 Slice 2: SnakeYAML is the safe-loader for `core-utils.readYaml`.
    // The version is pinned in libs.versions.toml; the Step uses a custom
    // SafeConstructorOnlyOptions to refuse arbitrary-class instantiation,
    // alias bombs, and oversize documents.
    implementation(libs.snakeyaml)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
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
// LFC-2E2 utilities OFFICIAL_PLUGIN provenance seam.
//
// Mirrors the proven scm-git build-time provenance pattern (F5.1 / ADR-0092):
// the contributor (CoreUtilsStepDefinitionContributor) refuses to register
// without the real SHA-256 of its own artefact — that is the "no fabricated
// SHA" contract.
//
// The digest is computed at build time by streaming the contents of every
// class file produced by `compileKotlin` and every resource processed by
// `processResources` through `sha256sum` and then taking the SHA-256 of the
// concatenation. The result is deterministic for a given module source
// revision (same source -> same classes -> same digest) and is independent
// of JAR layout / packaging timestamps.
//
// The digest is written to `META-INF/utilities-release.properties` so it
// ships inside the JAR and is observable by any consumer.
//
// If the digest cannot be computed (no compiled classes yet — e.g. when
// running compileKotlin directly), the properties file is left absent and
// the contributor fails closed at registration time with a clear diagnostic.
// A canonical build ALWAYS goes through `classes` first.
// ----------------------------------------------------------------------------

val utilitiesPublisher = providers.gradleProperty("pipeline.utilities.publisher").orElse("pipeline-kotlin")
val utilitiesNamespace = providers.gradleProperty("pipeline.utilities.namespace").orElse("pipeline.utilities")
val utilitiesVersion = providers.gradleProperty("pipeline.utilities.release.version").orElse(version.toString())

val utilitiesReleaseProps = layout.buildDirectory.file("resources/main/META-INF/utilities-release.properties")

val computeUtilitiesDigest = tasks.register<Exec>("computeUtilitiesDigest") {
    group = "utilities"
    description = "Compute the LFC-2E2 utilities OFFICIAL_PLUGIN provenance SHA-256."

    val publisher = utilitiesPublisher
    val namespace = utilitiesNamespace
    val ver = utilitiesVersion

    outputs.file(utilitiesReleaseProps)
    dependsOn("compileKotlin", "processResources")

    doFirst {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        require(classesDir.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = utilitiesReleaseProps.get().asFile
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
        require(all.isNotEmpty()) { "No class or resource files to hash for utilities OFFICIAL_PLUGIN provenance" }
        commandLine = listOf("sh", "-c", "sha256sum $all | sha256sum | awk '{print $1}' > '${out.absolutePath}.digest'")
    }

    doLast {
        val out = utilitiesReleaseProps.get().asFile
        val digestFile = File("${out.absolutePath}.digest")
        val hex = digestFile.readText().trim()
        require(hex.length == 64) { "Expected 64-hex SHA-256, got '${hex.take(80)}'" }
        val digest = "sha256:$hex"
        out.writeText(
            buildString {
                appendLine("pipeline.utilities.publisher=${publisher.get()}")
                appendLine("pipeline.utilities.namespace=${namespace.get()}")
                appendLine("pipeline.utilities.release.version=${ver.get()}")
                appendLine("pipeline.utilities.release.digest=$digest")
                appendLine("pipeline.utilities.module=utilities")
            },
        )
        digestFile.delete()
        println("utilities: provenance written to $out (digest=${digest.take(20)}...)")
    }
}

tasks.named("jar") {
    dependsOn(computeUtilitiesDigest)
}

val utilitiesReleasePropsFile = utilitiesReleaseProps

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
    val props = loadPropsAsMap(utilitiesReleasePropsFile.get().asFile)
    if (props.isNotEmpty()) {
        for ((k, v) in props) systemProperty(k, v)
        systemProperty("pipeline.utilities.release.digest.missing", "false")
    } else {
        systemProperty("pipeline.utilities.release.digest", "sha256:" + "0".repeat(64))
        systemProperty("pipeline.utilities.release.version", utilitiesVersion.get())
        systemProperty("pipeline.utilities.publisher", utilitiesPublisher.get())
        systemProperty("pipeline.utilities.namespace", utilitiesNamespace.get())
        systemProperty("pipeline.utilities.release.digest.missing", "true")
    }
}
