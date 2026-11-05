package dev.rubentxu.pipeline.v2.output.follow

import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * Public follow contract for the Output Plane.
 * ## What this is and what it is not
 * [OutputFollower] composes [dev.rubentxu.pipeline.v2.output.OutputReadPort],
 * [dev.rubentxu.pipeline.v2.output.OutputFrameIndex] and
 * [dev.rubentxu.pipeline.v2.output.OutputTailPort] into a pull-style handle
 * that a consumer can drive. It does NOT introduce a new writer, a new
 * store, or a new cursor vocabulary: the existing ports are the source of
 * truth, and the follow contract is additive on top of them.
 * ## Why pull (iterator) and not Flow
 * The published contract for `pipelinek/pipeline-output` does not depend on
 * `kotlinx-coroutines`, and adding that dependency is an architectural
 * change. The pull shape — an `Iterator<OutputFollowEvent>` over a
 * closeable handle — keeps the published ABI free of coroutines, lets a
 * Java consumer drive the follow without a Kotlin runtime, and matches
 * the existing `OutputReadPort.read` / `OutputFrameIndex.framesOfRun`
 * request/response shape. The application layer
 * (`pipelinek/pipeline-application/.../observation/follow/`) can adapt the
 * iterator to a `Flow<OutputFollowEvent>` for Kotlin consumers; the
 * adaptation is an internal concern, not a contract surface.
 * ## Why polling is the baseline
 * The first cut is polling. The wakeup vocabulary (`ObservationWakeup`)
 * is not wired to a real emitter in this repo (`MainObserveCli.kt:255-258`
 * Cites "There is no `EventsCommitted` emitter yet (bis)")
 * Polling at the existing `FOLLOW_IDLE_MILLIS = 25L` cadence matches the
 * in-tree `pipeline observe` behaviour and avoids inventing a new
 * transport. A future non-breaking addition can swap the implementation
 * for one that honours `ObservationWakeup`; the public type does not
 * change.
 * ## Cancellation
 * The handle is `AutoCloseable`. The consumer closes it (try-with-resources
 * via `use { }`). After `close`, `iterator().hasNext()` returns `false`
 * and the implementation releases any store-side resources. The contract
 * guarantees that no half-page is delivered after a `close`.
*/
interface OutputFollower {

    /**
     * Open a follow handle. The handle is `AutoCloseable`; the consumer
     * MUST close it when finished.
     * The returned [OutputFollowHandle.iterator] yields events in the
     * order the implementation produces them; the first event is always
     * either an [OutputFollowEvent.StateChanged] (declaring the initial
     * tail state) or an [OutputFollowEvent.Refused] (declaring why the
     * follow cannot start).
     * Backpressure is bound by [OutputFollowOptions.pageMaxBytes] per
     * page and [OutputFollowOptions.maxRecords] per poll cycle, mirroring
     * the existing `LiveOutputDrain` and `ObservationOutputFollower` in
     * `pipelinek/pipeline-application/.../observation/`.
     * The handle is single-observer: a second consumer that wants to
     * observe the same run independently calls [open] again. Two
     * iterators over the same handle would be a definition of cursor
     * sharing; the contract does not allow that, because cursor sharing
     * across consumers is exactly the kind of "two parallel histories"
     * Bug E4c was written to prevent
*/
    fun open(runId: String, options: OutputFollowOptions): OutputFollowHandle
}

/**
 * A handle to an active follow. The handle is `AutoCloseable`; the
 * consumer MUST close it when finished (a `use { }` block in Kotlin, or
 * a try-with-resources in Java).
 * The handle is single-observer: the iterator obtained from
 * [iterator] yields the events of this follow in a single pass; the
 * handle is one iterator, and one iterator is the entire read surface
 * of the handle. Two consumers that want to observe the same run
 * independently each call [OutputFollower.open] and each get their
 * own handle.
 * The handle is NOT thread-safe; the consumer drives the iterator from
 * a single thread. Resumption across a restart is via
 * [OutputFollowOptions.afterOrdinal]: a consumer that crashed and
 * restarted opens a new handle with `afterOrdinal = lastObserved` and
 * the implementation begins polling from there.
*/
interface OutputFollowHandle : AutoCloseable {

