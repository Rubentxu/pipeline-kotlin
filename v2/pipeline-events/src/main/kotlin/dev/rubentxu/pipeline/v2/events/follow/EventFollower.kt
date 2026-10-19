package dev.rubentxu.pipeline.v2.events.follow

import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import java.time.Duration

/**
 * Public follow contract for the DomainEvent plane.
 * ## What this is and what it is not
 * [EventFollower] composes
 * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort] into
 * a pull-style handle. It does NOT introduce a new event store, a new
 * envelope codec, or a new cursor vocabulary. The existing event-plane
 * types are the source of truth, and the follow contract is additive on
 * top of them.
 * ## Why pull (iterator) and not Flow
 * Same shape as the Output follow (`OutputFollower`): the published
 * contract for `v2/pipeline-events` does not depend on
 * `kotlinx-coroutines`, and adding that dependency is an architectural
 * change. The pull shape — an `Iterator<EventFollowEvent>` over a
 * closeable handle — keeps the published ABI free of coroutines. The
 * application layer can adapt the iterator to a `Flow<EventFollowEvent>`
 * for Kotlin consumers; the adaptation is an internal concern, not a
 * contract surface.
 * ## Why polling is the baseline
 * Same as the Output follow: the wakeup vocabulary (`ObservationWakeup`)
 * is not wired to a real emitter in this repo. The first cut is polling,
 * with the same `FOLLOW_IDLE_MILLIS = 25L` cadence as the Output follow
 * and the existing `pipeline observe` follow loop. A future non-breaking
 * addition can swap the implementation for one that honours
 * `ObservationWakeup`; the public type does not change.
 * ## Cancellation
 * The handle is `AutoCloseable`. The consumer closes it (try-with-resources
 * via `use { }`). After `close`, the iterator returns no further events
 * and the implementation releases any store-side resources.
*/
interface EventFollower {

    /**
     * Open a follow handle. The handle is `AutoCloseable`; the consumer
     * MUST close it when finished.
     * The returned [EventFollowHandle.iterator] yields events in the
     * order the implementation produces them; the first event is always
     * either an [EventFollowEvent.StateChanged] (declaring the initial
     * state) or an [EventFollowEvent.Refused] (declaring why the follow
     * cannot start).
     * Refusal rows in the underlying store are surfaced inside
     * [EventFollowEvent.Page.slice.refusals] (carried by
     * [EventRecordSlice.refusals]); the follow itself does not refuse on
     * a single undecodable row.
     * The handle is single-observer: a second consumer that wants to
     * observe the same run independently calls [open] again. Two
     * iterators over the same handle would be cursor sharing; the
     * contract does not allow that, because cursor sharing across
     * consumers is exactly the kind of "two parallel histories" bug
     * E4c was written to prevent
*/
    fun open(runId: String, options: EventFollowOptions): EventFollowHandle
}

