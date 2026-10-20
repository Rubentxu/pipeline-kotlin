package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepDecoder
import dev.rubentxu.pipeline.v2.domain.StepNode

/**
 * Legacy compatibility preparation to the closed core typed world (CDE.2-d / CDE.3-b3).
 *
 * Owns the concrete legacy typed decode ([CanonicalCoreStepDecoder]) and wraps its decoded
 * [PreparedLegacyExecution]. [prepare] is the legacy STRATEGY's preparation/admission phase: it runs
 * typed decode and returns a closed [ExecutionPreparation], but NEVER produces Step side effects
 * (the common executor does that). The durable protocol calls [prepare] only inside the Execute
 * resolution and receives a [PreparedExecution] opaque to it; it never names the decoded command
 * world. This is the seam a registry strategy will later occupy without touching journal/replay/
 * fingerprint/cursor/lifecycle.
 */
object LegacyExecutionBoundary {
    /**
     * Decodes and admits a step into a [PreparedExecution].
     *
     * [Rejected] means the legacy world could not build an executable typed input for an invocation
     * that DID reach Execute (so the common executor is never invoked): admission failure, NOT a step
     * that ran and failed. [Ready] carries the prepared legacy execution, ready for the common seam.
     */
    fun prepare(step: StepNode): ExecutionPreparation = try {
        ExecutionPreparation.Ready(PreparedLegacyExecution(CanonicalCoreStepDecoder.decode(step)))
    } catch (e: IllegalArgumentException) {
        ExecutionPreparation.Rejected(e.message ?: "unsupported core step")
    }
}
