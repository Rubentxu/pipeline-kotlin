package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent
import dev.rubentxu.pipeline.v2.events.follow.EventFollowHandle
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.follow.EventFollowRefusal
import dev.rubentxu.pipeline.v2.events.follow.EventFollowState
import dev.rubentxu.pipeline.v2.events.follow.EventFollowUntil
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * M1-C — production [dev.rubentxu.pipeline.v2.events.follow.EventFollowHandle]
 * driven by a polling loop on the consumer's thread, backed by the
 * M1-A [EventRecordReadPort].
 *
 * ## What this is and what it is not
 *
 * The handle is the live state of one
 * [dev.rubentxu.pipeline.v2.events.follow.EventFollower.open] call. It
 * holds:
 *  - the run being followed ([runId]),
 *  - the read-side collaborator ([port], the M1-A read port),
 *  - the **[runExists] callback** used to distinguish a run that does
 *    not exist from a run that exists but has not yet produced any
 *    event. The M1-A adapter owns this distinction at the page
 *    boundary; the follow honours it at the open boundary so an empty
 *    first page is unambiguous.
 *  - the **[EventCursor]** cursor, which advances through
 *    [EventRecordReadPort.readRecords] pages using
 *    [EventRecordSlice.nextCursor].
 *
 * ## Polling, not push
 *
 * The wakeup vocabulary (`ObservationWakeup`) is not wired to a real
 * emitter in this repository, so the first cut polls. The handle
 * sleeps [EventFollowOptions.pollIntervalMs] between cycles on the
 * consumer's thread. A future non-breaking addition can swap the
 * implementation for one that honours `ObservationWakeup` without
 * changing the public type.
 *
 * ## Terminal detection
 *
 * Terminal detection follows the design's
 * `M1_FOLLOW_DESIGN.md` §4 rule: a `DomainEvent.kind == "RunFinished"`
 * observation transitions the follow from [EventFollowState.Live] to
 * [EventFollowState.RunFinished] and then [EventFollowEvent.Completed].
 * The Event Plane is the source of truth for run terminality (the
 * Output Plane learns of it by joining this plane).
 *
 * The follow also reaches [EventFollowEvent.Completed] once the
 * consumer's [EventFollowOptions.until] condition is met
 * ([EventFollowUntil.UntilRunFinished] / [EventFollowUntil.UntilSequence])
 * or once the page has been drained to the current tail with no
 * further events visible (the natural-end semantic the
 * `M1_FOLLOW_DESIGN.md` §4 composition rule names for an empty
 * history).
 *
 * `EventFollowUntil.UntilSequence(lastSequence)` is honoured by
 * truncating the page the read port returned so the follow delivers
 * only rows with `sequence <= lastSequence` and then emits
 * [EventFollowEvent.Completed]. The truncation is the consumer's
 * explicit bound and takes priority over [RunFinished] observation:
 * a [RunFinished] row beyond `lastSequence` is NOT emitted and the
 * follow does not transition to [EventFollowState.RunFinished].
 *
 * ## Single-observer, idempotent close
 *
 * The handle is single-observer: calling [iterator] returns the SAME
 * [FollowIterator] every time (the design rule "One iterator per
 * handle. Calling this method on a handle returns the same iterator
 * (idempotent)"). The iterator is single-pass: once
 * [EventFollowEvent.Completed] or [EventFollowEvent.Refused] is
 * emitted, subsequent [FollowIterator.hasNext] calls return `false`.
 * [close] is idempotent and interrupts a mid-poll consumer: after
 * `close`, the next [FollowIterator.hasNext] returns `false` and no
 * half-page is delivered.
 *
 * ## Refusal translation
 *
 * The follow has only THREE [EventFollowRefusal] cases by design
 * ([EventFollowRefusal.UnknownRun], [EventFollowRefusal.RetentionLost],
 * [EventFollowRefusal.Cancelled]). The M1-A read port exposes a
 * richer set:
 *  - [EventRecordReadRefusal.UnknownRun] →
 *    [EventFollowRefusal.UnknownRun] (same identity, the run has
 *    never been written to);
 *  - [EventRecordReadRefusal.CursorBeyondTail] →
 *    [EventFollowRefusal.UnknownRun] (the cursor points past known
 *    history; from the follow's perspective, the run has nothing
 *    to give for the requested cursor);
 *  - [EventRecordReadRefusal.StorageError] →
 *    [EventFollowRefusal.UnknownRun] (defensive; there is no
 *    `StorageError` case in the closed event-follow refusal
 *    hierarchy, and a transient storage fault at the follow
 *    boundary is surfaced as a refusal that the consumer can
 *    decide to retry by reopening the handle).
 */
internal class SqliteEventFollowHandle(
    private val port: EventRecordReadPort,
    private val runExists: (String) -> Boolean,
    private val runId: String,
    private val options: EventFollowOptions,
) : EventFollowHandle {

    private val lock = ReentrantLock()

    /**
     * Closed flag. Reads happen from the consumer's `hasNext()` thread AND
     * from a thread that calls [close]; the lock serialises both so the
     * closed state is observed atomically with the [idleCondition]
     * `signalAll` that wakes the sleeping consumer.
     */
    private var closed = false

    /** Signalled by [close] so an [idle] consumer unblocks within `pollIntervalMs`. */
    private val idleCondition: Condition = lock.newCondition()

    /** The cursor the next [EventRecordReadPort.readRecords] will be called with. */
    private var cursor: EventCursor? = options.after

    /**
     * The highest sequence the follow has emitted so far. Seeds the
     * initial [EventFollowState.Live] value and is carried on every
     * subsequent [EventFollowEvent.Page] / [EventFollowEvent.StateChanged]
     * so the consumer can see how far the follow has progressed.
     */
    private var lastEmittedSequence: Long = options.after?.lastSequence ?: 0L

    /** The initial state change has been emitted, so subsequent polls do not re-announce it. */
    private var initialEmitted = false

    /** One-shot flag for the [EventFollowState.RunFinished] + [EventFollowEvent.Completed] end of a sealed run. */
    private var runFinishedEmitted = false

    /** One-shot flag for the [EventFollowEvent.Completed] end of the natural-tail / until-condition path. */
    private var completedEmitted = false

    /** The follow reached its end; the iterator returns `false` from [FollowIterator.hasNext]. */
    private var terminated = false

    /**
     * One or more events produced by the current poll cycle, drained
     * one-per-[FollowIterator.next].
     */
    private val pending: ArrayDeque<EventFollowEvent> = ArrayDeque()

    /**
     * The single iterator the handle exposes. Created on the first
     * call to [iterator] and cached, so subsequent calls return the
     * SAME instance — the design rule "One iterator per handle.
     * Calling this method returns the same iterator (idempotent)".
     */
    private var iteratorRef: FollowIterator? = null

    override fun iterator(): Iterator<EventFollowEvent> {
        val existing = iteratorRef
        if (existing != null) return existing
        val created = FollowIterator()
        iteratorRef = created
        return created
    }

    /**
     * Idempotent. The contract guarantees that no half-page is
     * delivered after this returns: any event buffered by an
     * in-flight [FollowIterator.hasNext] is dropped, and the next
     * call to [FollowIterator.hasNext] returns `false`. The store-side
     * collaborators (the read port, the run-existence callback) are
     * owned by the [SqliteEventFollower] factory, not by this handle,
     * so the handle has no resources of its own to release.
     *
     * A consumer sleeping inside [idle] on [idleCondition] is released
     * by [idleCondition.signalAll]; the next [FollowIterator.hasNext]
     * observes the closed flag and returns `false` promptly. Without this
     * `signalAll` a `Thread.sleep`-based implementation would keep the
     * iterator blocked until the full `pollIntervalMs` elapsed; the lock +
     * condition pair is what turns the operator's "close releases waits"
     * correction into a sub-cycle response.
     */
    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            // Drop any event that a concurrent `hasNext` may have
            // buffered; the contract is that no half-page is delivered
            // after `close` returns. A consumer that is sleeping in
            // `idle()` is woken by the signalAll below.
            pending.clear()
            idleCondition.signalAll()
        } finally {
            lock.unlock()
        }
    }

    // -------------------------------------------------------------- the iterator

    private inner class FollowIterator : Iterator<EventFollowEvent> {

        override fun hasNext(): Boolean {
            // Order matters: `closed` first (terminal state), then
            // `pending.isNotEmpty()` (events still to deliver), then
            // `terminated` (the natural end). The pending check must
            // come BEFORE `terminated` because `next()` calls
            // `hasNext()` recursively to verify, and once `terminated`
            // is set the only way to drain the last event is the
            // pending check.
            lock.lock()
            try {
                if (closed) {
                    pending.clear()
                    return false
                }
            } finally {
                lock.unlock()
            }
            if (pending.isNotEmpty()) return true
            lock.lock()
            try {
                if (terminated) return false
            } finally {
                lock.unlock()
            }
            // One poll cycle: fills `pending` with the events the
            // follow produces for this round (StateChanged first, then
            // Page, then optional terminal). Returns `false` if the
            // cycle was quiet (the caller may want to idle and retry).
            val drained = drainOnce()
            if (drained) return pending.isNotEmpty()
            // A quiet cycle: sleep `pollIntervalMs` and try once more,
            // in case a write landed during the sleep. The "twice then
            // give up" is the bounded-wait semantic: a consumer that
            // wants to wait longer calls [hasNext] again, and the
            // contract is that a closed handle will return `false`
            // promptly (the close path signals the condition so this
            // sleep wakes early).
            idle()
            lock.lock()
            try {
                if (closed) {
                    pending.clear()
                    return false
                }
            } finally {
                lock.unlock()
            }
            drainOnce()
            return pending.isNotEmpty()
        }

        override fun next(): EventFollowEvent {
            check(hasNext()) { "iterator is past its end; check hasNext() first" }
            return pending.removeFirst()
        }
    }

    // -------------------------------------------------------------- the poll loop

    /**
     * Run one poll cycle and fill [pending] with the events the follow
     * produces for this round. Returns `true` if at least one event
     * was queued, `false` if the cycle was quiet (the caller may want
     * to idle and retry).
     */
    private fun drainOnce(): Boolean {
        lock.lock()
        try {
            if (closed || terminated) return false
        } finally {
            lock.unlock()
        }

        // 1. First poll: detect unknown run BEFORE the initial state
        // change. The callback is the store-side authority the M1-A
        // adapter also uses, so the follow and the read port agree
        // on what "unknown" means. The design KDoc rule: the first
        // event is either StateChanged or Refused — a cursor past
        // the tail is detected at this same boundary and emits the
        // first event as Refused.
        if (!initialEmitted) {
            if (!runExists(runId)) {
                return queueRefusal(EventFollowRefusal.UnknownRun(runId))
            }
            // Read once at the open boundary so the FIRST event is
            // either StateChanged (when the page was served) or
            // Refused (when the read port refused). A cursor past
            // the tail surfaces here as CursorBeyondTail from the
            // M1-A adapter, which the follow translates to
            // EventFollowRefusal.UnknownRun (the closed event-follow
            // refusal hierarchy has no CursorBeyondTail case).
            val firstResult: EventRecordReadResult = try {
                port.readRecords(
                    runId = runId,
                    after = cursor,
                    query = options.query,
                    limit = options.maxRecords,
                )
            } catch (e: Exception) {
                return queueRefusal(EventFollowRefusal.UnknownRun(runId))
            }
            when (firstResult) {
                is EventRecordReadResult.Page -> {
                    initialEmitted = true
                    pending.add(
                        EventFollowEvent.StateChanged(
                            EventFollowState.Live(lastEmittedSequence),
                        ),
                    )
                    // Queue the page itself (possibly truncated) so
                    // the consumer sees the events without another
                    // `hasNext` round-trip.
                    val processed = handlePage(firstResult.slice)
                    if (processed) return true
                    // The page was empty / terminal — fall through so
                    // the StateChanged is the only event this round.
                    return pending.isNotEmpty()
                }
                is EventRecordReadResult.Refused -> {
                    return queueRefusal(translateRefusal(firstResult.refusal))
                }
            }
        }

        // 2. Subsequent polls: read a page through the M1-A read
        // port. The port is total and refuses closed — no exception
        // can leak out of here. The try is a defensive net for the
        // `port` reference itself becoming invalid.
        val result: EventRecordReadResult = try {
            port.readRecords(
                runId = runId,
                after = cursor,
                query = options.query,
                limit = options.maxRecords,
            )
        } catch (e: Exception) {
            return queueRefusal(EventFollowRefusal.UnknownRun(runId))
        }

        // 3. Translate the port result.
        return when (result) {
            is EventRecordReadResult.Page -> handlePage(result.slice)
            is EventRecordReadResult.Refused -> queueRefusal(translateRefusal(result.refusal))
        }
    }

    /**
     * Drain the page the read port returned and queue the follow's
     * events for it. The cursor advances strictly forward through
     * [EventRecordSlice.nextCursor] whether or not the page carried
     * any rows. Honors [EventFollowUntil.UntilSequence] by
     * truncating the page so the follow delivers only rows with
     * `sequence <= lastSequence`.
     */
    private fun handlePage(slice: EventRecordSlice): Boolean {
        // 3a. Honour EventFollowUntil.UntilSequence by truncating the
        // page to the bound. The consumer's explicit bound wins
        // over RunFinished observation: a RunFinished row beyond
        // lastSequence is NOT delivered and the follow does not
        // transition to RunFinished.
        val until = options.until
        val boundedSlice: EventRecordSlice = if (until is EventFollowUntil.UntilSequence) {
            val bound = until.lastSequence
            if (slice.records.any { it.sequence > bound }) {
                truncateToBound(slice, bound)
            } else {
                slice
            }
        } else {
            slice
        }

        cursor = boundedSlice.nextCursor

        // 3b. Detect [RunFinished] inside the typed rows. A page
        // can carry at most one RunFinished per run because the
        // store rejects duplicate (runId, sequence) pairs
        // (M1-A's UNIQUE(run_id, sequence) authority). If a
        // refusal row hides the RunFinished, the consumer still
        // sees it via `slice.refusals`; the follow's terminal
        // detection does not lose it.
        val runFinishedEvent: RunFinished? = boundedSlice.decoded
            .filterIsInstance<RunFinished>()
            .firstOrNull()

        if (boundedSlice.records.isNotEmpty()) {
            lastEmittedSequence = boundedSlice.records.last().sequence
            pending.add(
                EventFollowEvent.Page(
                    slice = boundedSlice,
                    newState = EventFollowState.Live(lastEmittedSequence),
                ),
            )
        }

        // 3c. UntilSequence is the consumer's explicit early-stop;
        // it takes priority over RunFinished and over the
        // natural-tail emission. If we have already reached the
        // bound, complete.
        if (until is EventFollowUntil.UntilSequence && lastEmittedSequence >= until.lastSequence) {
            return queueCompleted()
        }

        // 3d. UntilRunFinished: only RunFinished stops the follow.
        // RunFinished wins over the natural-tail emission; a
        // non-RunFinished tail continues to poll. This matches
        // the design's composition rule (a separate
        // StateChanged(RunFinished) is the terminal the contract
        // names, distinct from StreamSealed for Output).
        if (runFinishedEvent != null && !runFinishedEmitted) {
            runFinishedEmitted = true
            pending.add(
                EventFollowEvent.StateChanged(EventFollowState.RunFinished),
            )
            return queueCompleted()
        }

        // 3e. Tail reached: a page with `hasMore=false` is the
        // natural end of currently available history. For an
        // empty history (no rows yet), this is the natural-end
        // emission the design §4 composition rule names for an
        // empty run: `Completed` after the first poll. The
        // consumer decides whether to reopen with `after =
        // nextCursor` to keep following a run that may still be
        // live.
        if (!boundedSlice.hasMore && !completedEmitted) {
            completedEmitted = true
            return queueCompleted()
        }

        return pending.isNotEmpty()
    }

    /**
     * Build a [EventRecordSlice] that contains only the rows whose
     * `sequence <= bound`, with [EventRecordSlice.nextCursor] moved
     * to the last kept row and [EventRecordSlice.hasMore] set to
     * `false` (the truncated page is the terminal page for the
     * consumer).
     */
    private fun truncateToBound(slice: EventRecordSlice, bound: Long): EventRecordSlice {
        val kept = slice.records.filter { it.sequence <= bound }
        return if (kept.isEmpty()) {
            // No rows within the bound; the cursor sits at `bound`
            // so a future reopen starts from there.
            EventRecordSlice(
                records = emptyList(),
                nextCursor = EventCursor(slice.nextCursor.runId, bound),
                hasMore = false,
            )
        } else {
            EventRecordSlice(
                records = kept,
                nextCursor = EventCursor(slice.nextCursor.runId, kept.last().sequence),
                hasMore = false,
            )
        }
    }

    /**
     * Map a read-port refusal to the closed event-follow refusal
     * hierarchy. See the class KDoc's "Refusal translation" section
     * for the per-case rationale.
     */
    private fun translateRefusal(refusal: EventRecordReadRefusal): EventFollowRefusal =
        when (refusal) {
            is EventRecordReadRefusal.UnknownRun ->
                EventFollowRefusal.UnknownRun(refusal.runId)
            is EventRecordReadRefusal.CursorBeyondTail ->
                EventFollowRefusal.UnknownRun(refusal.runId)
            is EventRecordReadRefusal.StorageError ->
                EventFollowRefusal.UnknownRun(runId)
        }

    /**
     * Sleep on the consumer's thread for
     * [EventFollowOptions.pollIntervalMs]. The design pins
     * `pollIntervalMs = FOLLOW_IDLE_MILLIS = 25L` as the baseline;
     * a test that wants a faster turnaround passes `pollIntervalMs
     * = 0` (which the options allow because the predicate is `>= 0`).
     */
    /**
     * Sleep on the consumer's thread for [pollIntervalMs], interruptible
     * by [close].
     *
     * Replaces a plain [Thread.sleep] (which only wakes on
     * [Thread.interrupt], not on a flag check) with a [Condition.awaitNanos]
     * bound by [lock]. [close] does `lock.lock(); closed = true;
     * idleCondition.signalAll(); lock.unlock()`, so a consumer that
     * closed the handle from another thread wakes within the bounded
     * wait. The baseline cadence is `pollIntervalMs = FOLLOW_IDLE_MILLIS
     * = 25L`; passing `0L` short-circuits and yields immediately.
     */
    private fun idle() {
        val ms = options.pollIntervalMs
        if (ms <= 0) return
        val nanos = TimeUnit.MILLISECONDS.toNanos(ms)
        lock.lock()
        try {
            if (closed) return
            idleCondition.awaitNanos(nanos)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            // Honor closed-state semantics: the interrupted consumer
            // observes `closed == true` on the next hasNext and exits.
            closed = true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Set the terminal flag, queue an [EventFollowEvent.Completed],
     * and return `true` so the iterator knows the cycle produced at
     * least one event.
     */
    private fun queueCompleted(): Boolean {
        terminated = true
        pending.add(EventFollowEvent.Completed)
        return true
    }

    /**
     * Queue an [EventFollowEvent.Refused], set the terminal flag,
     * and return `true` so the iterator knows the cycle produced an
     * event.
     */
    private fun queueRefusal(reason: EventFollowRefusal): Boolean {
        terminated = true
        pending.add(EventFollowEvent.Refused(reason))
        return true
    }
}