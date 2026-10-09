package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * OBS-E2: the loop that turns a run's output into records for a consumer.
 *
 * ## Harness fidelity
 *
 * HF2 — a real `SegmentOutputStore` and a real frame index, published through the production order and
 * read back through the OBS-D2 reader by the follower under test. The rows do not build the records
 * they assert on, and they do not stub the store: a sealed refusal row (REFUSE-1) uses a read-port
 * double and says so, because what it adds is the follower's obligation to PROPAGATE.
 *
 * ## What this pins
 *
 * ```text
 * REPLAY-1  a drain returns every pending record, in observation order
 * REPLAY-2  resuming from the returned ordinal continues without a gap or a repeat
 * REPLAY-3  a run that is still open reports ReadAgain, never Finished
 * REPLAY-4  the record bound TRUNCATES and says so; it is not silently complete
 * FILTER-1  a query filters the result without shortening the drain
 * REFUSE-1  a refusal is propagated rather than answered with an empty replay
 * ```
 *
 * ## Mutation
 *
 * - `M-E3` (the bound is a truncation) → return `truncated = false` when the record bound stopped the
 *   drain. This is the plausible wrong fix: the caller gets a partial answer with no way to tell.
 * - `M-E4` (resume is exact) → resume from `lastOrdinal + 1`, which skips a record.
 */
class ObservationOutputFollowerTest {

    @TempDir
    lateinit var root: Path

    private val run = "run-obse2"

    private fun newFollower(): Triple<SegmentOutputStore, ObservationOutputReader, ObservationOutputFollower> {
        val store = SegmentOutputStore(root.resolve("output-plane"))
        store.recover()
        val reader: ObservationOutputReader = FrameIndexedObservationOutputReader(store.frameIndex(), store, store)
        return Triple(store, reader, ObservationOutputFollower(reader, frameLimit = 4))
    }

    private fun publish(store: SegmentOutputStore, stream: OutputStreamId, channel: OutputChannel, text: String) {
        store.frameIndex().declareStream(stream, channel)
        val payload = text.toByteArray(Charsets.UTF_8)
        val from = store.committedExtent(stream) ?: 0L
        val reservation = store.open(stream).reserve(payload.size)
        reservation.write(payload)
        reservation.commit()
        store.frameIndex().append(stream, channel, from, from + payload.size)
    }

    private fun stdout() = OutputStreamId("$run/build/sh-0/stdout")
    private fun stderr() = OutputStreamId("$run/build/sh-0/stderr")

    private fun complete(result: ObservationReplayResult): ObservationReplayResult.Complete =
        assertInstanceOf(
            ObservationReplayResult.Complete::class.java,
            result,
            "expected a complete replay, got $result",
        )

    @Test
    fun `REPLAY-1 a drain returns every pending record in observation order`() {
        val (store, _, follower) = newFollower()
        publish(store, stdout(), OutputChannel.STDOUT, "alpha\n")
        publish(store, stderr(), OutputChannel.STDERR, "beta\n")
        publish(store, stdout(), OutputChannel.STDOUT, "gamma\n")

        val result = complete(follower.replay(run))

        assertEquals(
            listOf("alpha\n", "beta\n", "gamma\n"),
            result.records.map { it.text },
            "the drain must cover both channels in the order PipelineK published them",
        )
        assertEquals(
            2,
            result.lastOrdinal,
            "ordinals are zero-based: three frames are 0, 1 and 2, so the resume position is 2",
        )
        assertFalse(result.truncated, "three records is not a truncation")
    }

    @Test
    fun `REPLAY-2 resuming from the returned ordinal continues without a gap or a repeat`() {
        val (store, _, follower) = newFollower()
        repeat(9) { publish(store, stdout(), OutputChannel.STDOUT, "line $it\n") }

        val first = complete(follower.replay(run))
        assertEquals(9, first.records.size, "premise broken: the first drain did not see everything")

        val second = complete(follower.replay(run, afterOrdinal = first.lastOrdinal))
        assertTrue(
            second.records.isEmpty(),
            "a second drain from the end must return nothing. Got ${second.records.map { it.text }}",
        )
        assertEquals(
            first.lastOrdinal,
            second.lastOrdinal,
            "and it must hand back the position it was given, not one past it",
        )
    }

