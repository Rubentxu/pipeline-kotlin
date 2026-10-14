plugins {
    kotlin("jvm")
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

// ---------------------------------------------------------------------------------------------
// H1 — the gate. `validationPlan` above computes and PRINTS the selection; it never runs anything
// and never looks at what ran, so a plan nobody discharges still builds green. This task closes
// that loop: it resolves the same plan, dispatches it as Gradle invocations, runs them, reads the
// JUnit XML they produced, and refuses unless the evidence discharges the selection.
//
// It is a separate task rather than extra behaviour on `validationPlan` on purpose. Selection,
// execution and adjudication are three decisions; a task that both selects and certifies can be
// trusted for none of them, because a narrowing of the selection and a narrowing of the run look
// identical from the outside.
//
// `-PvalidationCommit=<sha>` is the commit the evidence must be bound to, and
// `-PvalidationObservedCommit=<sha>` is what the installed binary reports. Both are explicit
// because the gate must never infer its own identity: an omitted observed commit is UNKNOWN, and
// unknown refuses rather than matching.
// ---------------------------------------------------------------------------------------------

val validationCommit: String? = findProperty("validationCommit") as String?

val validationObservedCommit: String? = findProperty("validationObservedCommit") as String?

val validationGate by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run the suites the plan selected and refuse unless their evidence discharges it."

    mainClass.set("dev.rubentxu.pipeline.v2.harness.plan.GateCliKt")
    classpath = sourceSets["main"].runtimeClasspath

    // onlyIf is evaluated before doFirst, so the commit is appended here rather than in a doFirst:
    // a task whose arguments depend on a condition should not build them in an action block.
    onlyIf {
        if (validationCommit == null) {
            logger.lifecycle(
                "validationGate: SKIPPED — pass -PvalidationCommit=<sha> to bind the evidence. " +
                    "This task does not guess a commit, and an unbound receipt certifies nothing.",
            )
            false
        } else {
            args(validationBase, validationCommit!!, validationObservedCommit ?: "")
            logger.lifecycle(
                "validationGate: binding evidence to ${validationCommit} against $validationBase. " +
                    "The working tree must be clean: uncommitted work is not in " +
                    "$validationBase...HEAD and cannot be attributed to that commit.",
            )
            true
        }
    }
}

tasks.named("check") {
    // The planner runs before the suites, so a change that cannot be classified never pays for a
    // full test run. dependsOn rather than finalizedBy: the plan is an input to validation, not a
    // report about it.
    dependsOn(validationPlan)
}
