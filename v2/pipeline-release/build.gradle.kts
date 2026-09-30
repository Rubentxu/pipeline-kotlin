plugins {
    kotlin("jvm")
    // Matched to the Kotlin version (2.4.10), NOT the kotlinx-serialization
    // runtime version. The catalog alias `libs.plugins.kotlin.serialization`
    // binds to the runtime version and does not resolve as a Gradle plugin id.
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // Honour a system property override so CI can point the real-artifact
    // checks at a different build tree. Default: this module lives at
    // v2/pipeline-release/, so the parent is v2/.
    val v2RootDefault = projectDir.parentFile.absolutePath
    systemProperty("release.v2.root", System.getProperty("release.v2.root", v2RootDefault))
}

// ---------------------------------------------------------------------------
// DIST-PRODUCT P0.3 build wiring: the cheap identity gate.
//
// This task is the boundary. It runs AFTER the distribution ZIP exists, asks
// the release model whether those bytes are an admissible candidate, and fails
// the build when they are not. Until this task existed, the identity model was
// exercised only by tests — a green build did not mean an admissible candidate.
//
// The task is intentionally thin. It resolves the ZIP, shells out to the
// `candidate-admission` main, and propagates a non-zero exit. It re-derives no
// admission rules, so the build and the tested model cannot drift apart.
//
// Candidate sequence is a build parameter rather than a constant: a release
// train publishes several candidates, and the sequence is what orders them.
// ---------------------------------------------------------------------------

val candidateSequence = providers.gradleProperty("candidate.sequence")
    .orElse("1")
    .get()

val candidateTag = providers.gradleProperty("candidate.tag")
    .orElse("")
    .get()

tasks.register<JavaExec>("candidateAdmission") {
    group = "distribution"
    description = "DIST-PRODUCT P0.3: cheap identity admission for the distribution ZIP."

    dependsOn(":pipeline-application:distZip")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.v2.release.CandidateAdmissionMainKt")

    val distDir = project(":pipeline-application").layout.buildDirectory.dir("distributions")
    val outDir = layout.buildDirectory.dir("candidate")

    // Declared at configuration time: Gradle rejects an outputs mutation once
    // a task has started. Registering the directory here also gives the task a
    // real output, so it re-runs when the emitted material changes.
    outputs.dir(outDir)
    inputs.property("productVersion", project.version.toString())
    inputs.property("candidateSequence", candidateSequence)

    // Resolved lazily inside doFirst: the ZIP does not exist at configuration
    // time, and a configuration-time existence check would fail the build for
    // the wrong reason.
    doFirst {
        val version = project.version.toString()
        val dist = distDir.get().asFile
        val zip = dist.resolve("pipelinek-$version.zip")
        if (!zip.isFile) {
            throw GradleException(
                "DIST-PRODUCT P0.3: expected distribution ZIP at $zip but it does not exist. " +
                    "Run :pipeline-application:distZip first.",
            )
        }

        val sbom = listOf(
            dist.resolve("pipelinek-$version.sbom.json"),
            dist.resolve("bom.json"),
        ).firstOrNull { it.isFile }

        args = listOf(
            zip.absolutePath,
            version,
            gitCommitOf(rootProject),
            outDir.get().asFile.absolutePath,
            candidateTag,
            candidateSequence,
            sbom?.absolutePath ?: "-",
            // P0.5 — the repository whose version control state defines this
            // candidate's provenance. RootProject lives in v2/, whose parent
            // holds the .git directory.
            rootProject.projectDir.parentFile.absolutePath,
        )
    }

    // Any non-zero exit means the candidate was refused. Propagate it verbatim:
    // the diagnostic printed by the model is the operator-facing message, and
    // wrapping or swallowing it would hide the reason.
    isIgnoreExitValue = false
}


/**
 * The commit the candidate was built from, or an explicit marker when git is
 * unavailable (an exported source tree, for example). Never an empty string:
 * the manifest records provenance, and an empty commit would be a claim about
 * a build nobody can trace.
 *
 * NOTE (P0.5): this value alone does NOT establish provenance. It is git HEAD
 * while the ZIP is compiled from the working tree, so a dirty tree would make
 * it a false address. The build's real guarantee is the clean-tree law in
 * `SourceProvenance`, enforced by the `candidateAdmission` task; this function
 * only supplies the address to be verified.
 */
fun gitCommitOf(root: Project): String = try {
    providers.exec {
        workingDir(root.projectDir.parentFile)
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
} catch (_: Exception) {
    "unknown"
}