    @Test
    fun `REPLAY-3 a run that is still open reports ReadAgain, never Finished`() {
        val (store, _, follower) = newFollower()
        publish(store, stdout(), OutputChannel.STDOUT, "still running\n")

        val result = complete(follower.replay(run))

        assertEquals(
            FollowDecision.ReadAgain,
            result.decision,
            "the step has not reached its terminal, so nothing is sealed. Reporting Finished here " +
                "would end a follow of a run that is still writing — which is indistinguishable, from " +
                "the outside, from a correctly completed one",
        )
    }

    @Test
    fun `REPLAY-4 the record bound truncates and says so`() {
        val (store, _, follower) = newFollower()
        repeat(10) { publish(store, stdout(), OutputChannel.STDOUT, "line $it\n") }

        val result = complete(follower.replay(run, limit = ObservationReplayLimit(records = 6)))

        assertEquals(6, result.records.size)
        assertTrue(
            result.truncated,
            "stopping at the bound is a PARTIAL answer and the caller must be able to see that. " +
                "A truncated replay reported as complete is the one failure this flag exists to stop",
        )
        assertEquals(
            FollowDecision.ReadAgain,
            result.decision,
            "a truncated drain is by definition not the end of the tail",
        )

        // And resuming really does continue from where it stopped, without a repeat.
        val rest = complete(follower.replay(run, afterOrdinal = result.lastOrdinal))
        assertEquals(
            listOf("line 6\n", "line 7\n", "line 8\n", "line 9\n"),
            rest.records.map { it.text },
        )
    }

    @Test
    fun `FILTER-1 a query filters the result without shortening the drain`() {
        val (store, _, follower) = newFollower()
        publish(store, stdout(), OutputChannel.STDOUT, "alpha\n")
        publish(store, stderr(), OutputChannel.STDERR, "beta\n")
        publish(store, stdout(), OutputChannel.STDOUT, "gamma\n")

        val compiled = compileQuery(ObservationQuery(channels = setOf(OutputChannel.STDERR)))
        assertTrue(compiled is SelectorCompileResult.Ok)
        val result = complete(follower.replay(run, query = (compiled as SelectorCompileResult.Ok).value))

        assertEquals(listOf("beta\n"), result.records.map { it.text })
        assertEquals(
            2,
            result.lastOrdinal,
            "the drain must still traverse the index to its end even though only one record survived " +
                "the filter: filtering is read-side, so a reader counting KEPT records would be " +
                "counting a view of the run instead of the run",
        )
    }

    @Test
    fun `REFUSE-1 a refusal is propagated rather than answered with an empty replay`() {
        val (store, _, _) = newFollower()
        publish(store, stdout(), OutputChannel.STDOUT, "text that exists but cannot be read\n")

        val refusing = ObservationOutputFollower(
            FrameIndexedObservationOutputReader(
                store.frameIndex(),
                object : OutputReadPort {
                    override fun committedExtent(stream: OutputStreamId): Long? = 0L
                    override fun read(
                        stream: OutputStreamId,
                        cursor: OutputCursor,
                        maxBytes: Int,
                    ) = OutputReadResult.Refused(
                        OutputRefusal.UnknownStream(stream),
                    )

                    override fun readRange(
                        stream: OutputStreamId,
                        from: Long,
                        to: Long,
                    ) = OutputReadResult.Refused(
                        OutputRefusal.UnknownStream(stream),
                    )
                },
                store,
            ),
            frameLimit = 4,
        )

        val refused = assertInstanceOf(
            ObservationReplayResult.Refused::class.java,
            refusing.replay(run),
            "a store that will not answer must NOT look like a run that produced no output: a replay " +
                "reported as empty here would tell an agent that the build printed nothing",
        )
        assertInstanceOf(OutputRefusal.UnknownStream::class.java, refused.reason)
    }
}
