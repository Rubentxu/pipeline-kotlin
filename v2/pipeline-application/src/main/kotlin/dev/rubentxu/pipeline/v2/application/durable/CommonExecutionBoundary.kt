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

/**
 * Composes the two structural execution-strategy families behind a single [CommonExecutionBoundary]
 * (CDE.3-d2).
 *
 * Selection is by the PreparedExecution strategy kind (legacy-compatible vs registry), NEVER by step
 * key and NEVER over concrete plugin steps, so no concrete handler is special-cased. The [when] is
 * exhaustive over the two sealed structural forms. Each family is backed by its own executor boundary
 * ([legacy] for [PreparedLegacyExecution], [registry] for [PreparedRegistryExecution]).
 */
object SeamedExecutionRouter {
    fun route(
        legacy: CommonExecutionBoundary,
        registry: CommonExecutionBoundary,
    ): CommonExecutionBoundary = CommonExecutionBoundary { prepared, context ->
        when (prepared) {
            is PreparedLegacyExecution -> legacy.execute(prepared, context)
            is PreparedRegistryExecution -> registry.execute(prepared, context)
        }
    }
}

/**
 * Single production authority that builds the default [CommonExecutionBoundary] a coordinator (or a
 * recording test decorator) should route through (B1.2c3-S2.5.2).
 *
 * Without a registry it is the legacy adapter over the legacy executor; with a [StepRegistry] it is the
 * family router ([SeamedExecutionRouter.route]) over the legacy adapter + the registry boundary. Test
 * recorders MUST wrap this authority (observing effective execution and delegating to the real routing),
 * never reimplement family routing themselves.
 *
 * As of S2.5.7 this function is retained for source compatibility as a forwarder over
 * [ExecutionBoundaryFactory.build]; the structural decision lives in [ExecutionBoundaryFactory] / [FamilyRouter].
 * Removal is future cleanup after legacy adapter retirement.
 */
fun buildDefaultExecutionBoundary(
    dispatcher: CanonicalNodeDispatcher,
    invocationExecutor: CanonicalInvocationExecutor?,
    stepRegistry: dev.rubentxu.pipeline.v2.domain.step.StepRegistry?,
): CommonExecutionBoundary = ExecutionBoundaryFactory.build(dispatcher, invocationExecutor, stepRegistry)
