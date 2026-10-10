package dev.rubentxu.pipeline.v2.output.follow

import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * P1 — public follow contract for the Output Plane.
 *
 * ## What this is and what it is not
 *
 * [OutputFollower] composes [dev.rubentxu.pipeline.v2.output.OutputReadPort],
 * [dev.rubentxu.pipeline.v2.output.OutputFrameIndex] and
 * [dev.rubentxu.pipeline.v2.output.OutputTailPort] into a pull-style handle
 * that a consumer can drive. It does NOT introduce a new writer, a new
 * store, or a new cursor vocabulary: the existing ports are the source of
 * truth, and the follow contract is additive on top of them. The
 * composition rule lives in `M1_FOLLOW_DESIGN.md` §4 in
 * `docs/pipelinek-coordinated-evolution/m1-design/`.
 *
 * ## Why pull (iterator) and not Flow
 *
 * The published contract for `v2/pipeline-output` does not depend on
 * `kotlinx-coroutines`, and adding that dependency is an architectural
 * change. The pull shape — an `Iterator<OutputFollowEvent>` over a
 * closeable handle — keeps the published ABI free of coroutines, lets a
 * Java consumer drive the follow without a Kotlin runtime, and matches
 * the existing `OutputReadPort.read` / `OutputFrameIndex.framesOfRun`
 * request/response shape. The application layer
 * (`v2/pipeline-application/.../observation/follow/`) can adapt the
 * iterator to a `Flow<OutputFollowEvent>` for Kotlin consumers; the
 * adaptation is an internal concern, not a contract surface.
 *
 * ## Why polling is the baseline
 *
 * The first cut is polling. The wakeup vocabulary (`ObservationWakeup`)
 * is not wired to a real emitter in this repo (`MainObserveCli.kt:255-258`
 * cites "There is no `EventsCommitted` emitter yet (OBS-D bis)").
 * Polling at the existing `FOLLOW_IDLE_MILLIS = 25L` cadence matches the
 * in-tree `pipeline observe` behaviour and avoids inventing a new
 * transport. A future non-breaking addition can swap the implementation
 * for one that honours `ObservationWakeup`; the public type does not
 * change.
 *
 * ## Cancellation
 *
 * The handle is `AutoCloseable`. The consumer closes it (try-with-resources
 * via `use { }`). After `close`, `iterator().hasNext()` returns `false`
 * and the implementation releases any store-side resources. The contract
 * guarantees that no half-page is delivered after a `close`.
 *
 * @see M1_FOLLOW_DESIGN.md §2.1 for the full type surface and §4 for the
 *   composition contract.
 */
interface OutputFollower {

    /**
     * Open a follow handle. The handle is `AutoCloseable`; the consumer
     * MUST close it when finished.
     *
     * The returned [OutputFollowHandle.iterator] yields events in the
     * order the implementation produces them; the first event is always
     * either an [OutputFollowEvent.StateChanged] (declaring the initial
     * tail state) or an [OutputFollowEvent.Refused] (declaring why the
     * follow cannot start).
     *
     * Backpressure is bound by [OutputFollowOptions.pageMaxBytes] per
     * page and [OutputFollowOptions.maxRecords] per poll cycle, mirroring
     * the existing `LiveOutputDrain` and `ObservationOutputFollower` in
     * `v2/pipeline-application/.../observation/`.
     */
    fun open(runId: String, options: OutputFollowOptions): OutputFollowHandle
}

/**
 * A handle to an active follow. The handle is `AutoCloseable`; the
 * consumer MUST close it when finished (a `use { }` block in Kotlin, or
 * a try-with-resources in Java).
 *
 * The handle is NOT thread-safe; the consumer drives the iterator from a
 * single thread. Multiple independent follows for the same run use
 * multiple handles (each handle has its own cursor).
 */
interface OutputFollowHandle : AutoCloseable {

    /**
     * Returns a fresh iterator over the events the follow produces.
     * Calling this method more than once returns independent iterators
     * that share the underlying store cursor; in practice the consumer
     * calls it exactly once and drains the iterator.
     */
    fun iterator(): Iterator<OutputFollowEvent>

