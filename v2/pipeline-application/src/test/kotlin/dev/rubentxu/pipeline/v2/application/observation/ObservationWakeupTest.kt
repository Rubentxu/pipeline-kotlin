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
            followDecision(moreFrames = false, tailStates = listOf(OutputTailState.Sealed(10)), runFinished = true),
            "nothing pending and one sealed stream is a finished tail",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = true, tailStates = listOf(OutputTailState.Sealed(10)), runFinished = true),
            "a truncated page means more frames exist right now, whatever the tail says",
        )
    }

    @Test
    fun `FOLLOW-2 an unknown stream keeps the follower going because null is not Sealed`() {
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = false, tailStates = listOf(null), runFinished = true),
            "a stream whose state could not be established is NOT finished. Stopping here looks " +
                "exactly like a successfully completed follow, which is the worst failure available",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(
                moreFrames = false,
                tailStates = listOf(OutputTailState.Sealed(4), OutputTailState.Open(9)),
                runFinished = true,
            ),
            "one stream still open keeps the whole follower going",
        )
    }

    @Test
    fun `FOLLOW-3 a stream never observed is not evidence that the run ended`() {
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = false, tailStates = emptyList(), runFinished = false),
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
            followDecision(moreFrames = false, tailStates = reader.tailStatesOf(run), runFinished = true),
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
            followDecision(moreFrames = false, tailStates = reader.tailStatesOf(run), runFinished = true),
        )

        // 3. Sealed, with no frame ever published. The follower may conclude.
        store.seal(stderr)
        assertEquals(
            FollowDecision.Finished,
            followDecision(moreFrames = false, tailStates = reader.tailStatesOf(run), runFinished = true),
            "with every declared stream sealed and nothing pending, the follower may conclude. " +
                "Without streamsOfRun this verdict was unreachable for a follower that had read " +
                "nothing, and 'read until nothing is pending' became an infinite poll of a run that " +
                "had already finished",
        )
    }

    /**
     * **UAT-R1-01** — `sh("true")`, sin stdout/stderr, termina y su follower conoce el fin.
     *
     * A silent step owns no stream and no frame, so the output plane answers an EMPTY tail list for
     * it. Until the run-finished fact became an argument, that empty list was the whole evidence
     * available and the answer was `ReadAgain` forever: a follow on a finished, successful,
     * completely silent run could not return. Lazy stream declaration turned that from a corner case
     * into the common one — every step that prints nothing now hits it.
     *
     * The other wrong answer is the one this row exists to rule out — declare a stream anyway so the
     * follower has something to seal. That invents an observation to buy a termination, which is what
     * `ADR-M1 §D2` forbids, and it is worse than hanging: it makes a step that printed nothing
     * indistinguishable from a step that printed something a reader could later look up.
     *
     * Source of the row: OBS-R1 mandate §1.5. An earlier revision of this file asserted the row did
     * not exist, after a search of `docs/` came back empty. The rows were in the mandate and not in
     * the tree, so that search could not have found them, and the conclusion drawn from it was wrong.
     *
     * HF0 Pure Contract. [followDecision] reads no clock, no filesystem and no ambient state, so
     * driving it directly is the faithful level and not a reimplementation of the decision.
     *
     * **Scope of this row, stated plainly:** it discharges the DECISION half of UAT-R1-01. It does
     * NOT discharge the run half — that needs an installed pipeline whose silent step terminates a
     * real follower, and that is tracked separately as NOT_RUN. `ObsE5ObserveFollowTest` covers the
     * CLI path with an in-process lane; neither is a real process.
     *
     * Mutations: FOLLOW-M1 — answer `Finished` from the empty-tails arm regardless of [runFinished];
     * killed by FOLLOW-3 alone. FOLLOW-M2 — restore the old unconditional `tailStates.isEmpty()` arm;
     * killed by the first assertion of THIS row, and it is the mutation that re-introduces the hang.
     * FOLLOW-M3 — promote [runFinished] to a conjunct on every case (`!runFinished -> ReadAgain`
     * before the sealed test); killed by FOLLOW-6. That mutation is not a simplification, it is the
     * defect this section's rejection describes, and it is why FOLLOW-6 exists.
     */
    @Test
    fun `FOLLOW-5 a run that finished without writing a byte still ends its follower`() {
        assertEquals(
            FollowDecision.Finished,
            followDecision(moreFrames = false, tailStates = emptyList(), runFinished = true),
            "a run that finished without writing a byte IS a finished console. The empty tail list is " +
                "the absence of observations, never evidence that the run is still going",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = false, tailStates = emptyList(), runFinished = false),
            "and the SAME empty list with the run still running keeps reading, which is what makes the " +
                "previous row a fact about the run rather than about its silence",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            followDecision(moreFrames = true, tailStates = emptyList(), runFinished = true),
            "frames pending right now outrank every other fact, terminal run included",
        )
    }

    /**
     * The run-finished fact rescues the empty case and NOTHING ELSE.
     *
     * This row exists because the first version of the fix got it wrong in the direction that looks
     * stricter, and a passing suite did not catch it for a day: `!runFinished -> ReadAgain` placed
     * BEFORE the sealed test made run completion a conjunct on every decision. It kept FOLLOW-5 green
     * — the row it was written for — while silently breaking `ObsE5ObserveFollowTest.FOLLOW-2`, a
     * console-only lane with no event plane, which then could never end at all.
     *
     * So the claim is two-sided and both halves are pinned here: the run fact is REQUIRED when the
     * output plane has nothing, and IGNORED when sealing already answered. A one-sided test would
     * have accepted either error.
     *
     * HF0 Pure Contract, same level as FOLLOW-5. `ObsE5ObserveFollowTest.FOLLOW-2` covers the same
     * property one level up, through `MainObserveCli.follow`, and both are kept: the unit row states
     * the rule, the integration row states that the CLI actually reaches it.
     *
     * Mutation FOLLOW-M3: promote `runFinished` to a conjunct — killed by the first assertion.
     */
    @Test
    fun `FOLLOW-6 a sealed stream ends the output lane without asking whether the run ended`() {
        assertEquals(
            FollowDecision.Finished,
            followDecision(moreFrames = false, tailStates = listOf(OutputTailState.Sealed(7)), runFinished = false),
            "sealing is the output lane's OWN authority and it is not asked to prove anything about " +
                "the run. Requiring run completion here would make --view console --follow " +
                "unterminatable on a console-only lane, which has no event store to ask and can only " +
                "ever answer 'I do not know'",
        )
        assertEquals(
            FollowDecision.Finished,
            followDecision(moreFrames = false, tailStates = listOf(OutputTailState.Sealed(7)), runFinished = true),
            "and with the run finished it is the same answer, because ReachedSealedOutput and " +
                "ReachedRunFinish are DIFFERENT cases and neither implies the other",
        )
    }

    private fun pageOf(read: ObservationOutputRead): ObservationOutputPage =
        assertInstanceOf(
            ObservationOutputRead.Page::class.java,
            read,
            "expected a page, got $read",
        ).page
}
