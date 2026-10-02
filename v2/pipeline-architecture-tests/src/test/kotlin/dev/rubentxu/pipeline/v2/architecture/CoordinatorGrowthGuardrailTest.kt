package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-PR-017 / PR-020 guardrail: the durable coordinator may only SHRINK.
 *
 * `CanonicalDurableRunCoordinator` spent its life accreting responsibilities
 * — run lifecycle, stage bookends, body loops, retry, waitUntil, credential
 * leases, failure folding. The PR-017..020 trains extract those into named
 * engines (RunLifecycleEngine done; BodyExecution/Invocation/Recovery next).
 * This guardrail makes the old direction (grow in place) impossible to slip
 * through a review: any change that adds lines to the coordinator must bump
 * this ceiling IN THE SAME COMMIT, with the commit message justifying why an
 * extraction could not absorb it.
 *
 * The ceiling is pinned at the exact current size (552 lines). It is
 * deliberately a ratchet, not a final target: PR-020's real property is
 * "the coordinator only coordinates and no Step/body/replay semantics live
 * there", enforced by the concrete-routing fitness tests; this file stops
 * the tape measure from running backwards while those slices land.
 */
class CoordinatorGrowthGuardrailTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private val coordinatorSource = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
    )

    /**
     * The ratchet. History: 2592 (post RP-035) -> 2562 (slice 1, lifecycle
     * bookends out) -> 2514 (slice 2, stage bookends out) -> 2032 (H3 close)
     * -> 1728 (PR-020 slice 1, the BEFORE_STAGE directive seam out) -> 1701
     * (slice 2a, the dead waitUntil wrapper out) -> 741 (slice 2b, the step
     * spine and the parallel aggregate out) -> 552 (slice 4, the stage body and its `post`
     * finalizers out).
     *
     * Pinned to the exact current size on purpose. A ceiling left at 2514 while
     * the file is 741 is not a ratchet: it would take 1773 lines of regression to
     * trip, which is the whole class of growth this guard exists to stop. Raising
     * it requires a same-commit justification per the class KDoc.
     */
    private val maxCoordinatorLines = 552L

    @Test
    fun `the durable coordinator never grows again`() {
        require(Files.exists(coordinatorSource)) {
            "Coordinator source moved? Update this guardrail's path: $coordinatorSource"
        }
        val lines = Files.readAllLines(coordinatorSource).size
        assertTrue(
            lines <= maxCoordinatorLines,
            "CanonicalDurableRunCoordinator.kt grew to $lines lines (ceiling $maxCoordinatorLines). " +
                "New responsibilities belong in named engines (RunLifecycle, BodyExecution, " +
                "Invocation/Recovery, BeforeStageDirective, StepDispatch, ParallelStage, " +
                "StageExecution), not in the " +
                "coordinator. If a same-commit extraction truly cannot absorb " +
                "the change, raise this ceiling deliberately and justify it in the commit message.",
        )
    }

    @Test
    fun `the extracted lifecycle engine is the only bookend owner`() {
        val engine = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RunLifecycleEngine.kt",
        )
        require(Files.exists(engine)) { "RunLifecycleEngine disappeared: $engine" }
        // Comment-blind view: prose may name the events; code may not emit them.
        val coordinator = Files.readString(coordinatorSource).lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            !coordinator.contains("RunStarted(") && !coordinator.contains("RunFinished("),
            "run bookends must be emitted by RunLifecycleEngine, not re-inlined into the coordinator",
        )
        assertTrue(
            !coordinator.contains("StageStarted(") && !coordinator.contains("StageFinished("),
            "stage bookends must be emitted by RunLifecycleEngine, not re-inlined into the coordinator",
        )
    }
}
