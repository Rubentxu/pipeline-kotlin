package dev.rubentxu.pipeline.v2.application.durable

/**
 * The ONE seam for the EFFECTIVE execution of a Step's concrete semantics.
 *
 * A call to [execute] means "the concrete Step will actually run and may produce
 * side effects". The durable protocol decides whether to call it: a fresh or
 * re-run execution calls it; replay reuse, decode rejection, divergence and
 * running-shell recovery all return BEFORE it is reached.
 *
 * The seam carries a [PreparedRegistryExecution] — a step that already passed
 * registry resolution, capability admission and typed decode — and it returns
 * one [CommonExecutionResult], which carries the outcome and the encoded typed
 * output together so the coordinator never needs a second channel to read the
 * typed output back.
 *
 * There is exactly ONE implementation of this seam in the tree
 * ([RegistryExecutionBoundary]) and exactly ONE way to produce its input
 * ([RegistryExecutionPreparation]). The two legacy execution families that used
 * to share it — the command-typed legacy adapter and the seam router that chose
 * between them — are gone, because the legacy world could not produce a
 * successful execution under any input.
 */
fun interface CommonExecutionBoundary {
    suspend fun execute(
        prepared: PreparedRegistryExecution,
        context: CanonicalRuntimeContext,
    ): CommonExecutionResult
}
