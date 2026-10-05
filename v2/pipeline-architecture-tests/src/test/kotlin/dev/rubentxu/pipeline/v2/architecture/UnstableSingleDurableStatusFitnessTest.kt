package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * P1 of the Runtime Observation Contract Closure: `Unstable` must have ONE durable meaning.
 *
 * ## Why this file exists next to `P1UnstableSingleDurableStatusTest`
 *
 * That test crosses `StepOutcome.toOperationStatus()`, which is reachable and therefore covered
 * behaviourally. The parallel aggregate is a **`private` function** (`ParallelStageEngine
 * .aggregateStatusOf`) and the retry arm is reached only through the full durable loop. Neither is
 * reachable from a unit test without rebuilding a coordinator, and per Harness Fidelity Law a
 * harness that cannot cross the production authority must declare itself `model`. So the
 * behavioural claim is split deliberately: one reachable authority tested, two private authorities
 * read from source — and neither half is left silently uncovered.
 *
 * ## The defect class this guards, which is NOT a `when`
 *
 * `OperationStatus.isTerminal` is `this in terminalStates`, and `terminalStates` is a `setOf` in a
 * companion object. Adding `UNSTABLE` to the enum therefore compiled, and 21 modules recompiled
 * with zero errors, while `UNSTABLE` was **not** a terminal state. Two consequences followed:
 *
 * - `transition(RUNNING, UNSTABLE)` returned `Result.failure`, so the very journal write the change
 *   introduced was an illegal transition.
 * - `isTerminal` was false, so `OperationJournal` never stamped `endedAt`: an operation that
 *   completed was recorded with no end time.
 *
 * A `when` would have refused to compile. A `setOf` cannot. The row
 * [every durable status production persists is legally reachable] exists so this is a structural
 * law rather than something a test happened to trip over.
 *
 * ## Why sources are read with COMMENTS STRIPPED
 *
 * Same reason as `SingleDurableAuthorityFitnessTest`: without stripping, the KDoc added alongside
 * this change names `OperationStatus.UNSTABLE` and the KDoc on the enum lists `UNSTABLE` among its
 * terminal states, so a fitness scan that a comment can pass is a fitness scan that measures prose.
 */
class UnstableSingleDurableStatusFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private fun productionSources(): List<Path> = ScannerSupport.walkKotlinFiles(v2Root)
        .filter { it.toString().replace('\\', '/').contains("/src/main/") }
        .map { it.toAbsolutePath().normalize() }

    /** Same deliberately-simple stripper as the sibling fitness; see its KDoc for the rationale. */
    private fun codeOnly(source: String): String = buildString {
        var i = 0
        while (i < source.length) {
            when {
                source[i] == '"' -> {
                    append(source[i]); i++
                    while (i < source.length) {
                        if (source[i] == '\\' && i + 1 < source.length) {
                            append(source[i]); append(source[i + 1]); i += 2
                            continue
                        }
                        append(source[i])
                        val closing = source[i] == '"'
                        i++
                        if (closing) break
                    }
                }
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                else -> {
                    append(source[i]); i++
                }
            }
        }
    }

    /**
     * The semantic cases whose durable meaning this item owns. Kept in ONE list so adding a fourth
     * collapsing site is a deliberate edit here rather than a silent divergence.
     */
    private val unstableCaseRegex = Regex("""(StepOutcome|BranchTerminal)\.Unstable""")

    /** A later `StepOutcome.X`/`BranchTerminal.X` ends the current arm's window. */
    private val nextCaseRegex = Regex("""(StepOutcome|BranchTerminal)\.[A-Z]""")

    private val statusRefRegex = Regex("""OperationStatus\.([A-Z_]+)""")

    /**
     * For every production reference to an `Unstable` semantic case, the durable statuses written in
     * that arm. Returns `(file, context, statusName)` triples.
     */
    private fun durableStatusesWrittenForUnstable(): List<Triple<String, String, String>> =
        productionSources().flatMap { path ->
            val code = codeOnly(Files.readString(path))
            val rel = v2Root.relativize(path).toString().replace('\\', '/')
            unstableCaseRegex.findAll(code).mapNotNull { match ->
                // The arm ends where the next case begins, so a neighbouring arm's status is not
                // attributed to this one. The cap is a backstop against a pathologically long arm.
                val window = code.substring(
                    match.range.last + 1,
                    minOf(match.range.last + 1 + 800, code.length),
                )
                val boundary = nextCaseRegex.find(window)?.range?.first ?: window.length
                val arm = window.substring(0, boundary)
                val statuses = statusRefRegex.findAll(arm).map { it.groupValues[1] }.toList()
                if (statuses.isEmpty()) {
                    null
                } else {
                    Triple(rel, match.value, statuses.joinToString(", "))
                }
            }.toList()
        }

    @Test
    fun `every Unstable arm writes only UNSTABLE, never a collapse`() {
        val offenders = durableStatusesWrittenForUnstable()
            .filter { (_, _, statuses) ->
                statuses.split(", ").any { it != "UNSTABLE" }
            }

        assertTrue(
            offenders.isEmpty(),
            "A declared-unstable run has exactly ONE durable meaning. It is not a failure and not " +
                "an abort, so an arm that maps it onto FAILED or ABORTED contradicts the run-level " +
                "terminal the CLI already reported and makes a child row disagree with the " +
                "aggregate row of the same branch. This is precisely the three-way divergence P1 " +
                "closed. Found arms writing a collapse:\n" +
                offenders.joinToString("\n") { "\t${it.first}  (${it.second} -> ${it.third})" },
        )
    }

    @Test
    fun `the known Unstable arms are all still mapped, so the row above is not vacuous`() {
        val actual = durableStatusesWrittenForUnstable()
            .map { it.first }
            .distinct()
            .sorted()

        assertEquals(
            listOf(
                "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalStructuralDecisions.kt",
                "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ParallelStageEngine.kt",
                "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/retry/RetryEngine.kt",
            ),
            actual,
            "P1 unified exactly three sites. If one was renamed, moved or deleted, the first row " +
                "would pass by having nothing left to check — which is a false green, not a pass. " +
                "Re-point this list deliberately when the set of sites genuinely changes.",
        )
    }

    /**
     * The defect class a `when` cannot catch: a status that exists in the enum but is missing from
     * `terminalStates`, so it is not terminal and `RUNNING -> it` is refused.
     *
     * Reachability is not re-derived here — it is ASKED of `OperationStatus.transition`, the same
     * function the journal would use, because re-computing the answer to check the answer is a test
     * of the test's own arithmetic.
     *
     * `PENDING` and `RUNNING` are excluded because they are legitimately in-flight writes
     * (`compositeRunningRow` writes RUNNING). Everything else production writes is a status the
     * operation *finished* with, and every such status must be terminal — otherwise the journal
     * write violates the contract and `endedAt` is never stamped.
     */
    @Test
    fun `every durable status production finishes with is terminal in the algebra`() {
        val written = productionSources()
            .flatMap { path ->
                val code = codeOnly(Files.readString(path))
                val rel = v2Root.relativize(path).toString().replace('\\', '/')
                statusRefRegex.findAll(code)
                    .map { rel to it.groupValues[1] }
                    .toList()
            }
            .distinct()

        val notTerminal = written.filter { (_, status) ->
            status != "PENDING" && status != "RUNNING" && !isTerminalInProduction(status)
        }

        assertTrue(
            notTerminal.isEmpty(),
            "Production writes a durable status that the production state machine would REFUSE from " +
                "a RUNNING row. `terminalStates` is a `setOf`, not a `when`, so adding a case to the " +
                "enum compiles cleanly — 21 modules rebuilt with zero errors — while leaving the " +
                "state machine closed against it. That is the exact failure this item hit with " +
                "`UNSTABLE`. The loud symptom is `RetryEngine.persistTerminalTransition`'s runtime " +
                "`else -> error(...)`; the silent one is a journal write that violates its own " +
                "contract, and a completed operation whose `endedAt` is never stamped. Found:\n" +
                notTerminal.joinToString("\n") { "\t${it.first}  (OperationStatus.${it.second})" },
        )
    }

    /**
     * The production answer, not a local copy of the rule. `transition(RUNNING, s)` succeeds exactly
     * when `s` is RUNNING or terminal, so asking it is asking whether the state machine accepts a
     * row that finished as `s`.
     */
    private fun isTerminalInProduction(status: String): Boolean =
        OperationStatus.entries
            .firstOrNull { it.name == status }
            ?.let { OperationStatus.transition(OperationStatus.RUNNING, it).isSuccess }
            ?: false
}
