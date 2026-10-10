package dev.rubentxu.pipeline.v2.events.follow

import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import java.time.Duration

/**
 * P1 — public follow contract for the DomainEvent plane.
 *
 * ## What this is and what it is not
 *
 * [EventFollower] composes
 * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort] (M1-A) into
 * a pull-style handle. It does NOT introduce a new event store, a new
 * envelope codec, or a new cursor vocabulary. The existing event-plane
 * types are the source of truth, and the follow contract is additive on
 * top of them.
 *
 * ## Why pull (iterator) and not Flow
 *
 * Same shape as the Output follow (`OutputFollower`): the published
 * contract for `v2/pipeline-events` does not depend on
 * `kotlinx-coroutines`, and adding that dependency is an architectural
 * change. The pull shape — an `Iterator<EventFollowEvent>` over a
 * closeable handle — keeps the published ABI free of coroutines. The
 * application layer can adapt the iterator to a `Flow<EventFollowEvent>`
 * for Kotlin consumers; the adaptation is an internal concern, not a
 * contract surface.
 *
 * ## Why polling is the baseline
 *
 * Same as the Output follow: the wakeup vocabulary (`ObservationWakeup`)
 * is not wired to a real emitter in this repo. The first cut is polling,
 * with the same `FOLLOW_IDLE_MILLIS = 25L` cadence as the Output follow
 * and the existing `pipeline observe` follow loop. A future non-breaking
 * addition can swap the implementation for one that honours
 * `ObservationWakeup`; the public type does not change.
 *
 * ## Cancellation
 *
 * The handle is `AutoCloseable`. The consumer closes it (try-with-resources
 * via `use { }`). After `close`, the iterator returns no further events
 * and the implementation releases any store-side resources.
 *
 * @see M1_FOLLOW_DESIGN.md §2.2 for the full type surface and §4 for the
 *   composition contract.
 */
interface EventFollower {

    /**
     * Open a follow handle. The handle is `AutoCloseable`; the consumer
     * MUST close it when finished.
     *
     * The returned [EventFollowHandle.iterator] yields events in the
     * order the implementation produces them; the first event is always
     * either an [EventFollowEvent.StateChanged] (declaring the initial
     * state) or an [EventFollowEvent.Refused] (declaring why the follow
     * cannot start).
     *
     * Refusal rows in the underlying store are surfaced inside
     * [EventFollowEvent.Page.slice.refusals] (carried by
     * [EventRecordSlice.refusals]); the follow itself does not refuse on
     * a single undecodable row.
     */
    fun open(runId: String, options: EventFollowOptions): EventFollowHandle
}

/**
 * A handle to an active follow. The handle is `AutoCloseable`; the
 * consumer MUST close it when finished.
 *
 * The handle is NOT thread-safe; the consumer drives the iterator from a
 * single thread. Multiple independent follows for the same run use
 * multiple handles (each handle has its own cursor).
 */
interface EventFollowHandle : AutoCloseable {

    /**
     * Returns a fresh iterator over the events the follow produces.
     * Calling this method more than once returns independent iterators
     * that share the underlying store cursor; in practice the consumer
     * calls it exactly once and drains the iterator.
     */
    fun iterator(): Iterator<EventFollowEvent>

    /**
     * Closes the handle. After `close`, the iterator returns no further
     * events and any underlying store-side resources are released. Idempotent.
     */
    override fun close()
}

/**
 * Knobs for [EventFollower.open]. The defaults match the existing
 * in-tree `pipeline observe` follow loop.
 */
data class EventFollowOptions(
    val query: EventQuery = EventQuery.All,
    val pollIntervalMs: Long = 25L,
    val maxRecords: Int = 256,
    val until: EventFollowUntil = EventFollowUntil.Unbounded,
    /**
     * Reporting-only threshold. The follow reports the observed lag
     * (current time minus last `occurredAt`) at this interval; it does
     * NOT refuse on its own. A slow consumer is not a defect; the
     * consumer decides whether to abort based on the reported lag.
     */
    val lagReportInterval: Duration = Duration.ofSeconds(1),
) {
    init {
        require(pollIntervalMs >= 0) { "pollIntervalMs must be non-negative, got $pollIntervalMs" }
        require(maxRecords > 0) { "maxRecords must be positive, got $maxRecords" }
        require(!lagReportInterval.isNegative) { "lagReportInterval must be non-negative, got $lagReportInterval" }
    }
}

/**
 * A terminal condition for [EventFollower.open]. The default is
 * [EventFollowUntil.Unbounded].
 */
sealed interface EventFollowUntil {
    data object Unbounded : EventFollowUntil
    data class UntilRunFinished(val runId: String) : EventFollowUntil
    data class UntilSequence(val lastSequence: Long) : EventFollowUntil {
        init {
            require(lastSequence >= 0) { "lastSequence must be non-negative, got $lastSequence" }
        }
    }
}

/**
 * The state of an event follow at a given poll.
 *
 * Terminal detection follows the existing `MainObserveCli.followEvents`
 * rule: a `DomainEvent.kind == "RunFinished"` observation transitions
 * the follow from [Live] to [RunFinished]. There is no separate "lost
 * retention" terminal; that is signalled inside
 * [EventFollowState.Unobservable.refusal] as
 * [EventFollowRefusal.RetentionLost].
 */
sealed interface EventFollowState {
    data class Live(val lastSequence: Long) : EventFollowState

    /**
     * The run is terminal. The final sequence is the
     * [EventRecordSlice.nextCursor] of the last [EventFollowEvent.Page]
     * observed before this state change; the contract does not
     * denormalise it into a separate field on the state value.
     */
    data object RunFinished : EventFollowState

    data class Unobservable(val refusal: EventFollowRefusal) : EventFollowState
}

/**
 * Follow-specific refusals for the event plane. The closed hierarchy
 * here is on purpose: row-level refusals (`EventRecordRead.Undecodable`)
 * are surfaced inside the page via [EventRecordSlice.refusals] and do
 * NOT terminate the follow.
 *
 * The shape mirrors the existing `OutputRefusal` convention in
 * `v2/pipeline-output/src/main/kotlin/.../output/OutputRefusal.kt`:
 * a closed sealed interface per port, no `Either`/`Result` re-use.
 *
 * Note: there is no `LagExceeded` case. A slow consumer is not a
 * defect; the consumer observes lag via the configured reporting
 * interval and decides whether to abort.
 */
sealed interface EventFollowRefusal {
    data class UnknownRun(val runId: String) : EventFollowRefusal
    data class RetentionLost(val runId: String, val lastSeenSequence: Long) : EventFollowRefusal
    data object Cancelled : EventFollowRefusal
}

/**
 * A single event from [EventFollower.open].iterator(). The terminal
 * event is either [Completed] (the follow reached its [EventFollowUntil]
 * condition) or [Refused] (the follow could not continue for the reason
 * named in the event).
 */
sealed interface EventFollowEvent {
    data class Page(val slice: EventRecordSlice, val newState: EventFollowState) : EventFollowEvent
    data class StateChanged(val state: EventFollowState) : EventFollowEvent
    data class Refused(val refusal: EventFollowRefusal) : EventFollowEvent
    data object Completed : EventFollowEvent
}
