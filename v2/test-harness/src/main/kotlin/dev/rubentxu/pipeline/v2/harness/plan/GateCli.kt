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

    /**
     * The repository root: the nearest ancestor that CONTAINS the `v2/` build.
     *
     * The DIRECTION of the search is the whole point, and it is easy to get backwards. `v2/` is a
     * CHILD of the checkout root, so an upward walk can never find it — the answer is always "up to
     * a directory, then down into `v2/`". Searching upwards for a directory that *is* the build
     * root is what broke the real gate run: this checkout's root is itself a composite Gradle build
     * (`includeBuild("v2")`), so a settings-file probe matched the root, and the caller then took
     * its PARENT — a directory outside the checkout. `git status` there failed with "not a git
     * repository": a correct refusal, of the wrong tree, for a reason the operator could not act on.
     *
     * The discriminator is "does this directory contain a `v2/` that is a real build root", which
     * asks about a CHILD and therefore cannot be confused by an ancestor that is a build too.
     */
    fun repoRoot(start: Path): Path {
        var dir: Path? = start.toAbsolutePath().normalize()
        while (dir != null) {
            if (isBuildRoot(dir.resolve("v2"))) return dir
            dir = dir.parent
        }
        error("no directory containing a v2/ build root above $start")
    }

    /** True for the directory that owns BOTH the settings file and the Gradle wrapper. */
    private fun isBuildRoot(dir: Path): Boolean =
        Files.exists(dir.resolve("settings.gradle.kts")) && Files.exists(dir.resolve("gradlew"))

    /**
     * The `v2/` build root: [repoRoot]'s `v2/` child by construction.
     *
     * [repoRoot] already proved this directory is a build root, so the check here is about the
     * CONTRACT rather than the lookup: naming a directory that is not a build would turn every
     * later failure into a confusing Gradle error instead of one line naming what is missing.
     */
    fun v2Root(start: Path): Path = repoRoot(start).resolve("v2")

    /**
     * Paths that make the tree un-bindable to a commit.
     *
     * ## Why untracked files are usually ignored
     *
     * A working checkout is full of scratch files — notes, a receipt being drafted, another
     * session's document. Refusing on every untracked path would make the gate unusable in practice,
     * and a gate people disable is worse than no gate.
     *
     * ## Why BUILDABLE sources are not in that category
     *
     * An untracked `.kt` under a source root is not scratch: Gradle compiles it. `git diff
     * base...HEAD` does not describe it, so the plan omits its capability while the candidate
     * carries its behaviour — a receipt for bytes the commit does not contain. That is the exact
     * shape this gate exists to refuse, so it refuses it too.
     *
     * The discriminator is BUILDABILITY, not novelty, and it is deliberately narrow: the Kotlin and
     * Java source roots of the build. Widening it to "any untracked file under `v2/`" would refuse
     * on build outputs and reports, which are untracked by design.
     */
    fun dirtyPaths(repoRoot: Path): List<String> {
        val status = gitStatus(repoRoot)
        val tracked = status.filterNot { it.untracked }.map { it.path }
        val untracked = status.filter { it.untracked }
            .map { it.path }
            .filter { isBuildableSource(it) }
        return (tracked + untracked).distinct()
    }

    /**
     * One porcelain status row, with the untracked marker KEPT.
     *
     * The distinction is load-bearing and was the bug this shape replaced: [gitStatus] used to strip
     * the two status columns before returning, which silently discarded the only signal that said
     * whether a path was untracked. Callers then filtered on `?? ` against data that could no longer
     * contain it, and the untracked-source refusal never fired. Reading both halves in one value
     * makes that unrepresentable rather than something a future edit can undo by accident.
     */
    data class StatusRow(val untracked: Boolean, val path: String)

    /**
     * True for a file Gradle would compile into the candidate, by extension and tree.
     *
     * Repository-relative on purpose: the caller already has paths in Git's own form, and
     * re-anchoring them against a filesystem would be the kind of join that silently mismatches.
     */
    fun isBuildableSource(path: String): Boolean {
        val inSourceTree = path.startsWith("v2/") || path.startsWith("examples/")
        if (!inSourceTree) return false
        return path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".kts")
    }

    /**
     * Porcelain v1 status for [repoRoot], one [StatusRow] per line.
     *
     * Both status columns are read, because both answer a different question: `?` means "not in the
     * commit at all", anything else means "in the commit but modified".
     */
    private fun gitStatus(repoRoot: Path): List<StatusRow> {
        val process = ProcessBuilder("git", "status", "--porcelain", "--untracked-files=all")
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git status failed: $output" }
        // Porcelain v1: "XY path", two status columns then a space. Renames use "old -> new" and the
        // NEW path is the one that exists on disk, so it is the side that matters here.
        return output.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val status = line.substring(0, 2)
                val raw = line.substring(3).trim()
                StatusRow(
                    untracked = status == "??",
                    path = raw.substringAfter(" -> "),
                )
            }
            .filter { it.path.isNotEmpty() }
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
     * [provenance] is the INSTALLED distribution's own answer about which commit it carries. It is
     * read, never passed in: a caller-supplied observed commit lets one value satisfy both sides of
     * [ImpactResolver.adjudicate]'s comparison, which is how a real run discharged on `SHA != SHA`.
     * [Unreadable][ObservedProvenance.Unreadable] refuses rather than assuming, so a missing
     * distribution and a matching one are never confused.
     */
    fun run(
        base: String,
        repoRoot: Path,
        runner: SuiteRunner,
        provenance: ObservedProvenance,
        expectedCommit: String,
    ): Outcome {
        val dirty = dirtyPaths(repoRoot)
        if (dirty.isNotEmpty()) return Outcome.DirtyTree(dirty)

        val plan = ImpactResolver.resolve(PlanCli.changedPaths(repoRoot, base))
        // BQ1 v2 (typed CLI gate, 2026-10-17): the previous
        // shape was `if (plan is Refused) return ...; val
        // selected = plan as ValidationPlan.Selected`. The
        // unchecked cast is replaced with an exhaustive
        // `when` so the compiler narrows the type via the
        // destructured binding. A future third
        // [ValidationPlan] case would force the `when` to
        // grow at compile time. Mirrors the change in
        // `ImpactResolver.adjudicate` (commit 8f03eb3b
        // sibling).
        val selected: ValidationPlan.Selected = when (plan) {
            is ValidationPlan.Refused -> return Outcome.Refused(plan)
            is ValidationPlan.Selected -> plan
        }
        val invocations = Dispatch.dispatch(selected)
        val refusal = Gate.run(
            plan = selected,
            invocations = invocations,
            runner = runner,
            resultsRoot = repoRoot.resolve("v2"),
            expectedCommit = expectedCommit,
            observedCommit = provenance.commitOrNull(),
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
        is SelectionRefusal.IncompleteEvidence ->
            "suite ${refusal.suite} evidence is incomplete: ${refusal.missingField}"
        is SelectionRefusal.StaleBinary ->
            "suite ${refusal.suite} ran against commit ${refusal.observed}, not ${refusal.expected}"
        is SelectionRefusal.InsufficientFidelity ->
            "suite ${refusal.suite} ran at ${refusal.observed}, required one of ${refusal.required}"
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
        ?: error("usage: main <base-ref> <expected-commit>")
    val expectedCommit = args.getOrNull(1)
        ?: error("usage: main <base-ref> <expected-commit>")
    // There is deliberately no observed-commit argument. It existed so the operator could name both
    // sides of the provenance comparison, which made the comparison agree with itself; the observed
    // side is now read from the installed distribution below.
    check(args.size == 2) { "usage: main <base-ref> <expected-commit>" }

    val root = GateCli.repoRoot(Paths.get("").toAbsolutePath().normalize())
    val buildRoot = GateCli.v2Root(root)

    // Read BEFORE the suites run, so a refusal costs a `version --json` rather than the whole build.
    // Adjudication still happens after: this only decides whether an observed value exists.
    val provenance = InstalledDistribution.read(buildRoot)
    println("provenance: ${provenance.describe()}")

    val outcome = GateCli.run(
        base = base,
        repoRoot = root,
        // Two directories, because they answer different questions: the repo root is what Git
        // runs in and where report directories hang off, while the build root is the Gradle build
        // that OWNS the modules. Passing the repo root as both is what made every dispatch fail
        // with "task not found in root project 'pipeline-kotlin'".
        runner = GradleSuiteRunner(repoRoot = root, buildRoot = buildRoot),
        provenance = provenance,
        expectedCommit = expectedCommit,
    )

    println(GateCli.render(outcome))
    System.out.flush()
    if (outcome !is GateCli.Outcome.Discharged) Runtime.getRuntime().halt(2)
}
