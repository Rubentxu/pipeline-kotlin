package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * OBS-D3: wakeups are coalescible, records never are, and a follower stops only when it can prove
 * the tail is final.
 *
 * ## Harness fidelity
 *
 * HF2 where the claim touches storage, HF0 where it does not.
 *
 * `coalesceWakeup` and `followDecision` read no clock, no filesystem and no ambient state, so calling
 * them directly is faithful. WAKE-4 is the row that needed more: coalescing is only legal because a
 * wakeup loses no observation, and a pure row could only ever assert that an arithmetic picks a
 * number. So WAKE-4 publishes through a real `SegmentOutputStore` and a real frame index and reads
 * back with the OBS-D2 reader, comparing what a follower woken on EVERY page recovers against what
 * one woken on a COALESCED position recovers. If they differ, the fold proved nothing.
 *
 * ## What this pins
 *
 * ```text
 * WAKE-1   coalescing keeps the furthest position within one lane
 * WAKE-2   coalescing never crosses lanes: an event position is not an output position
 * WAKE-3   coalescing never crosses runs
 * WAKE-4   a coalesced position recovers exactly what the un-coalesced ones would have
 * WAKE-5   a wakeup carries no irreplaceable payload, so dropping all of them loses nothing
 * FOLLOW-1 finished only when nothing is pending and every known stream is Sealed
 * FOLLOW-2 an unknown stream keeps the follower going; null is not Sealed
 * FOLLOW-3 a stream never observed is not evidence that the run ended
 * ```
 *
 * ## Mutation
 *
 * - `M-D3` (keep the furthest position) → keep the OLDER of the two. This is the failure with the
 *   worst consequence: a follower waiting for ordinal 900 is handed 12, stops early, and reports a
 *   still-writing run as finished.
 * - `M-D4` (null is not Sealed) → treat a null tail state as `Sealed`.
 * - `M-D5` (an empty history is not completion) → treat "no stream seen" as finished.
 */
class ObservationWakeupTest {

    @TempDir
    lateinit var root: Path

    private val run = "run-obsd3"
    private val otherRun = "run-other"
    private val stdout = OutputStreamId("$run/build/sh-0/stdout")

    private fun events(seq: Long, r: String = run) = ObservationWakeup.EventsCommitted(r, seq)
    private fun output(ordinal: Long, r: String = run) = ObservationWakeup.OutputAdvanced(r, ordinal)

    @Test
    fun `WAKE-1 coalescing keeps the furthest position within one lane`() {
        assertEquals(
            events(900),
            coalesceWakeup(events(12), events(900)),
            "the furthest event sequence must win",
        )
        assertEquals(
            events(900),
            coalesceWakeup(events(900), events(12)),
            "an out-of-order older position must NOT pull the consumer back",
        )
        assertEquals(output(41), coalesceWakeup(output(41), output(3)))
    }

    @Test
    fun `WAKE-2 coalescing never crosses lanes`() {
        val merged = coalesceWakeup(events(900), output(41))

        assertEquals(
            output(41),
            merged,
            "an event sequence and an output ordinal are different orders. Merging them would " +
                "fabricate a clock across two planes that have none, which is the same fabrication " +
                "that keeps ObservationView.FULL refused",
        )
        assertTrue(
            merged is ObservationWakeup.OutputAdvanced,
            "the result must stay a single lane's wakeup, never a union of both",
        )
    }

    @Test
    fun `WAKE-3 coalescing never crosses runs`() {
        val merged = coalesceWakeup(events(900), events(3, r = otherRun))

        assertEquals(otherRun, merged.runId, "two runs' sequences are not comparable")
        assertEquals(3L, (merged as ObservationWakeup.EventsCommitted).lastSequence)
    }

    @Test
    fun `WAKE-4 a coalesced position recovers exactly what the un-coalesced ones would have`() {
        val store = SegmentOutputStore(root.resolve("output-plane"))
        store.recover()
        val index = store.frameIndex()
        val reader: ObservationOutputReader = FrameIndexedObservationOutputReader(index, store, store)

        val lineCount = 40
        for (index2 in 0 until lineCount) {
            val payload = "line $index2\n".toByteArray(Charsets.UTF_8)
            val from = store.committedExtent(stdout) ?: 0L
            val reservation = store.open(stdout).reserve(payload.size)
            reservation.write(payload)
            reservation.commit()
            index.append(stdout, OutputChannel.STDOUT, from, from + payload.size)
        }

        // A follower woken on EVERY page: one frame at a time, coalescing as it goes.
        var stepwise: ObservationWakeup? = null
        val stepwiseText = StringBuilder()
        var ordinal = -1L
        while (true) {
            val page = pageOf(reader.readOutput(run, ordinal, frameLimit = 1))
            if (page.records.isEmpty()) break
            ordinal = page.lastOrdinal
            page.records.forEach {
                stepwiseText.append(it.text)
                stepwise = coalesceWakeup(stepwise, output(it.frame.ordinal))
            }
        }

        // The same follower, woken on the single COALESCED position a conflated bus would deliver.
        val collapsed = stepwise
        val collapsedOrdinal = (collapsed as ObservationWakeup.OutputAdvanced).lastOrdinal
        val fromCollapsed = StringBuilder()
        var resume = -1L
        while (true) {
            val page = pageOf(reader.readOutput(run, resume, frameLimit = lineCount))
            if (page.records.isEmpty()) break
            resume = page.lastOrdinal
            page.records.forEach { fromCollapsed.append(it.text) }
        }

        assertEquals(
            (lineCount - 1).toLong(),
            collapsedOrdinal,
            "the coalesced position must be the furthest frame published",
        )
        assertEquals(
            stepwiseText.toString(),
            fromCollapsed.toString(),
            "a follower holding the coalesced position must recover EXACTLY the bytes a follower " +
                "woken on every page recovered. If these differ, coalescing lost or duplicated an " +
                "observation and the whole premise of dropping wakeups is void",
        )
        assertEquals(lineCount, fromCollapsed.toString().lines().count { it.isNotEmpty() })
    }

