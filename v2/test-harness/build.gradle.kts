plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

// H0 — the harness contracts module.
//
// Deliberately minimal dependencies. The catalogue is DATA plus pure functions over it, so it needs
// no product class, no persistence, no process runner and no test framework at runtime. That is what
// lets a selection run before anything is compiled: if the catalogue needed the product on the
// classpath, impact analysis could not answer "what must be tested" until the product already
// built, which is one moment too late.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// H0.2 — the gate. Until this task exists, the catalogue describes what should be tested and
// nothing consults it, which is the difference between a document and a gate.
//
// `validationPlan` runs the planner over the diff against the base branch and PRINTS what must
// run. It fails the build on a refusal: a non-zero exit here is the gate refusing an unclassified
// production change, and treating that as a warning is exactly how an unclassified file learns to
// be tolerated.
//
// It deliberately does NOT run the suites itself. Selection and execution are separate decisions,
// and a task that both selects and runs can be trusted for neither: a narrowing of the selection
// and a narrowing of the run look identical from the outside. Execution belongs in the tasks that
// own those suites, and `adjudicate` is what checks the two agree afterwards.
// ---------------------------------------------------------------------------------------------

/** The ref the plan is computed against. Override with -PvalidationBase=<ref>. */
val validationBase: String =
    (findProperty("validationBase") as String?) ?: "origin/main"

val validationPlan by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Resolve the changed paths into the set of suites that must be run."

    mainClass.set("dev.rubentxu.pipeline.v2.harness.plan.PlanCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(validationBase)

    // The refusal must be legible in the build log, not buried in a stack trace.
    isIgnoreExitValue = false

    doFirst {
        logger.lifecycle(
            "validationPlan: computing impact against $validationBase. " +
                "A REFUSED result fails this build by design.",
        )
    }
}

// BQ4 vertical (2026-10-16): the cycle runner is the harness's
// executable surface. The `cycle` task is a thin wrapper that
// launches the JVM with the harness's runtime classpath. The
// `CycleCli` itself decides which path is the cycle dir, refuses
// to write inside a Git working tree, and parses a snake_case
// manifest via `kotlinx.serialization`.
val cycle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run an ephemeral cycle (HF0/HF1 only) and close it under spec invariants."

    mainClass.set("dev.rubentxu.pipeline.v2.harness.cycle.CycleCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    // The CLI is opt-in: it requires --cycle-id and --manifest.
    // When the operator runs `:test-harness:cycle`, Gradle will
    // surface the CLI's argument error and exit 2. We do not
    // pre-validate the args here to keep the task single-purpose.
    isIgnoreExitValue = true
}

// ---------------------------------------------------------------------------------------------
// H1 — the gate, and WHY IT IS NOT A TASK HERE.
//
// `validationPlan` above computes and PRINTS the selection; it never runs anything and never looks
// at what ran, so a plan nobody discharges still builds green. The gate closes that loop: it
// resolves the same plan, dispatches it as Gradle invocations, runs them, reads the JUnit XML they
// produced, and refuses unless the evidence discharges the selection.
//
// It is deliberately NOT a `JavaExec` task in this file. `settings.gradle.kts` takes an exclusive
// lock on this checkout for the duration of any build, and the gate has to START builds of its own
// after the build that launched it has finished. A task would always find that lock held by itself,
// and every selected suite would fail with "Another Gradle invocation is already using this v2
// checkout". That was measured, not assumed: it is how the first real run of the gate failed, and
// the gate's refusal named all five suites correctly while the underlying cause was the lock.
//
// So the entry point is `scripts/validation-gate.py`, which builds the harness jar, releases the
// lock, then runs the gate as a plain JVM. One path, and it is the one that works.
// ---------------------------------------------------------------------------------------------

tasks.register("printRuntimeClasspath") {
    group = "build"
    description = "Write the runtime classpath of this module, one entry per line, for external launchers."

    // The output path is read INSIDE doLast, never at configuration time. Configuring eagerly
    // with `?: error(...)` aborts the whole module's configuration when the property is absent —
    // and since configuration happens for EVERY task in the module, `:test-harness:tasks` and
    // `:test-harness:test` both died with "Could not create task ':test-harness:printRuntimeClasspath'"
    // before this moved. Measured: it is what made the gate report "task 'test-harness' not found"
    // for a project that is in settings.gradle.kts and has a working `test` task.
    val outputProperty = findProperty("output") as String?

    dependsOn(tasks.named("jar"))

    doLast {
        val output = outputProperty
            ?: throw InvalidUserDataException(
                "printRuntimeClasspath needs -Poutput=<file>; refusing to guess where to write the classpath",
            )
        val entries = sourceSets["main"].runtimeClasspath.files.map { it.absolutePath }
        check(entries.isNotEmpty()) { "the runtime classpath is empty; refusing to write it" }
        val file = file(output)
        file.parentFile.mkdirs()
        file.writeText(entries.joinToString("\n"))
        logger.lifecycle("printRuntimeClasspath: wrote ${entries.size} entries to $output")
    }
}

tasks.named("check") {
    // The planner runs before the suites, so a change that cannot be classified never pays for a
    // full test run. dependsOn rather than finalizedBy: the plan is an input to validation, not a
    // report about it.
    dependsOn(validationPlan)
}
