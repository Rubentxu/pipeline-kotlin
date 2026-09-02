package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.LegacyOutcomeMapper
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunCoordinator
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.RunRequest
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import kotlinx.coroutines.runBlocking

/**
 * Seam between the [DurableRunCoordinator] and the legacy durable walker.
 *
 * The signature is exactly the legacy orchestrator entry point
 * (`PipelineOrchestrator.run`). Keeping it a `fun interface` lets the
 * composition root hand in the real orchestrator as a lambda while tests
 * substitute a fake — the coordinator itself never depends on the
 * concrete orchestrator class.
 */
fun interface DurableRunDelegate {
    suspend fun run(spec: PipelineSpec, runId: String, startFromCursor: Boolean): Result<String>
}

/**
 * Production [RunCoordinator] for the durable runtime (LF-0205 redirect).
 *
 * Routes every CLI invocation through the `RunCoordinator` port: the CLI
 * no longer calls the orchestrator (or the walker) directly. During the
 * M2 transition the coordinator delegates to the legacy walker behind
 * [DurableRunDelegate] and crosses the string boundary exactly once,
 * through [LegacyOutcomeMapper] — the stable failure mapping (M2-005).
 *
 * ## Semantics
 *
 * - **Spec resolution**: the compiled definition must have been registered
 *   in [SpecRegistry] by the composition root; a miss fails closed before
 *   any execution.
 * - **Resume**: [RunRequest.resumeFromCursor] maps 1:1 to the legacy
 *   `startFromCursor` flag (journal-backed resume, LF-0206 will formalise
 *   it). [RunRequest.resumeAfter] is NOT supported on the durable surface
 *   yet — requesting it fails closed without dispatching.
 * - **Outcome mapping**: delegate success strings go through
 *   [LegacyOutcomeMapper]; delegate failure (`Result.failure`, i.e.
 *   divergence) and unexpected delegate exceptions both map to
 *   [RunOutcome.Failure] with an [FailureKind.INFRASTRUCTURE] carrier —
 *   the event log already carries the diagnostic detail (the orchestrator
 *   emits `RunFinished` before returning).
 *
 * @see RunCoordinator
 * @see SpecRegistry
 * @see LegacyOutcomeMapper
 */
class DurableRunCoordinator(
    private val delegate: DurableRunDelegate,
    private val specs: SpecRegistry,
) : RunCoordinator {

    override fun run(request: RunRequest): RunOutcome {
        if (request.resumeAfter != null) {
            throw IllegalArgumentException(
                "resumeAfter is not supported by the durable coordinator until LF-0206; " +
                    "use resumeFromCursor for journal-backed resume"
            )
        }
        val spec = specs.resolve(request.definition.id)
        val result = try {
            runBlocking {
                delegate.run(spec, request.runId.value, startFromCursor = request.resumeFromCursor)
            }
        } catch (ex: Exception) {
            return infrastructureFailure(ex)
        }
        return result.fold(
            onSuccess = { legacy -> LegacyOutcomeMapper.toRunOutcome(legacy) },
            onFailure = { ex -> infrastructureFailure(ex) },
        )
    }

    private fun infrastructureFailure(ex: Throwable): RunOutcome.Failure =
        RunOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.INFRASTRUCTURE,
                message = "durable run failed: ${ex::class.java.simpleName}: ${ex.message ?: "no message"}",
                cause = ex,
            )
        )
}