/**
 * A handle to an active follow. The handle is `AutoCloseable`; the
 * consumer MUST close it when finished (a `use { }` block in Kotlin, or
 * a try-with-resources in Java).
 * The handle is single-observer: the iterator obtained from
 * [iterator] yields the events of this follow in a single pass; the
 * handle is one iterator, and one iterator is the entire read surface
 * of the handle. Two consumers that want to observe the same run
 * independently each call [EventFollower.open] and each get their own
 * handle.
 * The handle is NOT thread-safe; the consumer drives the iterator from
 * a single thread. Resumption across a restart is via
 * [EventFollowOptions.after]: a consumer that crashed and restarted
 * opens a new handle with `after = lastCursor` and the implementation
 * begins polling from there.
 * ## Live follow vs snapshot (contract differentiation)
 * The follow is ALWAYS live while the handle is open. The first event
 * the iterator returns is a [EventFollowEvent.StateChanged] declaring
 * the open-boundary state, then [EventFollowEvent.Page]s as the
 * follow observes new events. There is no separate "snapshot mode"
 * flag; the differentiation is a consumer-side pattern:
 * | Consumer intent | Pattern | Terminal event? |
 * | --- | --- | --- |
 * | **Live follow** (default) | keep the handle open and the iterator polling; the implementation re-polls while alive. | [EventFollowEvent.Completed] only at the two terminal conditions: [EventFollowUntil.UntilSequence] satisfied (3c) or a [dev.rubentxu.pipeline.v2.events.RunFinished] observed in the page (3d). |
 * | **Snapshot** (one-shot) | open, drain pages until the first empty `Page` (or any `Page` whose `slice.records` is empty), call [close]. | None emitted. The handle terminates by the consumer's `close` call, not by an in-band `Completed`. |
 * | **Bounded live** | open with [EventFollowUntil.UntilSequence] or [EventFollowUntil.UntilRunFinished]; the follow terminates on the bound. | [EventFollowEvent.Completed] at the bound. |
 * **A page with `hasMore = false` is NOT a terminal condition.** The
 * handle conflated "no new data right now" with "the run is
 * finished" and emitted `Completed` on the first quiet page; that
 * Conflated snapshot with live follow and was the finding from the
 * Audit. The natural tail is now a state the follow re-polls
 * past while the handle is open.
 * THAT DEFECT HAS NO GUARDIAN. This comment used to name the two
 * falsifiers that pinned it — an in-process live-tail test and a
 * cross-JVM one — and both were deleted in `2aa071aab`. Nothing else in
 * the repository replaces them: a follow emits `Completed` on a quiet
 * page, and no E2E scenario observes the event stream, because
 * `examples/run.sh` reads the process exit code and at most a stderr
 * substring. So the state machine below can regress to the exact defect
 * its own KDoc describes and the whole executable suite stays green.
 * This is the most consequential thing recorded in this module, and it is
 * not closed. Writing the guard needs a live tail that stays open across a
 * quiet page, which means a store or a follower double — a decision about
 * what to fake, taken before the fake is written, not a test to add later.
 * ## Refusal and cursor past tail
 * Opening a follow with a cursor past the durable tail of the run
 * surfaces as a single [EventFollowEvent.Refused] carrying
 * [EventFollowRefusal.UnknownRun] (the closed event-follow refusal
 * Hierarchy has no `CursorBeyondTail` case; the adapter translates
 * that condition to the same refusal the open boundary emits for an
 * unknown run, because both mean "the cursor points past known
 * history"). After a `Refused`, the iterator returns no further events
 * and the consumer MUST close the handle.
 * A single undecodable row in a page is surfaced inside the page via
 * [dev.rubentxu.pipeline.v2.events.EventRecordSlice.refusals]; the
 * follow itself does not refuse on a single bad row.
 * ## Cancellation
 * [close] is the only cancellation path. After `close`, the iterator
 * returns `false` from [Iterator.hasNext] promptly (the close path
 * wakes any in-flight poll). There is no `Refused(Cancelled)` event;
 * cancellation is a local lifecycle operation, not a follow outcome.
*/
interface EventFollowHandle : AutoCloseable {

    /**
     * Returns the iterator over the events this follow produces.
     * One iterator per handle. Calling this method more than once
     * returns the same iterator (idempotent), and that iterator is
     * single-pass: once it has emitted [EventFollowEvent.Completed]
     * or [EventFollowEvent.Refused] and the consumer has drained it,
     * subsequent calls to [Iterator.hasNext] return `false`.
     * To resume after a restart, the consumer opens a NEW handle with
     * [EventFollowOptions.after] set to the last cursor it observed
     * (the [EventRecordSlice.nextCursor] of the last
     * [EventFollowEvent.Page] it consumed); it does not re-iterate this
     * handle.
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
     * Resume strictly after this cursor. `null` starts at the
     * beginning of the run. A consumer that crashed mid-poll persists
     * the last [EventRecordSlice.nextCursor] it observed and reopens
     * with `after = lastCursor`; the implementation begins polling
     * from there. The
     * [EventRecordReadPort.readRecords] call is the existing primitive
     * that backs this.
*/
    val after: EventCursor? = null,
    /**
     * Reporting-only threshold. The follow reports the observed lag
     * (current time minus last `occurredAt`) at this interval; it does
     * NOT refuse on its own. A slow consumer is not a defect; the
     * consumer decides whether to abort based on the reported lag.
*/
    val lagReportInterval: Duration = Duration.ofSeconds(1),
) {
    init {
        require(pollIntervalMs > 0) {
            "pollIntervalMs must be positive, got $pollIntervalMs; a follow that skips the wait " +
                "between polls re-polls in a tight loop against a live store connection, and that " +
                "loop runs inside a native call where the interrupt flag is never observed"
        }
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
 * The shape mirrors the existing `OutputRefusal` convention in
 * `v2/pipeline-output/src/main/kotlin/.../output/OutputRefusal.kt`:
 * a closed sealed interface per port, no `Either`/`Result` re-use.
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
 * A single event from [EventFollower.open].iterator. The terminal
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
