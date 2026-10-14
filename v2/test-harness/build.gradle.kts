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

tasks.named("check") {
    // The planner runs before the suites, so a change that cannot be classified never pays for a
    // full test run. dependsOn rather than finalizedBy: the plan is an input to validation, not a
    // report about it.
    dependsOn(validationPlan)
}
