package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.follow.FollowState
import dev.rubentxu.pipeline.v2.output.follow.FollowUntil
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowEvent
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowHandle
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowOptions
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M1-B — production [OutputFollowHandle] driven by a polling loop on the
 * consumer's thread.
 *
 * ## What this is and what it is not
 *
 * The handle is the live state of one [dev.rubentxu.pipeline.v2.output.follow.OutputFollower.open]
 * call. It holds:
 *  - the run being followed ([runId]),
 *  - the read-side collaborators ([read], [frames], [tails]),
 *  - the **after-ordinal** cursor, which is the frame index's strict cut
 *    (`framesOfRun(runId, afterOrdinal, limit)` returns frames whose
 *    `ordinal > afterOrdinal`),
 *  - the **byte cursor per stream**, which advances through
 *    [OutputReadPort.read] pages,
 *  - the **last known committed extent per stream**, used to translate a
 *    retention prune between two polls into [OutputRefusal.StreamLostRetention]
 *    rather than a silent `next == null` page (the design KDoc names this
 *    the central invariant: "the follow does NOT silently emit a
 *    `next == null` page and pretend nothing was lost").
 *
 * ## Polling, not push
 *
 * The wakeup vocabulary (`ObservationWakeup`) is not wired to a real
 * emitter in this repository, so the first cut polls. The handle sleeps
 * [OutputFollowOptions.pollIntervalMs] between cycles, on the consumer's
 * thread: a future non-breaking addition can swap the implementation
 * for one that honours `ObservationWakeup` without changing the public
 * type.
 *
 * ## Single-observer, idempotent close
 *
 * The handle is single-observer: calling [iterator] returns the same
 * [FollowIterator] every time. The iterator is single-pass: once
 * [OutputFollowEvent.Completed] or [OutputFollowEvent.Refused] is
 * emitted, subsequent [FollowIterator.hasNext] calls return `false`.
 * [close] is idempotent and interrupts a mid-poll consumer: after
 * `close`, the next [FollowIterator.hasNext] returns `false` and no
 * half-page is delivered.
 *
 * ## RunTerminal is NOT emitted
 *
 * The Output Plane does not authoritatively know if a run is over
 * (cf. [OutputTailState] KDoc: "Putting an outcome here would make the
 * Output Plane a second authority over execution results"). The
 * application-layer adapter joins the event plane; the M1-B follower
 * stops at [FollowState.StreamSealed] and then [OutputFollowEvent.Completed]
 * when the consumer's [FollowUntil] is [FollowUntil.UntilAllSealed].
 *
 * @see M1_FOLLOW_DESIGN.md §2.1 for the type surface and §4 for the
 *   composition contract.
 */
internal class SegmentOutputFollowHandle(
    private val read: OutputReadPort,
    private val frames: OutputFrameIndex,
    private val tails: OutputTailPort,
    private val runExists: (String) -> Boolean,
    private val runId: String,
    private val options: OutputFollowOptions,
) : OutputFollowHandle {

    private val closed = AtomicBoolean(false)

    /** The strict cut for `framesOfRun`: every emitted frame has `ordinal > afterOrdinal`. */
    private var afterOrdinal: Long = options.afterOrdinal ?: -1L

    /**
     * Per-stream last-known committed extent, populated on the first
     * non-null [OutputTailPort.tailState] answer and consulted on every
     * subsequent poll to translate a `null` answer (retention pruned the
     * stream) into [OutputRefusal.StreamLostRetention] with the
     * consumer's last-known position preserved.
     */
    private val lastCommitted = HashMap<OutputStreamId, Long>()

    /**
     * Per-stream byte cursor (the `committedOffset` of the next
     * [OutputReadPort.read] call). Initially `null`, meaning "start at
     * the first frame's `from`". Advanced by the page boundary returned
     * from each successful read.
     */
    private val streamCursors = HashMap<OutputStreamId, Long>()

    /** The first state change has been emitted, so subsequent polls do not re-announce it. */
    private var initialEmitted = false

    /** One-shot flag for the `Unobservable` + `Completed` end of the no-streams case. */
    private var unobservableEmitted = false

    /** One-shot flag for the `StreamSealed` + `Completed` end of the `UntilAllSealed` case. */
    private var streamSealedEmitted = false

    /** The follow reached its end; the iterator returns `false` from [FollowIterator.hasNext]. */
    private var terminated = false

    /** Total bytes emitted across all `Bytes` events, for [FollowUntil.UntilBytesRead]. */
    private var bytesEmitted: Long = 0L

    /** One or more events produced by the current poll cycle, drained one-per-[FollowIterator.next]. */
    private val pending: ArrayDeque<OutputFollowEvent> = ArrayDeque()

    override fun iterator(): Iterator<OutputFollowEvent> = FollowIterator()

    /**
     * Idempotent. The contract guarantees that no half-page is delivered
     * after this returns: any event buffered by an in-flight
     * [FollowIterator.hasNext] is dropped, and the next call to
     * [FollowIterator.hasNext] returns `false`. The store-side
     * collaborators (the segment store and the frame index) are owned by
     * the [SegmentOutputFollower] factory, not by this handle, so the
     * handle has no resources of its own to release.
     *
     * A [Thread.sleep] in progress inside [idle] is interrupted and the
     * closed flag is set, so a consumer that closed the handle on
     * another thread is unblocked within milliseconds.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Drop any event that a concurrent `hasNext` may have
            // buffered; the contract is that no half-page is delivered
            // after `close` returns. A consumer that is sleeping in
            // `idle()` will be released by the next `hasNext` check on
            // the closed flag (bounded by `pollIntervalMs`).
            pending.clear()
        }
    }

    // -------------------------------------------------------------- the iterator

    private inner class FollowIterator : Iterator<OutputFollowEvent> {

        override fun hasNext(): Boolean {
            if (closed.get()) {
                pending.clear()
                return false
            }
            if (pending.isNotEmpty()) return true
            if (terminated) return false
            // One poll cycle: fills `pending` with up to `maxRecords` events
            // (or fewer if the cycle ends earlier). Returns the first event
            // from the queue, or `null` if the cycle was quiet.
            val drained = drainOnce()
            if (drained) return pending.isNotEmpty()
            // A quiet cycle: sleep `pollIntervalMs` and try once more, in
            // case a write landed during the sleep. The "twice then give
            // up" is the bounded-wait semantic: a consumer that wants to
            // wait longer calls [hasNext] again, and the contract is that
            // a closed handle will return `false` promptly.
            idle()
            if (closed.get()) {
                pending.clear()
                return false
            }
            drainOnce()
            return pending.isNotEmpty()
        }

        override fun next(): OutputFollowEvent {
            check(hasNext()) { "iterator is past its end; check hasNext() first" }
            return pending.removeFirst()
        }
    }

    // -------------------------------------------------------------- the poll loop

    /**
     * Run one poll cycle and fill [pending] with up to [OutputFollowOptions.maxRecords]
     * events. Returns `true` if at least one event was queued, `false` if
     * the cycle was quiet (the caller may want to idle and retry).
     */
    private fun drainOnce(): Boolean {
        if (closed.get() || terminated) return false

        // 1. Discover the declared streams for this run.
        val declared: List<OutputStreamId> = try {
            frames.streamsOfRun(runId)
        } catch (e: Exception) {
            return queueRefusal(OutputRefusal.StorageError(shortCause(e)))
        }

        // 2. No declared streams — either unknown run or empty run.
        if (declared.isEmpty()) {
            if (!runExists(runId)) {
                val probe = OutputStreamId("$runId/__follow_probe__/stdout")
                return queueRefusal(OutputRefusal.UnknownStream(probe))
            }
            // Known run with no declared streams. Per the design §2.1
            // ("Open on a run with no declared streams produces the
            // first event as `FollowState.Unobservable`"), the follow
            // is unobservable for output and reaches `Completed` after
            // the state change.
            if (!unobservableEmitted) {
                unobservableEmitted = true
                pending.add(
                    OutputFollowEvent.StateChanged(
                        FollowState.Unobservable(
                            OutputRefusal.UnknownStream(
                                OutputStreamId("$runId/__follow_probe__/stdout"),
                            ),
                        ),
                    ),
                )
                return true
            }
            return queueCompleted()
        }

        // 3. For each declared stream, ask the tail port.
        val open: MutableList<OutputStreamId> = ArrayList(declared.size)
        val sealed: MutableList<OutputStreamId> = ArrayList()
        for (stream in declared) {
            if (closed.get()) return pending.isNotEmpty()
            val state: OutputTailState? = try {
                tails.tailState(stream)
            } catch (e: Exception) {
                return queueRefusal(OutputRefusal.StorageError(shortCause(e)))
            }
            when (state) {
                null -> {
                    // A stream that the tail port no longer knows about
                    // is the retention-prune signature. The last-known
                    // committed extent is preserved as the refusal's
                    // `lastCommitted` so the consumer can decide whether
                    // to reset-and-retry or escalate.
                    val prior = lastCommitted[stream]
                    if (prior != null) {
                        return queueRefusal(OutputRefusal.StreamLostRetention(stream, prior))
                    }
                    // Declared but never observed open/sealed: not a
                    // refusal yet, just a stream that has not produced
                    // bytes. The follower keeps it as "not yet open".
                }
                is OutputTailState.Open -> {
                    lastCommitted[stream] = state.committedEnd
                    open.add(stream)
                }
                is OutputTailState.Sealed -> {
                    lastCommitted[stream] = state.finalEnd
                    sealed.add(stream)
                }
            }
        }

        // 4. Emit the initial state change once. The first poll always
        // announces the follow's current state so the consumer can
        // decide without reading bytes.
        if (!initialEmitted) {
            initialEmitted = true
            pending.add(OutputFollowEvent.StateChanged(currentState(open, sealed)))
            return true
        }

        // 5. If every declared stream is sealed and the consumer asked
        // for `UntilAllSealed`, the follow reaches its terminal. The
        // Output Plane does NOT emit `RunTerminal`; the application
        // layer joins the event plane to learn that.
        if (open.isEmpty() && sealed.isNotEmpty() && options.until is FollowUntil.UntilAllSealed) {
            if (!streamSealedEmitted) {
                streamSealedEmitted = true
                pending.add(
                    OutputFollowEvent.StateChanged(FollowState.StreamSealed(sealed.toList())),
                )
                return true
            }
            return queueCompleted()
        }

        // 6. Poll for new frames. `maxRecords` bounds records per cycle,
        // so we stop as soon as the budget is exhausted and yield to
        // the consumer; the next [hasNext] will resume a new cycle.
        val maxRecords = options.maxRecords
        val cursor = afterOrdinal
        val framesInRun: List<OutputFrame> = try {
            frames.framesOfRun(runId, cursor, maxRecords)
        } catch (e: Exception) {
            return queueRefusal(OutputRefusal.StorageError(shortCause(e)))
        }
        if (framesInRun.isEmpty()) {
            // 7. Check `UntilBytesRead` even when no bytes were emitted
            // this cycle (a long-quiet run may have hit the bound on a
            // previous cycle).
            val untilAfter = options.until
            if (untilAfter is FollowUntil.UntilBytesRead && bytesEmitted >= untilAfter.limit) {
                return queueCompleted()
            }
            return false
        }
        for (frame in framesInRun) {
            if (closed.get() || terminated) return pending.isNotEmpty()
            if (pending.size >= maxRecords) {
                // Out of budget before reading this frame. Keep
                // `afterOrdinal` at the cut so the next cycle re-reads
                // the same set of frames; the byte cursors per stream
                // are preserved so partial drains are not duplicated.
                return true
            }
            // Page through the frame's bytes in `pageMaxBytes` chunks
            // until the frame is fully drained. Each page is a record.
            // The byte cursor survives a partial-drain across cycles:
            // if `maxRecords` is hit mid-frame, the next cycle starts
            // at the same frame and resumes from the cursor.
            var frameCursor = streamCursors[frame.stream] ?: frame.from
            val frameEnd = frame.to
            while (frameCursor < frameEnd) {
                if (closed.get() || terminated) return pending.isNotEmpty()
                if (pending.size >= maxRecords) {
                    // Out of budget mid-frame: leave `afterOrdinal` at
                    // the previous cut so the next cycle re-reads this
                    // frame from `frameCursor` (preserved in
                    // `streamCursors`).
                    streamCursors[frame.stream] = frameCursor
                    return true
                }
                val readResult: OutputReadResult = try {
                    // Bound the read by `frameEnd` so a page never
                    // crosses the frame boundary. Without this, a
                    // frame of 8 bytes inside a 40-byte stream would
                    // return the full 40-byte page on the first read
                    // and silently drop the four subsequent frames'
                    // ordinal semantics. The read method's `maxBytes`
                    // is an upper bound, not a frame boundary; the
                    // follower is the one that knows where the frame
                    // ends and must shrink the read accordingly.
                    val readEnd = minOf(frameEnd, frameCursor + options.pageMaxBytes)
                    val boundedMax = (readEnd - frameCursor).toInt().coerceAtLeast(0)
                    read.read(
                        stream = frame.stream,
                        cursor = OutputCursor(frame.stream, frameCursor),
                        maxBytes = boundedMax,
                    )
                } catch (e: Exception) {
                    return queueRefusal(OutputRefusal.StorageError(shortCause(e)))
                }
                when (readResult) {
                    is OutputReadResult.Page -> {
                        val page: OutputPage = readResult.page
                        val newCursor = page.next?.committedOffset ?: page.end
                        frameCursor = newCursor
                        streamCursors[frame.stream] = newCursor
                        bytesEmitted += page.bytes.size
                        val stateForNewBytes = currentState(open, sealed)
                        // The frame is fully drained on the page that
                        // crosses `frameEnd`. Earlier pages leave the
                        // frame's ordinal out of the `afterOrdinal`
                        // cut, so a mid-frame cycle exits keep the
                        // partial frame in the next cycle's window.
                        if (newCursor >= frameEnd) {
                            afterOrdinal = frame.ordinal
                        }
                        pending.add(OutputFollowEvent.Bytes(page, stateForNewBytes))
                        // The bytes-read bound, if set, terminates the
                        // follow as soon as the bound is met.
                        val until = options.until
                        if (until is FollowUntil.UntilBytesRead && bytesEmitted >= until.limit) {
                            return queueCompleted()
                        }
                    }
                    is OutputReadResult.Refused -> {
                        return queueRefusal(readResult.reason)
                    }
                }
            }
            // Frame fully drained. The page loop above updates
            // `afterOrdinal` on the last page; the assignment below is
            // the safety net for the edge case where `frameEnd` was
            // already at the cursor when we entered (no pages read).
            if (streamCursors[frame.stream] == frameEnd) {
                afterOrdinal = frame.ordinal
            }
        }
        return pending.isNotEmpty()
    }

    /**
     * Sleep on the consumer's thread for [pollIntervalMs]. The design
     * pins `pollIntervalMs = FOLLOW_IDLE_MILLIS = 25L` as the baseline;
     * a test that wants a faster turnaround passes `pollIntervalMs = 0`
     * (which the options allow because the predicate is `>= 0`).
     */
    private fun idle() {
        if (closed.get()) return
        val ms = options.pollIntervalMs
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            closed.set(true)
        }
    }

    /**
     * The follow state carried by every `Bytes` and `StateChanged` event.
     * `Running(openStreams, sealedStreams)` while any declared stream is
     * still open; `StreamSealed(sealed)` when every declared stream is
     * sealed.
     */
    private fun currentState(open: List<OutputStreamId>, sealed: List<OutputStreamId>): FollowState =
        if (open.isEmpty() && sealed.isNotEmpty()) {
            FollowState.StreamSealed(sealed)
        } else {
            FollowState.Running(openStreams = open.toList(), sealedStreams = sealed.toList())
        }

    /**
     * Set the terminal flag, queue a [OutputFollowEvent.Completed], and
     * return `true` so the iterator knows the cycle produced at least
     * one event.
     */
    private fun queueCompleted(): Boolean {
        terminated = true
        pending.add(OutputFollowEvent.Completed)
        return true
    }

    /**
     * Queue a [OutputFollowEvent.Refused], set the terminal flag, and
     * return `true` so the iterator knows the cycle produced an event.
     */
    private fun queueRefusal(reason: OutputRefusal): Boolean {
        terminated = true
        pending.add(OutputFollowEvent.Refused(reason))
        return true
    }

    private fun shortCause(e: Throwable): String {
        val cls = e::class.simpleName ?: e.javaClass.name
        val msg = e.message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
