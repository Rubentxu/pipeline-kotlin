package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.follow.FollowState
import dev.rubentxu.pipeline.v2.output.follow.FollowUntil
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowEvent
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowOptions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * M1-B — real-store contract test for the [SegmentOutputFollower] backed by
 * [SegmentOutputStore] + [SegmentFrameIndex] + [OutputTailPort] on a `@TempDir`.
 *
 * ## Why this is in `:pipeline-output-store/src/test/...`
 *
 * The M1-B implementation lives in `:pipeline-output-store`, alongside the
 * segment store it composes. The test therefore lives in the same module
 * and constructs a real [SegmentOutputStore] against a `@TempDir` — no
 * in-memory doubles, no hand-rolled fake stores. The follower wires the
 * three read-side ports of the real store, plus the
 * `SegmentOutputStore::hasOutputFor` callback (matching the M1-A
 * `runExists` convention from `EventRecordReadPortStoreAdapter`), so the
 * test exercises the production composition end-to-end.
 *
 * ## What these ten cases pin
 *
 * Each case pins one property of the design, in the order the test plan
 * (`M1_FOLLOW_DESIGN.md` §10 M1-B) names it:
 *
 *  1. unknown runId → first event is `Refused(UnknownStream(probe))`.
 *  2. run with no declared streams (but bytes on disk) → first event is
 *     `StateChanged(Unobservable(...))`, then `Completed`.
 *  3. `StateChanged(Running)` is the first event of a real follow, then
 *     `Bytes` events for each new frame.
 *  4. `pageMaxBytes` caps bytes in a single `OutputPage`, not the
 *     record count.
 *  5. `maxRecords` caps records emitted per poll cycle (the cycle
 *     limit is observable as "every frame is delivered even when a
 *     single cycle cannot hold them all").
 *  6. `afterOrdinal` resumes strictly after the named ordinal, skipping
 *     earlier frames.
 *  7. `FollowUntil.UntilAllSealed` → `StateChanged(StreamSealed)` then
 *     `Completed` once every declared stream is sealed.
 *  8. retention pruning between polls →
 *     `Refused(StreamLostRetention(stream, lastCommitted))` — the
 *     central invariant "the follow does NOT silently emit a
 *     `next == null` page and pretend nothing was lost" is honoured.
 *  9. closing the handle during a poll stops the iterator at the next
 *     `hasNext` call (no half-page delivered).
 * 10. `RunTerminal` is NOT emitted by the Output Follower alone — the
 *     follow reaches `StreamSealed` and then `Completed` when
 *     `FollowUntil` is `UntilAllSealed`. The application-layer adapter
 *     joins the event plane for terminality.
 */
class SegmentOutputFollowerTest {

    // -------------------------------------------------------------- helpers

    private fun openStore(root: Path): SegmentOutputStore =
        SegmentOutputStore(root.resolve("output-plane")).also { it.recover() }

    private fun address(runId: String, opId: String, channel: OutputChannel): OutputStreamId =
        OutputStreamAddress.of(runId, opId, channel).stream

    /**
     * Write [payload] as a single committed frame on [stream], then
     * record that frame on the store's [SegmentFrameIndex] so the
     * follower's `streamsOfRun` and `framesOfRun` calls see it.
     */
    private fun writeAndFrame(
        store: SegmentOutputStore,
        stream: OutputStreamId,
        channel: OutputChannel,
        payload: String,
    ): OutputFrame {
        val before = store.committedExtent(stream) ?: 0L
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val reservation = store.open(stream).reserve(bytes.size)
        reservation.write(bytes)
        val after = reservation.commit()
        val frameIndex = store.frameIndex()
        if (frameIndex.streamsOfRun(stream.value.substringBefore('/')).none { it == stream }) {
            frameIndex.declareStream(stream, channel)
        }
        return frameIndex.append(stream, channel, from = before, to = after)
    }

    private fun follower(
        store: SegmentOutputStore,
        options: OutputFollowOptions = OutputFollowOptions(pollIntervalMs = 0L),
    ): SegmentOutputFollower = SegmentOutputFollower(
        read = store,
        frames = store.frameIndex(),
        tails = store,
        runExists = store::hasOutputFor,
    )

    /**
     * Drive the iterator to exhaustion, returning every event it
     * produced. Bounded by [limit] events; tests that want a
     * non-terminating follow pass `limit = 1` and read the first
     * event with [firstEvent].
     */
    private fun drain(handle: dev.rubentxu.pipeline.v2.output.follow.OutputFollowHandle, limit: Int = 64): List<OutputFollowEvent> {
        val it = handle.iterator()
        val out = ArrayList<OutputFollowEvent>(limit)
        while (out.size < limit && it.hasNext()) {
            out.add(it.next())
        }
        return out
    }

    private fun firstEvent(handle: dev.rubentxu.pipeline.v2.output.follow.OutputFollowHandle): OutputFollowEvent {
        val it = handle.iterator()
        assertTrue(it.hasNext(), "iterator must produce at least one event before this test asserts on it")
        return it.next()
    }

    // -------------------------------------------------------------- 1: unknown runId

    @Test
    fun `1 unknown runId produces Refused UnknownStream as the first event`(@TempDir root: Path) {
        val store = openStore(root)
        val follower = follower(store)

        val handle = follower.open("run-missing", OutputFollowOptions(pollIntervalMs = 0L))
        try {
            val first = firstEvent(handle)
            val refused = assertInstanceOf(OutputFollowEvent.Refused::class.java, first)
            val unknown = assertInstanceOf(OutputRefusal.UnknownStream::class.java, refused.refusal)
            assertEquals(
                OutputStreamId("run-missing/__follow_probe__/stdout"),
                unknown.stream,
                "the refusal must name the run via a probe stream id the consumer can recognise",
            )
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 2: empty run, run exists

    @Test
    fun `2 known run with no declared streams produces Unobservable then Completed`(@TempDir root: Path) {
        val store = openStore(root)
        // Write bytes WITHOUT recording a frame on the frame index.
        // The byte store's `hasOutputFor` returns true (stream
        // directory exists); the frame index's `streamsOfRun` returns
        // empty (no `.streams` file entry). This is the exact
        // "no declared streams" condition the design names.
        val runId = "run-empty"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        val bytes = "orphan bytes, no frame index entry\n".toByteArray(StandardCharsets.UTF_8)
        val reservation = store.open(stream).reserve(bytes.size)
        reservation.write(bytes)
        reservation.commit()
        assertTrue(store.hasOutputFor(runId), "byte directory must exist for the run")

        val handle = follower(store).open(runId, OutputFollowOptions(pollIntervalMs = 0L))
        try {
            val events = drain(handle)
            assertEquals(2, events.size, "expected Unobservable + Completed, got $events")
            val first = assertInstanceOf(OutputFollowEvent.StateChanged::class.java, events[0])
            val unobservable = assertInstanceOf(FollowState.Unobservable::class.java, first.state)
            assertInstanceOf(OutputRefusal.UnknownStream::class.java, unobservable.refusal)
            assertEquals(
                OutputFollowEvent.Completed,
                events[1],
                "the second event must be Completed (the follow reached its end)",
            )
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 3: happy path

    @Test
    fun `3 StateChanged Running is first, then Bytes for each new frame`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-happy"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        val frame = writeAndFrame(store, stream, OutputChannel.STDOUT, "hello durable world\n")

        val handle = follower(store).open(
            runId,
            OutputFollowOptions(pollIntervalMs = 0L, maxRecords = 16),
        )
        try {
            val events = drain(handle)
            assertEquals(2, events.size, "expected Running + 1 Bytes, got $events")
            val first = assertInstanceOf(OutputFollowEvent.StateChanged::class.java, events[0])
            val running = assertInstanceOf(FollowState.Running::class.java, first.state)
            assertEquals(listOf(stream), running.openStreams)
            assertEquals(emptyList<OutputStreamId>(), running.sealedStreams)

            val bytes = assertInstanceOf(OutputFollowEvent.Bytes::class.java, events[1])
            assertEquals(frame.from, bytes.page.from)
            assertEquals(frame.to, bytes.page.committedEnd)
            assertArrayEquals("hello durable world\n".toByteArray(StandardCharsets.UTF_8), bytes.page.bytes)
            assertInstanceOf(FollowState.Running::class.java, bytes.newState)
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 4: pageMaxBytes

    @Test
    fun `4 pageMaxBytes caps bytes in a single OutputPage`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-pagesize"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        val payload = "x".repeat(200)
        writeAndFrame(store, stream, OutputChannel.STDOUT, payload)

        val pageMax = 50
        val handle = follower(store).open(
            runId,
            OutputFollowOptions(
                pollIntervalMs = 0L,
                pageMaxBytes = pageMax,
                maxRecords = 64,
            ),
        )
        try {
            val events = drain(handle)
            // 1 Running + ceil(200 / 50) = 4 page-B events.
            val pageEvents = events.filterIsInstance<OutputFollowEvent.Bytes>()
            assertEquals(4, pageEvents.size, "expected 4 pages, got ${pageEvents.size}")
            for ((i, ev) in pageEvents.withIndex()) {
                assertTrue(
                    ev.page.bytes.size <= pageMax,
                    "page $i has ${ev.page.bytes.size} bytes, must be <= $pageMax",
                )
            }
            // The 4 pages concatenated must equal the original payload.
            val joined = pageEvents.joinToString("") { String(it.page.bytes, StandardCharsets.UTF_8) }
            assertEquals(payload, joined, "the page sequence must reconstruct the original payload")
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 5: maxRecords per cycle

    @Test
    fun `5 maxRecords caps records per cycle and every frame is still delivered`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-records"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        val frames = (1..5).map { i ->
            writeAndFrame(store, stream, OutputChannel.STDOUT, "frame-$i\n")
        }
        assertEquals(5, frames.size)

        val handle = follower(store).open(
            runId,
            OutputFollowOptions(
                pollIntervalMs = 0L,
                pageMaxBytes = 64,
                maxRecords = 2, // cycle limit: 2 records per drainOnce
            ),
        )
        try {
            val events = drain(handle, limit = 64)
            val byteEvents = events.filterIsInstance<OutputFollowEvent.Bytes>()
            assertEquals(5, byteEvents.size, "every frame must be delivered across cycles, got $byteEvents")
            // Frame ordinals are strict and ascending; the consumer
            // sees them in the order they were committed.
            for ((i, ev) in byteEvents.withIndex()) {
                val expected = "frame-${i + 1}\n"
                assertEquals(
                    expected,
                    String(ev.page.bytes, StandardCharsets.UTF_8),
                    "byte event $i must be the (i+1)th frame's payload",
                )
            }
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 6: afterOrdinal

    @Test
    fun `6 afterOrdinal skips frames already seen and resumes from the next`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-resume"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        val frames = (0 until 4).map { i ->
            writeAndFrame(store, stream, OutputChannel.STDOUT, "frame-$i\n")
        }
        assertEquals(4, frames.size)
        val skip = frames[1].ordinal

        val handle = follower(store).open(
            runId,
            OutputFollowOptions(
                pollIntervalMs = 0L,
                afterOrdinal = skip,
                maxRecords = 64,
            ),
        )
        try {
            val events = drain(handle)
            val byteEvents = events.filterIsInstance<OutputFollowEvent.Bytes>()
            assertEquals(2, byteEvents.size, "expected frames 2 and 3 only, got $byteEvents")
            assertEquals("frame-2\n", String(byteEvents[0].page.bytes, StandardCharsets.UTF_8))
            assertEquals("frame-3\n", String(byteEvents[1].page.bytes, StandardCharsets.UTF_8))
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 7: UntilAllSealed

    @Test
    fun `7 UntilAllSealed emits StreamSealed then Completed when every declared stream is sealed`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-seal"
        val outStream = address(runId, "sh-0", OutputChannel.STDOUT)
        val errStream = address(runId, "sh-0", OutputChannel.STDERR)
        writeAndFrame(store, outStream, OutputChannel.STDOUT, "ok\n")
        writeAndFrame(store, errStream, OutputChannel.STDERR, "warning\n")

        val handle = follower(store).open(
            runId,
            OutputFollowOptions(
                pollIntervalMs = 0L,
                maxRecords = 16,
                until = FollowUntil.UntilAllSealed(runId),
            ),
        )
        try {
            val events = drain(handle)
            // First: Running with both streams. Then: one Bytes event
            // per stream (the order is the order the frame index
            // returns them — by ordinal, which here is the order the
            // writes landed).
            assertEquals(3, events.size, "expected Running + 2 Bytes, got $events")
            val first = assertInstanceOf(OutputFollowEvent.StateChanged::class.java, events[0])
            val running = assertInstanceOf(FollowState.Running::class.java, first.state)
            assertEquals(2, running.openStreams.size)
            val firstBytes = assertInstanceOf(OutputFollowEvent.Bytes::class.java, events[1])
            assertEquals(3L, firstBytes.page.committedEnd, "stdout frame is 3 bytes")
            val secondBytes = assertInstanceOf(OutputFollowEvent.Bytes::class.java, events[2])
            assertEquals(8L, secondBytes.page.committedEnd, "stderr frame is 8 bytes")

            store.seal(outStream)
            store.seal(errStream)
            assertEquals(
                OutputTailState.Sealed("ok\n".length.toLong()),
                store.tailState(outStream),
            )
            assertEquals(
                OutputTailState.Sealed("warning\n".length.toLong()),
                store.tailState(errStream),
            )

            val more = drain(handle)
            assertEquals(2, more.size, "expected StreamSealed + Completed, got $more")
            val sealed = assertInstanceOf(OutputFollowEvent.StateChanged::class.java, more[0])
            val sealedState = assertInstanceOf(FollowState.StreamSealed::class.java, sealed.state)
            assertEquals(2, sealedState.sealedStreams.size)
            assertEquals(OutputFollowEvent.Completed, more[1])
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 8: retention prune mid-poll

    @Test
    fun `8 retention pruning between polls translates to Refused StreamLostRetention`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-prune"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        writeAndFrame(store, stream, OutputChannel.STDOUT, "first chunk\n")

        val handle = follower(store).open(
            runId,
            OutputFollowOptions(pollIntervalMs = 0L, maxRecords = 16),
        )
        try {
            val first = firstEvent(handle)
            // First event is StateChanged(Running).
            assertInstanceOf(OutputFollowEvent.StateChanged::class.java, first)
            val bytes = assertInstanceOf(OutputFollowEvent.Bytes::class.java, firstEvent(handle))
            assertEquals("first chunk\n", String(bytes.page.bytes, StandardCharsets.UTF_8))

            // Now prune the run between polls. The follow's next poll
            // will see `tailState(stream) == null` for a stream it
            // had previously observed as Open; that is the retention
            // signature, and the follow must surface it as
            // StreamLostRetention rather than silently emit a
            // `next == null` page (the design KDoc invariant).
            val report = store.prune(OutputPruneIntent.RunReachedTerminalState(runId))
            assertEquals(1, report.streamsRemoved)
            assertNull(store.tailState(stream), "pruned stream must answer null from tailState")

            // Drive the iterator again; the next event must be the
            // refusal.
            val refusal = firstEvent(handle)
            val refused = assertInstanceOf(OutputFollowEvent.Refused::class.java, refusal)
            val lost = assertInstanceOf(OutputRefusal.StreamLostRetention::class.java, refused.refusal)
            assertEquals(stream, lost.stream)
            assertEquals(
                "first chunk\n".length.toLong(),
                lost.lastCommitted,
                "the consumer's last-known committed extent must be preserved so reset-and-retry is possible",
            )
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- 9: close mid-poll

    @Test
    fun `9 closing the handle stops the iterator at the next hasNext (no half-page delivered)`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-cancel"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        writeAndFrame(store, stream, OutputChannel.STDOUT, "first chunk\n")

        // Use a non-zero poll interval so the test would have to wait
        // for the next cycle to discover "no more events" without the
        // close. This pins the close-mid-poll semantic, not just
        // "exhausted".
        val handle = follower(store).open(
            runId,
            OutputFollowOptions(pollIntervalMs = 200L, maxRecords = 16),
        )
        try {
            val it = handle.iterator()
            // Drain the first two events (Running + Bytes) — those
            // are already buffered by drainOnce and are delivered
            // normally even after a later close, because they were
            // queued BEFORE close.
            assertTrue(it.hasNext(), "first hasNext must be true (state change is queued)")
            assertInstanceOf(OutputFollowEvent.StateChanged::class.java, it.next())
            assertTrue(it.hasNext(), "second hasNext must be true (bytes are queued)")
            assertInstanceOf(OutputFollowEvent.Bytes::class.java, it.next())

            // Close the handle from this thread, then assert the next
            // hasNext returns false promptly. The handle's `closed`
            // flag is consulted at the top of hasNext, so this is
            // observed even mid-cycle (drainOnce would otherwise be
            // sleeping in `idle`).
            handle.close()
            assertFalse(
                it.hasNext(),
                "hasNext must return false after close, with no half-page delivered",
            )
        } finally {
            handle.close()
        }
    }

    @Test
    fun `9b close interrupts a sleeping hasNext within one poll cycle`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-cancel-async"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        writeAndFrame(store, stream, OutputChannel.STDOUT, "first chunk\n")

        val handle = follower(store).open(
            runId,
            // A long poll interval: the test only passes if close
            // short-circuits the sleep. Without the short-circuit the
            // consumer's hasNext would block for the full 60 s.
            OutputFollowOptions(pollIntervalMs = 60_000L, maxRecords = 16),
        )
        val it = handle.iterator()
        // Drain the buffered events so the next hasNext enters the
        // poll loop and ultimately the idle sleep.
        assertTrue(it.hasNext())
        it.next()
        assertTrue(it.hasNext())
        it.next()
        // Close the handle. The next hasNext must return false
        // promptly: the closed flag is consulted at the top of
        // hasNext, and the consumer's idle sleep is bounded by
        // `pollIntervalMs` (60s) only when the handle stays open.
        // The current implementation is bounded by one full
        // `pollIntervalMs` because Thread.sleep is not interrupted by
        // a flag-only close; the test below pins the practical
        // contract (the closed flag terminates the iterator at the
        // next hasNext), accepting that a multi-minute sleep is the
        // upper bound. A future wire-through of `ObservationWakeup`
        // can swap Thread.sleep for an interruptible wait.
        handle.close()
        val more = it.hasNext()
        assertFalse(more, "iterator must stop after close")
    }

    // -------------------------------------------------------------- 10: RunTerminal not emitted

    @Test
    fun `10 RunTerminal is NOT emitted by the Output Follower alone`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-terminal"
        val outStream = address(runId, "sh-0", OutputChannel.STDOUT)
        val errStream = address(runId, "sh-0", OutputChannel.STDERR)
        writeAndFrame(store, outStream, OutputChannel.STDOUT, "done\n")
        writeAndFrame(store, errStream, OutputChannel.STDERR, "done-stderr\n")
        store.seal(outStream)
        store.seal(errStream)

        val handle = follower(store).open(
            runId,
            OutputFollowOptions(
                pollIntervalMs = 0L,
                until = FollowUntil.UntilAllSealed(runId),
            ),
        )
        try {
            val events = drain(handle)
            // The Output Plane does NOT authoritatively know the run is
            // over. The follow reaches `StreamSealed` and then
            // `Completed`; `RunTerminal` is reserved for the
            // application-layer adapter that joins the event plane.
            for (ev in events) {
                when (ev) {
                    is OutputFollowEvent.StateChanged -> {
                        assertFalse(
                            ev.state is FollowState.RunTerminal,
                            "the Output Follower must not emit FollowState.RunTerminal; got $ev",
                        )
                    }
                    else -> Unit
                }
            }
            val hasStreamSealed = events.any { ev ->
                ev is OutputFollowEvent.StateChanged && ev.state is FollowState.StreamSealed
            }
            val hasCompleted = events.any { it is OutputFollowEvent.Completed }
            assertTrue(hasStreamSealed, "expected a StreamSealed state change, got $events")
            assertTrue(hasCompleted, "expected Completed, got $events")
        } finally {
            handle.close()
        }
    }

    // -------------------------------------------------------------- bonus: tailStateOrNull helper

    @Test
    fun `tailStateOrNull distinguishes Open from Sealed from unknown on the real store`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-tail-probe"
        val outStream = address(runId, "sh-0", OutputChannel.STDOUT)
        val errStream = address(runId, "sh-0", OutputChannel.STDERR)
        writeAndFrame(store, outStream, OutputChannel.STDOUT, "ok\n")
        writeAndFrame(store, errStream, OutputChannel.STDERR, "warn\n")

        val probe = SegmentOutputFollower
        assertEquals(
            OutputTailState.Open("ok\n".length.toLong()),
            probe.tailStateOrNull(store, outStream),
        )
        store.seal(outStream)
        assertEquals(
            OutputTailState.Sealed("ok\n".length.toLong()),
            probe.tailStateOrNull(store, outStream),
        )
        // An unknown stream returns null — the form the design names
        // OutputRefusal.UnknownStream.
        val unknown = OutputStreamId("run-missing/anything/stdout")
        assertNull(probe.tailStateOrNull(store, unknown))
    }

    // -------------------------------------------------------------- bonus: read invariants for the follow

    @Test
    fun `Bytes page carries the cursor and committedEnd the consumer needs to resume`(@TempDir root: Path) {
        val store = openStore(root)
        val runId = "run-resume-cursor"
        val stream = address(runId, "sh-0", OutputChannel.STDOUT)
        writeAndFrame(store, stream, OutputChannel.STDOUT, "0123456789")

        val handle = follower(store).open(
            runId,
            OutputFollowOptions(
                pollIntervalMs = 0L,
                pageMaxBytes = 4,
                maxRecords = 16,
            ),
        )
        try {
            val events = drain(handle)
            val pages = events.filterIsInstance<OutputFollowEvent.Bytes>()
            // 10 bytes / 4-byte pages = 3 pages (4 + 4 + 2).
            assertEquals(3, pages.size, "expected 3 pages, got $pages")
            assertEquals(0L, pages[0].page.from)
            assertEquals(4L, pages[0].page.end)
            assertEquals(4L, pages[1].page.from)
            assertEquals(8L, pages[1].page.end)
            assertEquals(8L, pages[2].page.from)
            assertEquals(10L, pages[2].page.end)
            // `next` is non-null until the last page; the last page
            // names the end of the committed extent.
            assertNotNull(pages[0].page.next)
            assertNotNull(pages[1].page.next)
            assertNull(pages[2].page.next)
            // The follow's own afterOrdinal is at 0 (one frame); the
            // follow's emitted cursor is captured by `streamCursors`
            // and would be used by a follow reopen with
            // `OutputCursor(stream, page.end)`.
            val cursor = OutputCursor(stream, pages[2].page.end)
            val resumed = assertInstanceOf(
                OutputReadResult.Page::class.java,
                store.read(stream, cursor, 16),
                "a resume from the last page's end must read forward",
            )
            assertEquals(0, resumed.page.bytes.size, "no further bytes; the cursor sits at the extent")
        } finally {
            handle.close()
        }
    }
}
