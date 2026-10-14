package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The gate as the build sees it: resolve, dispatch, run, adjudicate, and refuse on disagreement.
 *
 * ## Why this is separate from [PlanCli]
 *
 * [PlanCli] prints the plan and exits 2 on a refusal. That answers "what should be tested" and
 * stops. It never runs anything and never looks at what ran, so a plan nobody discharges still
 * builds green. This is the other half: it produces the same plan, and then holds the change to it.
 *
 * ## Why it refuses on a dirty tree
 *
 * The plan is computed from `git diff base...HEAD`, which ignores uncommitted work. A dirty tree
 * therefore produces a plan that does not describe the code about to be built, and the evidence
 * would be attributed to a commit that does not contain what was tested. For a candidate that is
 * not a nuance: it is a receipt for bytes that do not exist. Refuse rather than certify it.
 *
 * ## Exit codes
 *
 * `0` the plan was discharged. `2` the change was refused, by the planner or by the evidence. `1`
 * something went wrong deciding, which is distinct from a refusal: a refusal is a verdict, an
 * error is the absence of one.
 */
object GateCli {

    /** The `v2/` directory, found by walking up to `settings.gradle.kts`. */
    fun v2Root(start: Path): Path {
        var dir: Path? = start.toAbsolutePath().normalize()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("no settings.gradle.kts above $start")
    }

    /** The repository root, which is the parent of `v2/`. */
    fun repoRoot(start: Path): Path = v2Root(start).parent

    /** Paths Git reports as modified but uncommitted, ignoring untracked files. */
    fun dirtyPaths(repoRoot: Path): List<String> {
        val process = ProcessBuilder("git", "status", "--porcelain", "--untracked-files=no")
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git status failed: $output" }
        // Porcelain v1: "XY path". The first two columns are the index and worktree status; a path
        // is dirty when either is not a space and not '?'.
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.substring(3).trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** What the gate decided, so the CLI and the tests agree on the shape. */
    sealed interface Outcome {
        /** The plan ran and the evidence discharged it. */
        data class Discharged(val suites: Int, val invocations: Int) : Outcome

        /** The change was refused. Never a pass. */
        data class Refused(val plan: ValidationPlan.Refused) : Outcome

        /** The tree is dirty, so no evidence could be attributed to a commit. */
        data class DirtyTree(val paths: List<String>) : Outcome
    }

    /**
     * Runs the gate against [base] and reports what it decided.
     *
     * [observedCommit] is what the installed binary reports; passing null means "unknown", which
     * [ImpactResolver.adjudicate] treats as a refusal rather than as a match.
     */
    fun run(
        base: String,
        repoRoot: Path,
        runner: SuiteRunner,
        observedCommit: String?,
        expectedCommit: String,
    ): Outcome {
        val dirty = dirtyPaths(repoRoot)
        if (dirty.isNotEmpty()) return Outcome.DirtyTree(dirty)

        val plan = ImpactResolver.resolve(PlanCli.changedPaths(repoRoot, base))
        if (plan is ValidationPlan.Refused) return Outcome.Refused(plan)

        val selected = plan as ValidationPlan.Selected
        val invocations = Dispatch.dispatch(selected)
        val refusal = Gate.run(
            plan = selected,
            invocations = invocations,
            runner = runner,
            resultsRoot = repoRoot.resolve("v2"),
            expectedCommit = expectedCommit,
            observedCommit = observedCommit,
        )
        return if (refusal == null) {
            Outcome.Discharged(selected.suites.size, invocations.size)
        } else {
            Outcome.Refused(refusal)
        }
    }

    /** Render the outcome for the build log, so a refusal is legible rather than a stack trace. */
    fun render(outcome: Outcome): String = when (outcome) {
        is Outcome.Discharged ->
            "VALIDATION DISCHARGED — ${outcome.suites} suite(s) in ${outcome.invocations} invocation(s)"

        is Outcome.DirtyTree -> buildString {
            appendLine("VALIDATION REFUSED — the working tree is dirty")
            appendLine("Uncommitted changes, so the plan and the evidence cannot be bound to a commit:")
            outcome.paths.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("Commit or stash the change before producing evidence.")
        }

        is Outcome.Refused -> buildString {
            appendLine("VALIDATION REFUSED — ${outcome.plan.reasons.size} reason(s)")
            outcome.plan.reasons.forEach { appendLine("  - ${describe(it)}") }
            appendLine()
            appendLine("The change cannot be certified with what the catalogue and the run know.")
        }
    }

    /** One refusal as a sentence naming the operator action. */
    private fun describe(refusal: SelectionRefusal): String = when (refusal) {
        is SelectionRefusal.UnclassifiedPath ->
            "unclassified production path ${refusal.path}: add a catalogue row or remove the file"
        is SelectionRefusal.MissingSuite ->
            "capability ${refusal.capability} has no suite bound (${refusal.suite})"
        is SelectionRefusal.SelectedButNotRun ->
            "suite ${refusal.suite} was selected but produced no clean evidence"
        is SelectionRefusal.SuiteDidNotRun ->
            "suite ${refusal.suite} did not run: Gradle failed for ${refusal.gradleProject}"
        is SelectionRefusal.StaleEvidence ->
            "evidence was produced by commit ${refusal.observed}, not ${refusal.expected}"
    }
}

/**
 * JVM entry point for the gate task.
 *
 * Named `main` in the file `GateCli.kt` because that is what makes the class `GateCliKt`: Kotlin
 * derives the JVM class from the FILE name, not the function name. A function called `gateMain`
 * would still land in `GateCliKt`, and pointing `mainClass` at anything else is the misleading
 * JavaFX "main method not found" error recorded in [PlanCli].
 */
fun main(args: Array<String>) {
    val base = args.firstOrNull()
        ?: error("usage: main <base-ref> <expected-commit> [observed-commit]")
    val expectedCommit = args.getOrNull(1)
        ?: error("usage: main <base-ref> <expected-commit> [observed-commit]")
    // A missing third argument means the binary's commit is UNKNOWN, and adjudicate refuses on
    // unknown rather than treating it as a match. That is the fail-closed direction: the operator
    // has to name the commit they observed.
    val observedCommit = args.getOrNull(2)

    val root = GateCli.repoRoot(Paths.get("").toAbsolutePath().normalize())
    val gradlew = root.resolve("v2/gradlew")

    val outcome = GateCli.run(
        base = base,
        repoRoot = root,
        runner = GradleSuiteRunner(repoRoot = root, gradlew = gradlew),
        observedCommit = observedCommit,
        expectedCommit = expectedCommit,
    )

    println(GateCli.render(outcome))
    System.out.flush()
    if (outcome !is GateCli.Outcome.Discharged) Runtime.getRuntime().halt(2)
}
