package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepDecoder
import dev.rubentxu.pipeline.v2.domain.StepNode

/**
 * Result of legacy decode/admission behind the execution boundary (CDE.2-d).
 *
 * Distinct phases, never conflated:
 *  - [Ready] carries an executable legacy command (opaque to the durable protocol).
 *  - [Rejected] means the legacy world could not build an executable typed input for an invocation
 *    that DID reach Execute (so the effective executor is never invoked). It is admission failure,
 *    NOT a step that ran and failed.
 */
sealed interface LegacyExecution {
    data class Ready(val command: CanonicalCoreStepCommand) : LegacyExecution
    data class Rejected(val reason: String) : LegacyExecution
}

/**
 * Legacy compatibility boundary to the closed core typed world (CDE.2-d).
 *
 * Owns the concrete legacy decode ([CanonicalCoreStepDecoder]) and the decoded
 * [CanonicalCoreStepCommand] representation. The durable protocol calls [decode] and receives only a
 * closed [LegacyExecution]; it never names the decoded command world directly. Effective side-effect
 * execution still flows through the single [CanonicalInvocationExecutor] seam (a1 law), which this
 * boundary merely admits a command for. This is the seam a registry strategy will later occupy
 * without touching journal/replay/fingerprint/cursor/lifecycle.
 */
object LegacyExecutionBoundary {
    fun decode(step: StepNode): LegacyExecution = try {
        LegacyExecution.Ready(CanonicalCoreStepDecoder.decode(step))
    } catch (e: IllegalArgumentException) {
        LegacyExecution.Rejected(e.message ?: "unsupported core step")
    }
}
