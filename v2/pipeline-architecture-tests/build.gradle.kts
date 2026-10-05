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

/**
 * Every task of every OTHER module that writes anywhere inside that module's own directory.
 *
 * **The hand-written version of this list was removed, and the two failures that removed it are the
 * reason it cannot come back.** It named eleven task types. `koverCachedVerify` was not among them,
 * so the full gate stopped on 2026-10-05 with
 *
 * ```text
 * Task ':pipeline-architecture-tests:test' uses this output of task
 * ':pipeline-step-sdk:api:koverCachedVerify' without declaring an explicit or implicit dependency.
 * Gradle detected a problem with the following location: 'v2/pipeline-step-sdk/api'
 * ```
 *
 * The first replacement was narrower than the problem: it computed "every task whose output
 * overlaps a DECLARED input of this test", comparing `task.outputs.files` against `build.gradle.kts`,
 * `src/main/kotlin` and `api/`. That is the property one would expect to be the right one, and it
 * still failed — naming `compileTestJava`, `compileTestKotlin`, `koverGenerateArtifact`,
 * `koverGenerateArtifactJvm`, `detekt` and `test`.
 *
 * The reason is visible in the error text and was missed the first time: the location Gradle
 * reports is `v2/pipeline-step-sdk/api`, the module's **project directory**, not one of its declared
 * outputs. `compileTestKotlin` writes under `build/classes/`, which overlaps no declared input at
 * all, and is flagged anyway. So the overlap Gradle is validating is not input-to-output; it is
 * *this test uses a location that sits inside a directory another task claims as its output*. The
 * only condition that matches it is "some output falls anywhere inside the other module's own
 * project directory", which is what is computed below.
 *
 * `mustRunAfter`, not `dependsOn`: this fitness reads build files, sources and ABI dumps, and it
 * reads nothing these tasks produce. Declaring a dependency would make it wait on another module's
 * entire test suite and its coverage instrumentation before judging anything. `mustRunAfter` says
 * exactly what is true — if they run, they run first — and it does not force them to run.
 *
 * `@Suppress("UnstableApiUsage")` covers `TaskCollection.filter`: iterating `module.tasks`
 * REALIZES the lazily-registered tasks, which is the price of asking the model rather than a
 * list. It is paid once, at configuration time, across every module.
 */
@Suppress("UnstableApiUsage")
val moduleLocalTaskOutputs: List<org.gradle.api.Task> =
    rootProject.subprojects
        .filter { it.path != project.path }
        .flatMap { module ->
            val moduleDir = module.projectDir.toPath()
            module.tasks.filter { task ->
                task.outputs.files.files.any { produced -> produced.toPath().startsWith(moduleDir) }
            }
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
    // `crossModules` excludes THIS project. It did not at first, and the result was a circular
    // dependency — a task cannot `mustRunAfter` itself — which Gradle reports as a configuration
    // failure before a single test runs. Ordering a module against its own directory states
    // nothing; the fitness reads its own `build.gradle.kts` because that file is right here, not
    // because something else has to produce it first.
    val crossModules = rootProject.subprojects.filter { it.path != project.path }
    val crossModuleDirs = crossModules.map { it.projectDir }

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
    val abiDumpModules = crossModules.filter { it.projectDir.resolve("api").isDirectory }
    inputs.files(abiDumpModules.map { it.projectDir.resolve("api") })
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

    // …and the dumps are TASK OUTPUTS, not just committed files. This dependency is not
    // belt-and-braces: without it Gradle refuses the whole task with
    // `uses this output of task ':…:apiBuild' without declaring an explicit or implicit
    // dependency`, because an `api/` directory that belongs to a module IS that module's `apiBuild`
    // output directory. The module directory then appears as a declared input (via
    // `allModuleBuildFiles` and `crossModuleProductionSources`) and overlaps a task output this
    // task does not depend on.
    //
    // Declaring it is also the honest version of the property: the fitness reads the dump that
    // `apiBuild` produces, so the dump must be produced by THIS revision before the rows judge it.
    //
    // Task references rather than path strings: a path string inside this block resolves against
    // THIS project, not the root, and `:pipeline-step-sdk:apiBuild` is not a task of
    // `pipeline-architecture-tests`. `findByName` also tolerates a module that carries an `api/`
    // directory without applying the BCV plugin, which would otherwise fail the whole task with
    // "Task with path not found" instead of simply not needing it.
    dependsOn(abiDumpModules.mapNotNull { it.tasks.findByName("apiBuild") })

    // The remaining overlap is ORDINAL, not a dependency, and that distinction matters.
    //
    // `moduleLocalTaskOutputs` holds every task of another module that writes anywhere inside that
    // module's own directory. This fitness does not read those outputs: it reads build files,
    // `src/main/kotlin` and `api/` dumps. Declaring `dependsOn` on them would be a lie that costs a
    // lot — it would make this task wait for another module's whole test suite and its coverage
    // instrumentation before judging anything.
    //
    // `mustRunAfter` states exactly what is true: if those tasks happen to run, they run first.
    // That satisfies the ordering half of the validation without inventing an artifact
    // dependency, and it is the distinction Gradle itself offers as a separate solution.
    mustRunAfter(moduleLocalTaskOutputs)
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
