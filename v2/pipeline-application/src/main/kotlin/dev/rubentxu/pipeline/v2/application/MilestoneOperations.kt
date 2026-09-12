package dev.rubentxu.pipeline.v2.application

/**
 * Typed seam for milestone state operations (S2-A9 / spike).
 *
 * This interface is the *only* contract a Step handler holds for milestone
 * ordinal tracking within a pipeline run. The handler MUST NOT carry mutable
 * state itself; it adapts to this typed seam, which is implemented by the
 * [MilestoneOperationsAdapter] bound to the coordinator/run lifetime.
 *
 * ## Why a typed seam and not a var in the handler?
 *
 * AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE (handler must NOT hold
 * mutable state). `CoreMilestoneStep` is a singleton `object`; its
 * `private var lastReachedOrdinal: Int? = null` is global classloader state.
 * The legacy `CanonicalMilestoneNodeDispatcher` tracks state per-coordinator
 * instance (per run). A singleton handler does not provide equivalent isolation:
 * in multi-run scenarios or test harnesses, the classloader-singleton state
 * bleeds across runs.
 *
 * ## Lifetime
 *
 * [MilestoneStateStore] (and hence [MilestoneOperations]) lives at the
 * coordinator/run level: it is created by the coordinator wiring alongside
 * the dispatcher, not by handler invocation. This mirrors the legacy
 * `CanonicalMilestoneNodeDispatcher.lastReachedOrdinal` scope exactly.
 *
 * ## Capability wiring
 *
 * The adapter is bound as [MILESTONE_OPERATIONS_CAPABILITY] in
 * [dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess].
 * The handler declares this capability in its [dev.rubentxu.pipeline.v2.domain.step.StepContract];
 * capability admission is fail-closed before the handler runs when it is unavailable.
 */
interface MilestoneOperations {

    /**
     * Returns the last reached ordinal, or `null` if no milestone has been reached
     * in this pipeline run.
     */
    fun peek(): Int?

    /**
     * Attempts to advance the milestone state to the given [ordinal].
     *
     * @return [MilestoneAdvanceResult.Reached] if the ordinal is strictly greater
     *         than the previously reached ordinal (the state is updated).
     * @return [MilestoneAdvanceResult.Aborted] if the ordinal is not strictly
     *         greater (the state is unchanged, the advance is recorded).
     */
    fun advance(ordinal: Int): MilestoneAdvanceResult
}

/**
 * Result of attempting to advance the milestone state.
 */
sealed class MilestoneAdvanceResult {
    abstract val previous: Int?

    /** The ordinal was strictly greater than the previously reached ordinal. */
    data class Reached(override val previous: Int?) : MilestoneAdvanceResult()

    /** The ordinal was not strictly greater than the previously reached ordinal. */
    data class Aborted(
        override val previous: Int?,
        val reason: String,
    ) : MilestoneAdvanceResult()
}

/**
 * Thread-safe milestone state store scoped to a single pipeline run.
 *
 * This is the *durable* state carrier: it lives alongside the coordinator
 * (same lifetime = one pipeline run), not inside the handler. The handler
 * sees only the [MilestoneOperations] capability interface.
 *
 * ## Why run-scoped?
 *
 * Milestone monotonicity is defined *per pipeline run*. The legacy
 * `CanonicalMilestoneNodeDispatcher.lastReachedOrdinal` is reset at the start
 * of each run. By creating the store at coordinator construction time and
 * binding it through the capability system, we guarantee:
 *
 * 1. **Isolation per run**: each `CanonicalDurableRunCoordinator` instance gets
 *    its own store, matching the legacy dispatcher's per-run semantics.
 * 2. **No classloader leakage**: unlike a `var` in an `object` singleton,
 *    the store is not shared across classloader boundaries.
 * 3. **Test isolation is explicit**: tests create their own store, no
 *    `resetState()` hack on the handler is needed.
 *
 * ## Thread safety
 *
 * Synchronized on `Any()` to support concurrent milestone invocations within
 * a single run (e.g. parallel stages). The milestone ordinal is pipeline-run
 * global, so concurrent access must be serialized.
 */
class MilestoneStateStore {
    @Volatile
    private var _lastReachedOrdinal: Int? = null

    @Synchronized
    fun peek(): Int? = _lastReachedOrdinal

    @Synchronized
    fun advance(ordinal: Int): MilestoneAdvanceResult {
        val previous = _lastReachedOrdinal
        return if (previous != null && ordinal <= previous) {
            MilestoneAdvanceResult.Aborted(
                previous = previous,
                reason = "ordinal-already-reached (previous=$previous)",
            )
        } else {
            _lastReachedOrdinal = ordinal
            MilestoneAdvanceResult.Reached(previous = previous)
        }
    }
}

/**
 * Runtime adapter binding [MilestoneOperations] to the run-scoped [MilestoneStateStore].
 *
 * The handler never sees the store directly; it sees only this adapter through the
 * capability system. This mirrors the [WorkspaceOperationsAdapter] pattern:
 * the adapter owns the state, the handler reaches only the typed seam.
 *
 * @param store The run-scoped state store (created by coordinator wiring, never by
 *              handler invocation)
 */
class MilestoneOperationsAdapter(
    private val store: MilestoneStateStore,
) : MilestoneOperations {

    override fun peek(): Int? = store.peek()

    override fun advance(ordinal: Int): MilestoneAdvanceResult = store.advance(ordinal)
}
