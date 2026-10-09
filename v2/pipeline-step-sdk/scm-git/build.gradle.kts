import dev.rubentxu.pipeline.build.ProvenanceDigest
import java.io.ByteArrayOutputStream

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

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// ----------------------------------------------------------------------------
// SCM/Git OFFICIAL_PLUGIN provenance seam (LFC-2E2 / F5.1 / ADR-0092).
//
// The contributor (ScmGitStepDefinitionContributor) refuses to register
// without the real SHA-256 of its own artefact — that is the "no
// fabricated SHA" contract the human reviewer asked for.
//
// We compute the digest at build time by streaming the contents of every
// class file produced by `compileKotlin` and every resource processed by
// `processResources` through `sha256sum` (always present on Linux) and
// then taking the SHA-256 of the concatenation. The resulting digest is
// deterministic for a given module source revision (same source -> same
// classes -> same digest) and is independent of the JAR layout / packaging
// timestamp — a much stronger provenance than hashing the JAR bytes would
// give us (since `Built-Date` and `Built-By` would randomise the JAR).
//
// The digest is written to `META-INF/scm-git-release.properties` so it
// ships inside the JAR and is observable by any consumer.
//
// If the digest cannot be computed (no compiled classes yet — e.g. when
// running compileKotlin directly), the properties file is left absent
// and the contributor fails closed at registration time with a clear
// diagnostic. A canonical build ALWAYS goes through `classes` first.
// ----------------------------------------------------------------------------

val scmGitPublisher = providers.gradleProperty("pipeline.scm-git.publisher").orElse("pipeline-kotlin")
val scmGitNamespace = providers.gradleProperty("pipeline.scm-git.namespace").orElse("pipeline.scm-git")
val scmGitVersion = providers.gradleProperty("pipeline.scm-git.release.version").orElse(version.toString())

val scmGitReleaseProps = layout.buildDirectory.file("resources/main/META-INF/scm-git-release.properties")

val scmGitClassesDir = layout.buildDirectory.dir("classes/kotlin/main")
val scmGitResourcesDir = layout.buildDirectory.dir("resources/main")

// Exclusions by EXACT relative path, never by name suffix (B0.2). The previous filter dropped
// any file ending with `plugin-manifest.json`, which would also drop an unrelated
// `unrelated-plugin-manifest.json` from the identity.
val scmGitExcludedResourcePaths = setOf(
    "META-INF/scm-git-release.properties",
    "META-INF/scm-git-release.properties.digest",
    "META-INF/pipelinek/plugin-manifest.json",
)

val computeScmGitDigest = tasks.register("computeScmGitDigest") {
    group = "scm-git"
    description = "Compute the SCM/Git OFFICIAL_PLUGIN provenance SHA-256 (deterministic over class files + resources)."

    val publisher = scmGitPublisher
    val namespace = scmGitNamespace
    val ver = scmGitVersion

    outputs.file(scmGitReleaseProps)
    dependsOn("compileKotlin", "processResources")

    // Declared inputs, WITHOUT `skipWhenEmpty`: `inputs.dir + skipWhenEmpty` used to make Gradle
    // mark this NO-SOURCE on incremental builds. `inputs.files(fileTree(...))` keeps the task
    // runnable while still letting Gradle re-run it when the material changes (B0.2).
    inputs.files(fileTree(scmGitClassesDir)).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(scmGitResourcesDir) { exclude(scmGitExcludedResourcePaths) })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("publisher", publisher)
    inputs.property("namespace", namespace)
    inputs.property("version", ver)
    inputs.property("module", "scm-git")

    // AUD-01: this used to be `sh -c "sha256sum <ABSOLUTE PATHS> | sha256sum"`, and `sha256sum`
    // PRINTS THE FILENAME IT WAS GIVEN. The shared ProvenanceDigest frames only the root name and
    // the relative path, so two checkouts of identical bytes hash identically.
    doLast {
        val classesDir = scmGitClassesDir.get().asFile
        require(classesDir.exists()) { "classes/kotlin/main does not exist: run compileKotlin first" }
        val resourcesDir = scmGitResourcesDir.get().asFile
        val out = scmGitReleaseProps.get().asFile
        out.parentFile.mkdirs()
        val hex = ProvenanceDigest.computeDigestHex(
            listOf(
                ProvenanceDigest.Root("classes", classesDir.toPath()),
                ProvenanceDigest.Root("resources", resourcesDir.toPath()),
            ),
            scmGitExcludedResourcePaths.mapTo(linkedSetOf()) { "resources/$it" },
        )
        require(hex.length == 64) { "Expected 64-hex SHA-256, got '${hex.take(80)}'" }
        val digest = "sha256:$hex"
        out.writeText(
            buildString {
                appendLine("pipeline.scm-git.publisher=${publisher.get()}")
                appendLine("pipeline.scm-git.namespace=${namespace.get()}")
                appendLine("pipeline.scm-git.release.version=${ver.get()}")
                appendLine("pipeline.scm-git.release.digest=$digest")
                appendLine("pipeline.scm-git.module=scm-git")
            },
        )
        println("scm-git: provenance written to $out (digest=${digest.take(20)}...)")
    }
}

tasks.named("jar") {
    // The JAR must include the provenance file. `processResources` depends
    // on `computeScmGitDigest` (Gradle's processResources is a copy task
    // and so the runtime ordering naturally picks the file up). We
    // declare an explicit dependsOn for clarity even though Gradle's
    // default lifecycle would handle it.
    dependsOn(computeScmGitDigest)
}

// Forward the release properties as system properties to every Test task so
// the contributor can read them via System.getProperty at class load (the
// contributor is invoked during tests, not during compilation). We
// deliberately do NOT inline them in the source: the contributor reads from
// the artefact tree, not from build-config-time source.
val scmGitReleasePropsFile = scmGitReleaseProps

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
    val props = loadPropsAsMap(scmGitReleasePropsFile.get().asFile)
    if (props.isNotEmpty()) {
        for ((k, v) in props) systemProperty(k, v)
        systemProperty("pipeline.scm-git.release.digest.missing", "false")
    } else {
        systemProperty("pipeline.scm-git.release.digest", "sha256:" + "0".repeat(64))
        systemProperty("pipeline.scm-git.release.version", scmGitVersion.get())
        systemProperty("pipeline.scm-git.publisher", scmGitPublisher.get())
        systemProperty("pipeline.scm-git.namespace", scmGitNamespace.get())
        systemProperty("pipeline.scm-git.release.digest.missing", "true")
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

val scmGitManifest = layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitScmGitManifest = tasks.register<JavaExec>("emitScmGitManifest") {
    group = "scm-git"
    description = "S6/C: emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeScmGitDigest)
    mainClass.set("dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitPluginDeclarationKt")

    // The main reads its release properties off ITS OWN classpath, so the freshly
    // generated resources directory has to be on it.
    classpath = sourceSets["main"].runtimeClasspath + files(layout.buildDirectory.dir("resources/main"))

    inputs.files(scmGitReleaseProps)
    inputs.property("apiRange", "[0.47.0, 0.49.0)")
    outputs.file(scmGitManifest)

    val out = scmGitManifest
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
                "S6/C: manifest emission produced no output. The scm-git plugin would ship without " +
                    "META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("scm-git: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") {
    finalizedBy(emitScmGitManifest)
}

tasks.named("jar") {
    dependsOn(emitScmGitManifest)
}
