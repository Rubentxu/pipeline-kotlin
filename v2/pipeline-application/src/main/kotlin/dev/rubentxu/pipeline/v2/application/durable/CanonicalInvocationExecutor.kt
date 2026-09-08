package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * LEGACY COMPATIBILITY SEAM for the effective invocation of a Step's concrete semantics
 * (B1.2c2-a1 / CDE.3-b5).
 *
 * @Deprecated Architectural authority moved to [CommonExecutionBoundary] (CDE.3-b4). This
 * command-typed seam is retained ONLY as a compatibility detail behind [LegacyExecutionAdapter]
 * while the legacy dispatcher still exists. It must NOT:
 *  - appear in new a1/replay law statements (those observe [CommonExecutionBoundary]);
 *  - be referenced by future registry architecture or new TestKit contracts;
 *  - receive new responsibilities.
 *
 * Retire this seam together with the legacy [CanonicalNodeDispatcher] dispatcher (see the CDE.3-b5
 * task). A call to it means "the concrete Step will actually execute and may produce side effects";
 * the durable protocol decides whether to reach it (fresh/re-run executes; replay reuse, decode
 * rejection and divergence return before it is reached).
 */
@Deprecated(
    "Architectural authority moved to CommonExecutionBoundary; CanonicalInvocationExecutor is legacy compatibility behind LegacyExecutionAdapter.",
    level = DeprecationLevel.WARNING,
)
fun interface CanonicalInvocationExecutor {
    suspend fun invoke(
        command: CanonicalCoreStepCommand,
        context: CanonicalRuntimeContext,
    ): StepOutcome
}
