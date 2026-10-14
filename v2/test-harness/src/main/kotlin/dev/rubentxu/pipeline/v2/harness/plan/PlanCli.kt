package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The impure edge: turns a Git range into changed paths, and prints the plan a build must run.
 *
 * ## Why this is separate from [ImpactResolver]
 *
 * The resolver decides; this reads and writes. Keeping the Git call out of the resolver is what
 * lets the fail-closed property be tested without a repository, and it means the one place that
 * can be wrong about "what changed" is a single small parser rather than logic interleaved with
 * the policy.
 *
 * ## Why it exits non-zero on a refusal
 *
 * A planner that prints a refusal and exits 0 is a planner nobody reads, because the build goes
 * green. So the exit code IS the contract: 0 means a runnable plan was produced, 2 means the change
 * cannot be validated with what the catalogue knows, and 1 is reserved for "I could not find out
 * what changed". The caller in `build.gradle.kts` treats any non-zero as a build failure, because a
 * warning is how an unclassified production file learns to be tolerated.
 */
object PlanCli {

    /**
     * Parse `git diff --name-only` output.
     *
     * Tolerant of blank lines and of the quotes Git adds for non-ASCII names, and intolerant of
     * anything else: a line that is not a path means the output was not what we asked for, and
     * guessing would be how a plan silently selects nothing.
     */
    fun parseDiffOutput(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> line.removeSurrounding("\"").removePrefix("./") }
            .toList()

    /** The changed paths between [base] and HEAD, read from Git. */
    fun changedPaths(repoRoot: Path, base: String): List<String> {
        val process = ProcessBuilder("git", "diff", "--name-only", "$base...HEAD")
            .directory(repoRoot.toFile())
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git diff --name-only $base...HEAD failed (exit $exit): $stderr" }
        return parseDiffOutput(stdout)
    }

    /** Render a plan for the build log, so a refusal is legible rather than a stack trace. */
    fun render(plan: ValidationPlan): String = when (plan) {
        is ValidationPlan.Refused -> buildString {
            appendLine("VALIDATION REFUSED — ${plan.reasons.size} reason(s)")
            plan.reasons.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("The change cannot be certified with what the catalogue knows.")
            appendLine("Either add a catalogue row for the unclassified path, or remove it.")
        }

        is ValidationPlan.Selected -> buildString {
            appendLine("VALIDATION PLAN — ${plan.capabilities.size} capability(ies)")
            appendLine("suites (${plan.suites.size}):")
            plan.suites.sortedBy { it.filter }.forEach { appendLine("  - ${it.filter}") }
            appendLine("minimum fidelity: ${plan.minimumFidelities.joinToString { it.name }}")
            appendLine("reasons:")
            plan.reasons.forEach { appendLine("  - $it") }
        }
    }

    /** Resolve, render, and halt with 2 when refused. Exposed so tests can drive it directly. */
    fun run(args: Array<String>, repoRoot: Path = Paths.get("").toAbsolutePath().normalize()) {
        val base = args.firstOrNull()
            ?: error("usage: PlanCli <base-ref>; e.g. PlanCli origin/main")
        val plan = ImpactResolver.resolve(changedPaths(repoRoot, base))
        println(render(plan))
        System.out.flush()
        if (plan is ValidationPlan.Refused) Runtime.getRuntime().halt(2)
    }
}

/**
 * JVM entry point.
 *
 * A top-level function rather than `PlanCli.main` on the object, because Kotlin compiles an
 * object's `main` into an INSTANCE method: `java -cp ... PlanCli` then fails with JavaFX's
 * misleading "main method not found in javafx.application.Application". That cost one run to
 * discover, and the message points at JavaFX rather than at this mistake, so the indirection pays
 * for itself the first time someone else hits it.
 */
fun main(args: Array<String>) = PlanCli.run(args)
