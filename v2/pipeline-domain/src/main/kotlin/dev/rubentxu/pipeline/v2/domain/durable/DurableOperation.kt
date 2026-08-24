package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Sealed hierarchy of durable operations.
 *
 * A durable operation represents a step execution that can be replayed, skipped,
 * or aborted based on its [replayPolicy] and the contents of the [OperationJournal].
 *
 * ## Variants
 *
 * - [RerunOperation]      — Always re-executes; useful for non-idempotent steps.
 * - [MemoizedOperation]   — Re-executes only if no journal entry exists or fingerprints diverge.
 * - [CompositeOperation]  — Groups multiple operations that must all succeed or all fail together.
 *
 * @see <a href="design.md §E4-01">Design §E4-01</a>
 */
sealed class DurableOperation {
    /** Unique identifier for this operation instance. */
    abstract val id: String

    /** SHA-256 fingerprint computed from input + stepId + replayPolicy + attempt. */
    abstract val fingerprint: Fingerprint

    /** The input parameters for this operation. */
    abstract val input: OperationInput

    /** The output produced by this operation, if completed. */
    abstract val output: OperationOutput?

    /** Current lifecycle status. */
    abstract val status: OperationStatus

    /** Monotonically increasing attempt number within the run. */
    abstract val attempt: Int

    /**
     * The replay policy this operation was constructed with.
     *
     * M3-R2 will extend this sealed hierarchy with new variants
     * that consume this field.
     */
    abstract val replayPolicy: ReplayPolicy
}

/**
 * A durable operation that always re-executes, regardless of journal state.
 *
 * Use for non-idempotent steps where cached output cannot be safely reused.
 *
 * @param id          Unique operation identifier.
 * @param fingerprint SHA-256 fingerprint of the current input.
 * @param input       Operation input parameters.
 * @param output      Output from the most recent execution, if any.
 * @param status      Current lifecycle status.
 * @param attempt     Current attempt number.
 */
data class RerunOperation(
    override val id: String,
    override val fingerprint: Fingerprint,
    override val input: OperationInput,
    override val output: OperationOutput?,
    override val status: OperationStatus,
    override val attempt: Int,
) : DurableOperation() {
    override val replayPolicy: ReplayPolicy = ReplayPolicy.RERUN
}

/**
 * A durable operation that can be skipped if a matching journal entry exists
 * and the fingerprints match.
 *
 * Use for idempotent steps where cached output is safe to reuse.
 *
 * @param id          Unique operation identifier.
 * @param fingerprint SHA-256 fingerprint of the current input.
 * @param input       Operation input parameters.
 * @param output      Cached output from a prior successful execution, if any.
 * @param status      Current lifecycle status.
 * @param attempt     Current attempt number.
 * @param cachedOutput The cached [OperationOutput] to return if replay is skipped.
 */
data class MemoizedOperation(
    override val id: String,
    override val fingerprint: Fingerprint,
    override val input: OperationInput,
    override val output: OperationOutput?,
    override val status: OperationStatus,
    override val attempt: Int,
    val cachedOutput: OperationOutput?,
) : DurableOperation() {
    override val replayPolicy: ReplayPolicy = ReplayPolicy.MEMOIZED
}

/**
 * A durable operation that groups multiple sub-operations as an atomic unit.
 *
 * All sub-operations must succeed together, or the entire composite fails.
 *
 * @param id          Unique operation identifier.
 * @param fingerprint SHA-256 fingerprint of the composite's canonical input.
 * @param input       Operation input parameters.
 * @param output      Combined output from all sub-operations, if completed.
 * @param status      Current lifecycle status.
 * @param attempt     Current attempt number.
 * @param subOperations The list of child [DurableOperation] instances.
 */
data class CompositeOperation(
    override val id: String,
    override val fingerprint: Fingerprint,
    override val input: OperationInput,
    override val output: OperationOutput?,
    override val status: OperationStatus,
    override val attempt: Int,
    val subOperations: List<DurableOperation>,
) : DurableOperation() {
    override val replayPolicy: ReplayPolicy = ReplayPolicy.MEMOIZED
}
