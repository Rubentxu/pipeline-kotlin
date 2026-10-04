plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2.fitness"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation(project(":pipeline-events"))
    testImplementation(project(":pipeline-scripting-api"))
    testImplementation(project(":pipeline-artefacts-local"))
    testImplementation(project(":pipeline-step-sdk:api"))
    testImplementation(project(":pipeline-application"))
    testImplementation(project(":pipeline-domain"))
    testImplementation(project(":pipeline-step-sdk:runtime"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(project(":pipeline-binding-factory"))
}

tasks.test {
    useJUnitPlatform()
    // Honour system property override (Gradle forwards -Pfitness.v2.root=...).
    // Default: this module lives at v2/pipeline-architecture-tests/, so ../ is v2/.
    val v2RootDefault = projectDir.parentFile.absolutePath
    systemProperty("fitness.v2.root", System.getProperty("fitness.v2.root", v2RootDefault))

    // ── Cross-module inputs, declared ────────────────────────────────────────────
    //
    // Most fitnesses in this package are about OTHER modules: they read a sibling's `build.gradle.kts`
    // to check a declared dependency, its `src/main/kotlin` to scan a package, its `api/*.api` to read
    // an ABI, or its compiled classes to see what a published jar would actually carry.
    //
    // Gradle's up-to-date check only sees DECLARED inputs, and none of those reads went through the
    // test source set. The result was a fitness that kept reporting a previous run's answer after
    // the very file it guards had changed: a mutation that added `maven-publish` to a store module
    // left this task UP-TO-DATE, the XML untouched, and the guard silent.
    //
    // Declared here rather than per test class because the property belongs to the module: every
    // fitness in this package is cross-module, so making each one declare its own inputs would mean
    // a dozen chances to forget.
    //
    // The locations are per module rather than one `fileTree` rooted at `v2`. Rooting at `v2` makes
    // the declared input overlap every other module's build directory, and Gradle then refuses the
    // task for using an output of `:pipeline-event-harness:compileTestKotlin` it never declared. The
    // obvious way out — an `exclude("**/build/**")` on the file tree — is itself forbidden:
    // `FArch011V2NoCompileExcludesTest` rejects the token `exclude(` in any build file, and it is
    // right to. Naming each module's own directories avoids the overlap without the token.
    val crossModuleDirs = rootProject.subprojects.map { it.projectDir }

    // Every module's build file: the fitnesses that read declared dependencies, plugins and
    // publication configuration all read from here.
    inputs.files(crossModuleDirs.map { it.resolve("build.gradle.kts") })
        .withPropertyName("allModuleBuildFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Every module's production sources: package scans read from here.
    inputs.files(crossModuleDirs.map { it.resolve("src/main/kotlin") })
        .withPropertyName("crossModuleProductionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Every committed ABI dump. A dump is a reviewable statement of a published surface, and a
    // fitness that reads one must re-run when it changes.
    inputs.files(crossModuleDirs.mapNotNull { it.resolve("api").takeIf { dir -> dir.isDirectory } })
        .withPropertyName("publishedAbiDumps")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The rows that read COMPILED classes need those classes to exist and to be current. Without the
    // dependency they would read whatever the last build left behind, which is the staleness this
    // whole block exists to remove.
    dependsOn(
        ":pipeline-domain:classes",
        ":pipeline-scripting-api:classes",
        ":pipeline-events:classes",
        ":pipeline-events-store:classes",
        ":pipeline-output:classes",
        ":pipeline-output-store:classes",
    )
}

// Cross-project runtime-classpath capture wiring (configure-time hook, zero M0-R2 build-file edits)
val v2Modules = listOf(
    "pipeline-domain",
    "pipeline-application",
    "pipeline-scripting-api",
    "pipeline-scripting-kotlin24",
    "pipeline-testkit",
    "pipeline-events",
    "pipeline-step-sdk:api",
    "pipeline-step-sdk:processor",
    "pipeline-step-sdk:runtime",
    "pipeline-binding-factory",
)

// Map module names to project paths
val v2ModulePaths = mapOf(
    "pipeline-domain" to ":pipeline-domain",
    "pipeline-application" to ":pipeline-application",
    "pipeline-scripting-api" to ":pipeline-scripting-api",
    "pipeline-scripting-kotlin24" to ":pipeline-scripting-kotlin24",
    "pipeline-testkit" to ":pipeline-testkit",
    "pipeline-events" to ":pipeline-events",
    "pipeline-step-sdk:api" to ":pipeline-step-sdk:api",
    "pipeline-step-sdk:processor" to ":pipeline-step-sdk:processor",
    "pipeline-step-sdk:runtime" to ":pipeline-step-sdk:runtime",
    "pipeline-binding-factory" to ":pipeline-binding-factory",
)

gradle.allprojects {
    val projPath = project.path
    if (projPath in v2ModulePaths.values) {
        val projName = name
        val capture = tasks.register("runtimeClasspathCapture", DefaultTask::class) {
            val out = layout.buildDirectory.file("fitness/${projName}-runtime-classpath.txt")
            outputs.file(out)
            doLast {
                val cp = configurations.named("runtimeClasspath").get()
                    .files
                    .map { it.name }
                    .sorted()
                out.get().asFile.writeText(cp.joinToString("\n"))
            }
        }
        tasks.matching { it.name == "test" }.configureEach {
            dependsOn(capture)
        }
    }
}

// Ensure :pipeline-architecture-tests:test runs after all capture tasks
val captureTaskPaths = v2ModulePaths.values.map { "$it:runtimeClasspathCapture" }
tasks.named("test") {
    dependsOn(captureTaskPaths)
}
