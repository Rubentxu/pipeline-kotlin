package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * Seam for the EFFECTIVE invocation of a Step's concrete semantics (B1.2c2-a1).
 *
 * Deliberately NOT [StepExecutionBoundary] (which also wraps recovery/abort and lifecycle) and NOT
 * the journal/replay machinery. A call to [invoke] means "the concrete Step will actually execute and
 * may produce side effects". The durable protocol decides whether to call it: a fresh/re-run path
 * invokes it; replay reuse, decode failure and divergence return before it is reached; running-shell
 * recovery produces an outcome without re-invoking it.
 *
 * The production default delegates to the legacy [CanonicalNodeDispatcher]. This is a temporary
 * compatibility seam so a recording executor can freeze the durable protocol (B1.2c2-a1) before a
 * generic execution strategy lands; it is NOT the final DI architecture.
 */
fun interface CanonicalInvocationExecutor {
    suspend fun invoke(
        command: CanonicalCoreStepCommand,
        context: CanonicalRuntimeContext,
    ): StepOutcome
}
