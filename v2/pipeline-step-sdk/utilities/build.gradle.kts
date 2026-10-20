import dev.rubentxu.pipeline.build.PluginCompatibilityPolicy
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Slice 2: SnakeYAML is the safe-loader for `core-utils.readYaml`
    // The version is pinned in libs.versions.toml; the Step uses a custom
    // SafeConstructorOnlyOptions to refuse arbitrary-class instantiation,
    // alias bombs, and oversize documents.
    implementation(libs.snakeyaml)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// ----------------------------------------------------------------------------
// Utilities OFFICIAL_PLUGIN provenance seam
// Mirrors the proven scm-git build-time provenance pattern:
// the contributor (CoreUtilsStepDefinitionContributor) refuses to register
// without the real SHA-256 of its own artefact — that is the "no fabricated
// SHA" contract.
// The digest is computed at build time by streaming the contents of every
// class file produced by `compileKotlin` and every resource processed by
// `processResources` through `sha256sum` and then taking the SHA-256 of the
// concatenation. The result is deterministic for a given module source
// revision (same source -> same classes -> same digest) and is independent
// of JAR layout / packaging timestamps.
// The digest is written to `META-INF/utilities-release.properties` so it
// ships inside the JAR and is observable by any consumer.
// If the digest cannot be computed (no compiled classes yet — e.g. when
// running compileKotlin directly), the properties file is left absent and
// the contributor fails closed at registration time with a clear diagnostic.
// A canonical build ALWAYS goes through `classes` first.
// ----------------------------------------------------------------------------

val utilitiesPublisher = providers.gradleProperty("pipeline.utilities.publisher").orElse("pipeline-kotlin")
val utilitiesNamespace = providers.gradleProperty("pipeline.utilities.namespace").orElse("pipeline.utilities")
val utilitiesVersion = providers.gradleProperty("pipeline.utilities.release.version").orElse(version.toString())

val utilitiesReleaseProps = layout.buildDirectory.file("resources/main/META-INF/utilities-release.properties")

val utilitiesClassesDir = layout.buildDirectory.dir("classes/kotlin/main")
val utilitiesResourcesDir = layout.buildDirectory.dir("resources/main")

// Exclusions by EXACT relative path, never by name suffix. The previous filter dropped
// any file ending with `plugin-manifest.json`, which would also drop an unrelated
// `unrelated-plugin-manifest.json` from the identity.
val utilitiesExcludedResourcePaths = setOf(
    "META-INF/utilities-release.properties",
    "META-INF/utilities-release.properties.digest",
    "META-INF/pipelinek/plugin-manifest.json",
)

val computeUtilitiesDigest = tasks.register("computeUtilitiesDigest") {
    group = "utilities"
    description = "Compute the LFC-2E2 utilities OFFICIAL_PLUGIN provenance SHA-256."

    val publisher = utilitiesPublisher
    val namespace = utilitiesNamespace
    val ver = utilitiesVersion

    outputs.file(utilitiesReleaseProps)
    dependsOn("compileKotlin", "processResources", "generatePluginCompatibilityMetadata")

    // Declared inputs, WITHOUT `skipWhenEmpty`: `inputs.dir + skipWhenEmpty` used to make Gradle
    // Mark this on incremental builds. `inputs.files(fileTree(...))` keeps the task
    // Runnable while still letting Gradle re-run it when the material changes
    inputs.files(fileTree(utilitiesClassesDir)).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(utilitiesResourcesDir) { exclude(utilitiesExcludedResourcePaths) })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("publisher", publisher)
    inputs.property("namespace", namespace)
    inputs.property("version", ver)
    inputs.property("module", "utilities")

    // This used to be `sh -c "sha256sum <ABSOLUTE PATHS> | sha256sum"`, and `sha256sum`
    // PRINTS THE FILENAME IT WAS GIVEN. The shared ProvenanceDigest frames only the root name and
    // the relative path, so two checkouts of identical bytes hash identically.
    doLast {
        val classesDir = utilitiesClassesDir.get().asFile
        require(classesDir.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDir = utilitiesResourcesDir.get().asFile
        val out = utilitiesReleaseProps.get().asFile
        out.parentFile.mkdirs()
        val hex = ProvenanceDigest.computeDigestHex(
            listOf(
                ProvenanceDigest.Root("classes", classesDir.toPath()),
                ProvenanceDigest.Root("resources", resourcesDir.toPath()),
            ),
            utilitiesExcludedResourcePaths.mapTo(linkedSetOf()) { "resources/$it" },
        )
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
        println("utilities: provenance written to $out (digest=${digest.take(20)}...)")
    }
}

tasks.named("jar") {
    dependsOn(computeUtilitiesDigest, generateUtilitiesPluginCompatibility)
}