    /**
     * Closes the handle. After `close`, the iterator returns no further
     * events and any underlying store-side resources are released. Idempotent.
     */
    override fun close()
}

/**
 * Knobs for [OutputFollower.open]. The defaults match the existing
 * in-tree `pipeline observe` follow loop: 64 KiB pages
 * (`OutputStreamHandle.DEFAULT_APPEND_WINDOW`), 25 ms poll interval
 * (`FOLLOW_IDLE_MILLIS`), 256 records per poll (`ObservationReplayLimit.DEFAULT`).
 */
data class OutputFollowOptions(
    /**
     * Which streams to follow. If empty, the follow will start by asking
     * [dev.rubentxu.pipeline.v2.output.OutputFrameIndex.streamsOfRun] and
     * follow every declared stream. The empty default exists so a Fabric
     * consumer can subscribe by run alone without naming the channels
     * up front.
     */
    val streams: List<OutputStreamAddress> = emptyList(),
    val pageMaxBytes: Int = 64 * 1024,
    val pollIntervalMs: Long = 25L,
    val includeFrames: Boolean = true,
    val maxRecords: Int = 256,
    val until: FollowUntil = FollowUntil.Unbounded,
) {
    init {
        require(pageMaxBytes > 0) { "pageMaxBytes must be positive, got $pageMaxBytes" }
        require(pollIntervalMs >= 0) { "pollIntervalMs must be non-negative, got $pollIntervalMs" }
        require(maxRecords > 0) { "maxRecords must be positive, got $maxRecords" }
    }
}

/**
 * A terminal condition for [OutputFollower.open]. The default is
 * [FollowUntil.Unbounded] (the follow runs until the run reaches
 * terminal state across all declared streams, or until the consumer
 * closes the handle).
 */
sealed interface FollowUntil {
    data object Unbounded : FollowUntil
    data class UntilAllSealed(val runId: String) : FollowUntil
    data class UntilBytesRead(val limit: Long) : FollowUntil {
        init {
            require(limit >= 0) { "limit must be non-negative, got $limit" }
        }
    }
}

/**
 * The state of a follow at a given poll. Lifted above
 * [dev.rubentxu.pipeline.v2.output.OutputTailState] so the consumer
 * can distinguish "the run is still running" ([FollowState.Running])
 * from "all declared streams are sealed but the run is not yet
 * observed as terminal" ([FollowState.StreamSealed]) from "the run
 * is terminal" ([FollowState.RunTerminal]) without re-joining the
 * event plane.
 *
 * The three values are deliberately distinct: the Output Plane
 * alone does NOT authoritatively know the run is over (cf.
 * [dev.rubentxu.pipeline.v2.output.OutputTailState] KDoc: "Putting
 * an outcome here would make the Output Plane a second authority
 * over execution results"). [FollowState.RunTerminal] is emitted
 * only after the event plane observes a `RunFinished` event; the
 * join happens in the application-layer adapter.
 */
sealed interface FollowState {
    data class Running(
        val openStreams: List<OutputStreamId>,
        val sealedStreams: List<OutputStreamId>,
    ) : FollowState

    data class StreamSealed(val sealedStreams: List<OutputStreamId>) : FollowState

    data object RunTerminal : FollowState

    data class Unobservable(val refusal: OutputRefusal) : FollowState
}

/**
 * A single event from [OutputFollower.open].iterator(). The terminal
 * event is either [Completed] (the follow reached its [FollowUntil]
 * condition) or [Refused] (the follow could not continue for the
 * reason named in the event).
 */
sealed interface OutputFollowEvent {
    data class Bytes(val page: OutputPage, val newState: FollowState) : OutputFollowEvent
    data class StateChanged(val state: FollowState) : OutputFollowEvent
    data class Refused(val refusal: OutputRefusal) : OutputFollowEvent
    data object Completed : OutputFollowEvent
}
