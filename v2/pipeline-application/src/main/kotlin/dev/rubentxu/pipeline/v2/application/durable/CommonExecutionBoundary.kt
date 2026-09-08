package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * New authority seam for the EFFECTIVE execution of a Step's concrete semantics (CDE.3-b2).
 *
 * This is the seam the durable protocol observes for the a1 law going forward: a call to [execute]
 * means "the concrete Step will actually run and may produce side effects". It supersedes
 * [CanonicalInvocationExecutor] as the architectural authority. During the migration the old
 * command-typed seam lives behind [LegacyExecutionAdapter] as a compatibility detail; after the
 * legacy dispatcher disappears, only this seam remains.
 *
 * The durable protocol decides whether to call it (fresh/re-run executes; replay reuse, decode
 * rejection, divergence and running-shell recovery return before it is reached).
 */
fun interface CommonExecutionBoundary {
    suspend fun execute(prepared: PreparedExecution, context: CanonicalRuntimeContext): StepOutcome
}

/**
 * Legacy strategy payload: wraps the already-decoded command opaquely. Only [LegacyExecutionAdapter]
 * reads [command]; the durable coordinator never sees it. Prepared here, never executed during
 * preparation.
 */
data class PreparedLegacyExecution(val command: CanonicalCoreStepCommand) : PreparedExecution

/**
 * Adapts the old command-typed [CanonicalInvocationExecutor] (legacy compatibility seam) behind the
 * new [CommonExecutionBoundary]. Temporary migration glue, behavior-preserving: the coordinator may
 * keep constructing the old executor (including a recording one) while the new boundary routes an
 * already-prepared legacy execution to it unchanged.
 */
object LegacyExecutionAdapter {
    fun adapt(legacy: CanonicalInvocationExecutor): CommonExecutionBoundary =
        CommonExecutionBoundary { prepared, context ->
            val legacyPrepared = prepared as? PreparedLegacyExecution
                ?: throw EngineInvariantViolation(
                    "LegacyExecutionAdapter received a non-legacy PreparedExecution; it cannot route it",
                )
            legacy.invoke(legacyPrepared.command, context)
        }
}