// ----------------------------------------------------------------------------
// The manifest DOCUMENT, derived from the code, not typed by hand.
// The plugin declares itself once, in UtilitiesPluginDeclaration, and both this build and
// the runtime read that same declaration. Writing the JSON here instead would create a
// second authority able to describe Steps the code does not have.
// Ordering is load-bearing: the digest task must run FIRST, because the manifest reports
// the digest and would otherwise fail closed for lack of provenance.
// The declared digest covers the artifact content EXCLUDING this document, the same
// convention `utilities-release.properties` already uses — a digest that included the
// manifest carrying it would have no fixed point.
// ----------------------------------------------------------------------------

val utilitiesCompatibilityResource = layout.buildDirectory
    .file("resources/main/META-INF/pipelinek/plugin-compatibility.json")

val generateUtilitiesPluginCompatibility = tasks.register("generatePluginCompatibilityMetadata") {
    group = "utilities"
    description = "PK-RC-PLUGIN-001: emit the policy-derived compatibility resource for the artifact."

    inputs.property("productVersion", rootProject.version.toString())
    outputs.file(utilitiesCompatibilityResource)

    doLast {
        val policy = PluginCompatibilityPolicy(rootProject.version.toString())
        val out = utilitiesCompatibilityResource.get().asFile
        out.parentFile.mkdirs()
        out.writeText(policy.toJson())
        println("utilities: compatibility metadata emitted to $out (apiRange=${policy.apiRange()})")
    }
}

val utilitiesManifest = layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitUtilitiesManifest = tasks.register<JavaExec>("emitUtilitiesManifest") {
    group = "utilities"
    description = "S6/C: emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeUtilitiesDigest, generateUtilitiesPluginCompatibility)
    mainClass.set("dev.rubentxu.pipeline.v2.sdk.utilities.step.UtilitiesPluginDeclarationKt")

    // The main reads META-INF/utilities-release.properties off ITS OWN classpath, so the
    // classpath must include the freshly generated resources directory.
    classpath = sourceSets["main"].runtimeClasspath +
        files(layout.buildDirectory.dir("resources/main"))

    inputs.files(utilitiesReleaseProps)
    inputs.files(utilitiesCompatibilityResource)
    outputs.file(utilitiesManifest)

    // Captured into a buffer rather than stdout: Gradle prints task output to the console,
    // and the document must land in a file with no shell quoting in between.
    // The buffer is created at CONFIGURATION time, outside `providers`: inside that lambda
    // the identifier `java` resolves to Gradle's own JavaPluginExtension, not the java.io
    // package, and the DSL stops compiling.
    val out = utilitiesManifest
    val captured = ByteArrayOutputStream()
    standardOutput = captured

    doFirst {
        out.get().asFile.parentFile.mkdirs()
        // The buffer lives for the whole configuration, so a second execution in the same build
        // would APPEND to the first document and emit concatenated JSON. Resetting here keeps
        // the output a function of the current inputs rather than of how many times the task ran.
        captured.reset()
    }

    doLast {
        val text = captured.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            throw GradleException(
                "S6/C: manifest emission produced no output. The utilities OFFICIAL_PLUGIN would ship " +
                    "without META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("utilities: manifest emitted to ${out.get().asFile}")
    }
}

tasks.named("processResources") {
    dependsOn(generateUtilitiesPluginCompatibility)
    finalizedBy(emitUtilitiesManifest)
}

tasks.named("jar") {
    dependsOn(emitUtilitiesManifest)
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

// Unit-test wiring, and what it does NOT cover.
// This module is the `readJson`/`writeJson`/`sha256`/`findFiles` STEPs. Their guarantee is that a
// pipeline calls them on real input, and AGENTS.md section 2 keeps a filesystem out of unit tests
// -- so the walk, the encode/decode of a real file, and the 100_000 cap stay with the executable
// E2E layer (`.pipeline.kts` under `v2/examples/`, driven by `v2/examples/run.sh`).
// That reasoning does not reach `CoreUtilsFindFilesStepDefinition.buildMatcher`, a pure
// String -> PathMatcher whose whole job is a written contract: a leading `**` means any depth
// INCLUDING ZERO, which Java NIO's own `glob:` does not do. A defect there is invisible to the
// E2E layer in the worst possible way -- `findFiles` returns `Unit`, so the scenario can only
// read an exit code, and a wrong matcher and a right one both produce 0. Same shape and same
// exception as `pipeline-step-sdk/files`, which tests its `antPatternToRegex` for exactly this
// reason and says so in its own build file.
// `useJUnitPlatform()` is load-bearing, not decorative: without it Gradle defaults to the JUnit 4
// runner, discovers zero JUnit 5 tests, and reports BUILD SUCCESSFUL over an empty result set.
// Gradle 8.14 exposes no `failOnNoDiscoveredTests` property -- the setting does not exist in this
// version -- so what turns that green into a failure lives outside Gradle: `scripts/verify-all.sh`
// enumerates the modules it asks from the filesystem and fails when a module that HAS a test
// source produces no JUnit XML. The wiring is present here because the test source is present.
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
