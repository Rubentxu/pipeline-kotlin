package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * P3-E E2 — a projection from a sealed outcome onto an event discriminator must be
 * COMPILER-exhaustive, not permissive.
 *
 * ## The defect this exists to prevent
 *
 * `ParallelStageEngine` projected `StepOutcome` to the `ParallelBranchFinished` wire with
 *
 * ```kotlin
 * when (branchOutcome) {
 *     is StepOutcome.Failure  -> "failure"
 *     is StepOutcome.Unstable -> "unstable"
 *     else                    -> "success"
 * }
 * ```
 *
 * The `else` reads as harmless because `StepOutcome` has exactly three cases today. It is
 * not: it is a standing invitation to report a fourth one as a **success**, in a durable
 * stream that an external observer reads as fact. A `StepOutcome.Cancelled` would never
 * have been a compile error, and it would never have been a wrong answer in review either,
 * because the projection looks correct for every input it was written against.
 *
 * The whole value of a sealed ADT at a projection boundary is that the compiler carries the
 * question forward to whoever adds the next case. An `else` hands that question back to a
 * human at exactly the moment they are least likely to be looking at this file.
 *
 * Its sibling `RetryEngine` had already solved this — with `else -> error(...)` and a
 * comment explaining why a permissive `else` would once have thrown away an `UNSTABLE` that
 * an earlier change had added. The lesson was learned once in this repository and the
 * sibling did not apply it, which is the strongest argument for pinning it mechanically.
 *
 * ## Why a scan, and what the scan does NOT claim
 *
 * The scan looks for `else -> "<outcome literal>"`. That is deliberately narrow: it
 * catches exactly the shape of this defect, and it cannot be defeated by formatting.
 *
 * Its limit is stated rather than hidden: a projection that ends in `else -> someVariable`
 * where the variable holds a computed outcome would pass this scan. Detecting that needs
 * whole-program dataflow, which is out of reach for a text fitness and would be a compiler
 * plugin's job. What the scan does guarantee is the common and the dangerous case — a
 * **literal** fallback — because a literal is a decision somebody wrote down, and written
 * decisions are what survive review.
 *
 * ## Scope
 *
 * `pipeline-application` and `pipeline-events` main sources: the two modules that project
 * runtime state onto event discriminators. Test sources are excluded on purpose — a test
 * may legitimately pattern-match for its own convenience, and a fitness that failed on test
 * code would train people to route around it.
 */
class P3EProjectionBoundaryExhaustivityFitnessTest {

    private val v2 = FitnessPaths.v2Root()

    private val projectionSources: List<Path> = listOf(
        v2.resolve("pipeline-application/src/main/kotlin"),
        v2.resolve("pipeline-events/src/main/kotlin"),
    )

    /**
     * Every outcome literal that has ever crossed the Event Plane.
     *
     * These are the historical spellings and they are deliberately NOT normalised: `failure`
     * belongs to `RunFinished`, `failed` to `StageFinished`, `succeeded` to
     * `RetryAttemptFinished`, `completed` / `deadline-exceeded` to `WaitUntilCompleted`. A
     * vocabulary that lost that distinction would let the fitness miss a fallback on the
     * less common half of a pair, so the set carries all of them.
     */
    private val outcomeWireLiterals = setOf(
        "success", "unstable", "failed", "failure", "succeeded", "failed",
        "aborted", "skipped", "completed", "deadline-exceeded",
    ).distinct()

    @Test
    fun `ninguna proyeccion de outcome cae en un literal por defecto`() {
        val offences = projectionSources.flatMap { root -> kotlinSources(root) }.flatMap { file ->
            val offenders = mutableListOf<String>()
            readTextOrEmpty(file).lines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("else ->")) return@forEachIndexed
                val literal = trimmed.removePrefix("else ->")
                    .trim()
                    .removeSurrounding("\"")
                    .substringBefore("\"")
                    .trim()
                if (literal in outcomeWireLiterals) {
                    offenders += "${file.fileName}:${index + 1}  else -> \"$literal\""
                }
            }
            offenders
        }

        assertEquals(
            emptyList<String>(),
            offences,
            "una proyeccion desde un ADT sellado no puede terminar en `else -> \"<outcome>\"`: " +
                "ese outcome no existiria todavia y el compilador no lo detectaria. " +
                "Escribe un `when` exhaustivo sin `else` para que el caso nuevo rompa el build. " +
                "Offensas:\n" + offences.joinToString("\n"),
        )
    }

    /**
     * P3-E E3 — the runtime must not reach its own stage planner by way of a STRING.
     *
     * `StageExecutionEngine.runPostBlock` used to take `stageFinishedOutcome: String` and
     * re-parse it through `PostCondition.outcomeOf`, so a decision already made as a typed
     * ADT was stringified, carried across a function boundary, and parsed back. The type
     * system could not see any of it, and the parser accepted five tokens while
     * `StageFinished` produced three — so the seam could read states the producer never
     * emitted.
     *
     * That parser still exists in `pipeline-domain` because that module is published ABI and
     * its removal is an E6 decision. What this pins is the half that is ours: **no production
     * caller**. Leaving a re-parsing seam alive but unreferenced is the only state in which
     * removing it later is safe, and it is a state that erodes on its own the first time
     * somebody finds the helper convenient.
     */
    @Test
    fun `el parser de outcome de stage no tiene consumidores de produccion`() {
        val offenders = projectionSources.flatMap { root -> kotlinSources(root) }.filter { file ->
            // Only a CALL counts. The declaration itself lives in pipeline-domain and the
            // prose in a KDoc is not a caller either.
            val text = readTextOrEmpty(file)
            Regex("PostCondition\\s*\\.\\s*outcomeOf\\s*\\(").containsMatchIn(text) &&
                !text.lineSequence().any { it.trimStart().startsWith("*") }
        }.map { it.fileName.toString() }

        assertTrue(
            offenders.isEmpty(),
            "PostCondition.outcomeOf(String) ya no debe tener consumidores de produccion: " +
                "reencontrar el outcome de stage por una cadena devuelve el runtime al bucle " +
                "typed -> String -> typed. Offensores: $offenders",
        )
    }

    /** Every Kotlin source under [root]; an absent root is empty rather than an exception. */
    private fun kotlinSources(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { stream ->
            return stream.filter { path -> path.toString().endsWith(".kt") }
                .collect(Collectors.toList())
        }
    }

    /**
     * Read defensively: a source this fitness cannot read must not be silently treated as
     * compliant. Returning "" makes it invisible to the scan, so the read is wrapped to keep
     * an unreadable file from passing as a clean one — the same reasoning as the sibling
     * fitness, for the same reason.
     */
    private fun readTextOrEmpty(path: Path): String =
        runCatching { Files.readString(path) }.getOrDefault("")
}