    /**
     * Returns the iterator over the events this follow produces.
     * One iterator per handle. Calling this method more than once
     * returns the same iterator (idempotent), and that iterator is
     * single-pass: once it has emitted [OutputFollowEvent.Completed]
     * or [OutputFollowEvent.Refused] and the consumer has drained it,
     * subsequent calls to [Iterator.hasNext] return `false`.
     * To resume after a restart, the consumer opens a NEW handle with
     * [OutputFollowOptions.afterOrdinal] set to the last ordinal it
     * observed; it does not re-iterate this handle.
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
 * (`pageMaxBytes`), 25 ms poll interval
 * (`FOLLOW_IDLE_MILLIS`), 256 records per poll (`ObservationReplayLimit.DEFAULT`).
*/
data class OutputFollowOptions(
    /**
     * Which streams to follow. If empty, the follow will start by asking
     * [dev.rubentxu.pipeline.v2.output.OutputFrameIndex.streamsOfRun] and
     * follow every declared stream. The empty default exists so a Fabric
     * consumer can subscribe by run alone without naming the channels
     * up front. What a consumer outside this process may follow, and what
     * it may not do, is the contract the root README states under "Reading a
     * run from outside".
*/
    val streams: List<OutputStreamAddress> = emptyList(),
    val pageMaxBytes: Int = 64 * 1024,
    val pollIntervalMs: Long = 25L,
    val includeFrames: Boolean = true,
    val maxRecords: Int = 256,
    val until: FollowUntil = FollowUntil.Unbounded,
    /**
     * Resume strictly after this frame ordinal. `null` starts at the
     * beginning of the declared streams. A consumer that crashed
     * mid-poll persists the last ordinal it observed and reopens with
     * `afterOrdinal = lastObserved`; the implementation begins polling
     * from there. The `OutputFrameIndex.framesOfRun(runId, afterOrdinal,
     * limit)` call is the existing primitive that backs this.
*/
    val afterOrdinal: Long? = null,
) {
    init {
        require(pageMaxBytes > 0) { "pageMaxBytes must be positive, got $pageMaxBytes" }
        require(pollIntervalMs > 0) {
            "pollIntervalMs must be positive, got $pollIntervalMs; a follow that skips the wait " +
                "between polls re-polls in a tight loop against a live store connection, and that " +
                "loop runs inside a native call where the interrupt flag is never observed"
        }
        require(maxRecords > 0) { "maxRecords must be positive, got $maxRecords" }
        require(afterOrdinal == null || afterOrdinal >= 0) {
            "afterOrdinal must be non-negative, got $afterOrdinal"
        }
    }
}

/**
 * A terminal condition for [OutputFollower.open]. The default is
 * [FollowUntil.Unbounded]: the follow runs until the consumer closes the
 * handle, because the output plane does not learn that the run is over.
*/
sealed interface FollowUntil {

    /**
     * The follow ends when the consumer closes the handle, or when the run
     * declares no stream at all and the follow reports itself unobservable.
     *
     * The output plane never learns that the run is over, so the run's terminal
     * state does not end the follow. It is the default because a consumer that
     * has not said where to stop is watching for as long as it cares to.
     */
    data object Unbounded : FollowUntil

    /**
     * The follow ends when every declared stream of the followed run has been
     * sealed.
     *
     * A sealed stream admits no more bytes, so the end of the follow is decided by the output
     * plane alone and not by the run's outcome. The [runId] this condition carries does not
     * select the run: the follow's own run id already fixes it.
     */
    data class UntilAllSealed(val runId: String) : FollowUntil

    /**
     * The follow ends once the pages it has delivered sum to [limit] bytes.
     *
     * The bound counts committed bytes handed to the consumer, and it is checked after a page
     * is delivered, so a limit of zero ends the follow once the first page has been handed
     * over rather than before any byte arrives.
     */
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
 * The three values are deliberately distinct: the Output Plane
 * alone does NOT authoritatively know the run is over (cf.
 * [dev.rubentxu.pipeline.v2.output.OutputTailState] KDoc: "Putting
 * an outcome here would make the Output Plane a second authority
 * over execution results"). No implementation emits
 * [FollowState.RunTerminal]: the follower stops at
 * [FollowState.StreamSealed], and only an adapter that joins the
 * event plane could say that the run is over.
*/
sealed interface FollowState {

    /**
     * Not every declared stream is sealed yet.
     *
     * The state is about the streams and not about the run: the output plane does not learn
     * that the run is over, so it does not say that the run is still executing either. The two
     * lists tell the consumer which streams may still produce bytes and which are done, so a
     * consumer that watches several streams at once knows where the remaining output will
     * come from. A declared stream the tail port does not know about yet is in neither list.
     */
    data class Running(
        val openStreams: List<OutputStreamId>,
        val sealedStreams: List<OutputStreamId>,
    ) : FollowState

    /**
     * Every stream the tail port reports is sealed, but the run is not yet observed as terminal.
     *
     * The distinction matters because the two facts have different owners: the output plane sees
     * the streams close, and only the event plane may say the run is over.
     */
    data class StreamSealed(val sealedStreams: List<OutputStreamId>) : FollowState

    /**
     * The run reached a terminal state and no further output can arrive.
     *
     * No implementation produces this state: the output plane does not learn
     * that the run is over, so the state is reserved for an adapter that joins
     * the event plane.
     */
    data object RunTerminal : FollowState

    /**
     * The run's output cannot be observed, and [refusal] names why.
     *
     * The follow reports the state once and then ends: the event after it is the completion
     * of the follow, and a consumer that wants to try again opens a new follow.
     */
    data class Unobservable(val refusal: OutputRefusal) : FollowState
}

/**
 * A single event from [OutputFollower.open].iterator. The terminal
 * event is either [Completed] (the follow reached its [FollowUntil]
 * condition) or [Refused] (the follow could not continue for the
 * reason named in the event).
*/
sealed interface OutputFollowEvent {

    /**
     * A page of committed bytes was delivered, and [newState] is the follow's state after it.
     *
     * The state travels with the bytes so a consumer that reads pages never has to poll for the
     * state separately.
     */
    data class Bytes(val page: OutputPage, val newState: FollowState) : OutputFollowEvent

    /**
     * The follow's state changed and no bytes accompanied the change.
     *
     * The first event of a follow is one of these, unless the follow refuses at
     * the open boundary: it carries the initial state, so a consumer starts from
     * a declared position rather than from an assumption.
     */
    data class StateChanged(val state: FollowState) : OutputFollowEvent

    /**
     * The follow stopped because it could not continue, and [refusal] names the closed cause.
     *
     * It is terminal: the consumer closes the handle and decides whether to re-anchor or escalate.
     */
    data class Refused(val refusal: OutputRefusal) : OutputFollowEvent

    /**
     * The follow reached the condition it was opened with, or found that the
     * run declares no stream to observe.
     *
     * It is the last event of a follow: the iterator is exhausted after it.
     */
    data object Completed : OutputFollowEvent
}