    @Test
    fun `WAKE-5 a wakeup carries no irreplaceable payload, so dropping all of them loses nothing`() {
        val store = SegmentOutputStore(root.resolve("wa5"))
        store.recover()
        val index = store.frameIndex()
        val stream = OutputStreamId("$run/build/sh-1/stdout")
        for (i in 0 until 3) {
            val payload = "x$i\n".toByteArray(Charsets.UTF_8)
            val from = store.committedExtent(stream) ?: 0L
            val reservation = store.open(stream).reserve(payload.size)
            reservation.write(payload)
            reservation.commit()
            index.append(stream, OutputChannel.STDOUT, from, from + payload.size)
        }

        // A consumer woken ZERO times, that simply polls the authority from its own cursor.
        val reader: ObservationOutputReader = FrameIndexedObservationOutputReader(index, store, store)
        val text = pageOf(reader.readOutput(run, -1, frameLimit = 10)).text

        assertEquals(
            "x0\nx1\nx2\n",
            text,
            "no wakeup is the only copy of anything: the frames live in the Output Plane, so a " +
                "consumer that was never signalled still recovers every byte. That is what makes " +
                "coalescing legal rather than lossy",
        )
    }

    @Test
    fun `FOLLOW-1 finished only when nothing is pending and every known stream is Sealed`() {
        assertEquals(
            FollowDecision.Finished,
            followDecision(moreFrames = false, tailStates = listOf(OutputTailState.Sealed(10))),
            "nothing pending and one sealed stream is a finished tail",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = true, tailStates = listOf(OutputTailState.Sealed(10))),
            "a truncated page means more frames exist right now, whatever the tail says",
        )
    }

    @Test
    fun `FOLLOW-2 an unknown stream keeps the follower going because null is not Sealed`() {
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = false, tailStates = listOf(null)),
            "a stream whose state could not be established is NOT finished. Stopping here looks " +
                "exactly like a successfully completed follow, which is the worst failure available",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(
                moreFrames = false,
                tailStates = listOf(OutputTailState.Sealed(4), OutputTailState.Open(9)),
            ),
            "one stream still open keeps the whole follower going",
        )
    }

    @Test
    fun `FOLLOW-3 a stream never observed is not evidence that the run ended`() {
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = false, tailStates = emptyList()),
            "a follower that has read nothing has not proven the run finished. Treating an empty " +
                "history as completion would end every follow that started before the first frame",
        )
    }

    @Test
    fun `FOLLOW-4 a follower that has read NO frames can still reach a verdict`() {
        val store = SegmentOutputStore(root.resolve("follow4"))
        store.recover()
        val index = store.frameIndex()
        val reader: ObservationOutputReader = FrameIndexedObservationOutputReader(index, store, store)

        val stderr = OutputStreamId("$run/build/sh-0/stderr")

        // 1. Declared in the index, never opened in the store: the shape of a run whose steps have
        // not produced output yet. A follower listing only the streams it saw FRAMES from would see
        // nothing at all here.
        index.declareStream(stderr, OutputChannel.STDERR)
        assertEquals(
            listOf<OutputTailState?>(null),
            reader.tailStatesOf(run),
            "a declared-but-unopened stream answers null: the index knows it, the store does not. " +
                "The follower must read that as 'not established yet', never as finished",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = false, tailStates = reader.tailStatesOf(run)),
            "an unestablished stream keeps the follower going",
        )

        // 2. Opened but still empty. OBS-C3 made open() create the directory, which is what turns
        // Open(0) into a reachable state instead of a guess.
        store.open(stderr)
        assertEquals(
            listOf(OutputTailState.Open(0L)),
            reader.tailStatesOf(run),
            "an opened but empty stream is Open at zero, which is a fact a reader can rely on",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = false, tailStates = reader.tailStatesOf(run)),
        )

        // 3. Sealed, with no frame ever published. The follower may conclude.
        store.seal(stderr)
        assertEquals(
            FollowDecision.Finished,
            followDecision(moreFrames = false, tailStates = reader.tailStatesOf(run)),
            "with every declared stream sealed and nothing pending, the follower may conclude. " +
                "Without streamsOfRun this verdict was unreachable for a follower that had read " +
                "nothing, and 'read until nothing is pending' became an infinite poll of a run that " +
                "had already finished",
        )
    }

    private fun pageOf(read: ObservationOutputRead): ObservationOutputPage =
        assertInstanceOf(
            ObservationOutputRead.Page::class.java,
            read,
            "expected a page, got $read",
        ).page
}
