package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * OBS-C3: a consumer can tell "no more bytes yet" from "no more bytes ever".
 *
 * ## What this pins
 *
 * `OutputPage.next == null` says only that the reader reached the committed extent *now*. Both a
 * step printing slowly and a step that has finished look identical through that door, so `--follow`
 * has to choose between stopping too early and polling forever. [OutputTailState] is the third
 * answer, and these rows pin the three properties that make it worth trusting:
 *
 * ```text
 * OPEN WHILE ALIVE   a stream nobody has sealed is Open, even at extent zero
 * SEALED ON TERMINAL a sealed stream reports Sealed with the extent it had when it was sealed
 * SEALED IS DURABLE  a store reopened from the same root still reports Sealed
 * SEALED IS FINAL    appending after a seal fails, rather than growing a stream that said it ended
 * ```
 *
 * ## Why these are not vacuous
 *
 * Every row begins from a stream that actually holds bytes, so `Open(0)` passing over an empty
 * store would fail the row rather than satisfy it. And the whole file is killable by one production
 * mutation — deleting the marker check in `tailState` — which is what distinguishes this from the C1
 * tail row, whose defect was an ABSENT interface and therefore could not be killed by editing
 * production code at all.
 *
 * ## Fidelity
 *
 * Real store, real filesystem, real reopen. No clock, no ambient state, no port outside the store
 * being tested: the rows assert the durable marker rather than a live view of a writer, because a
 * consumer in a fresh JVM must be able to learn the answer without the producer being alive.
 */
class SegmentOutputTailStateTest {

    @TempDir
    lateinit var root: Path

    private fun store() = SegmentOutputStore(root.resolve("output-plane")).also { it.recover() }

    private fun write(stream: OutputStreamId, text: String, target: SegmentOutputStore) {
        val handle = target.open(stream)
        val reservation = handle.reserve(text.toByteArray().size)
        reservation.write(text.toByteArray())
        reservation.commit()
    }

    @BeforeEach
    fun setUp() {
        root.resolve("output-plane").toFile().deleteRecursively()
    }

    @Test
    fun `a stream nobody sealed is Open, and says how far it has got`() {
        val target = store()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "hello durable tail", target)

        val state = target.tailState(stream)

        assertEquals(
            OutputTailState.Open(18L),
            state,
            "a stream with no seal marker is OPEN, and its committedEnd must be the extent a reader " +
                "can already see",
        )
    }

    @Test
    fun `a stream that exists but has no bytes yet is Open at zero, not absent`() {
        val target = store()
        val stream = OutputStreamId("run-1/build/sh-1/stdout")
        // Opened but never written: the sink declares the stream before the first byte, so this
        // state is reachable in production and must not be confused with "I have never heard of it".
        target.open(stream)

        val state = assertInstanceOf(
            OutputTailState.Open::class.java,
            target.tailState(stream),
            "a declared-but-empty stream is OPEN at zero. Answering null here would let a consumer " +
                "that asked the wrong question look the same as one asking about a real stream.",
        )
        assertEquals(0L, state.committedEnd)
    }

    @Test
    fun `an unknown stream is null, which is not Open`() {
        val target = store()

        assertNull(
            target.tailState(OutputStreamId("run-nope/build/sh-0/stdout")),
            "a stream this store never saw must be null. Open would tell a consumer to keep tailing " +
                "output that does not exist.",
        )
    }

    @Test
    fun `sealing records the extent the stream had at that moment`() {
        val target = store()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "twenty bytes exactly", target)

        val outcome = target.seal(stream)

        assertEquals(
            SealOutcome.Sealed(20L),
            outcome,
            "the seal must record the extent the stream had WHEN it was sealed",
        )
        assertEquals(
            OutputTailState.Sealed(20L),
            target.tailState(stream),
            "a sealed stream reports SEALED with the end it was sealed at",
        )
    }

    @Test
    fun `a seal survives the store that made it being reopened`() {
        val original = store()
        val stream = OutputStreamId("run-1/build/sh-0/stderr")
        write(stream, "durable stderr", original)
        original.seal(stream)

        // A brand-new store object over the same root: what a consumer in a fresh JVM sees.
        val afterCrash = store()

        assertEquals(
            OutputTailState.Sealed(14L),
            afterCrash.tailState(stream),
            "SEALED must be a durable FACT, not a live view of a writer. If it answered from live " +
                "state it would say Open forever after the crash that produced it, and a consumer " +
                "would tail a dead run indefinitely.",
        )
    }

    @Test
    fun `sealing twice does not move the recorded end`() {
        val target = store()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "first ten bytes", target)
        target.seal(stream)

        // A resumed run re-observes the same terminal and seals again. The second seal must not
        // rewrite the fact with whatever the extent happens to be at that later moment.
        val second = target.seal(stream)

        assertEquals(
            SealOutcome.AlreadySealed(15L),
            second,
            "sealing is idempotent: the recorded end must not move",
        )
        assertEquals(OutputTailState.Sealed(15L), target.tailState(stream))
    }

    @Test
    fun `appending to a sealed stream fails instead of growing it`() {
        val target = store()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "sealed content", target)
        target.seal(stream)

        val failure = assertThrows(IllegalStateException::class.java) {
            target.open(stream).reserve(4)
        }

        assertTrue(
            failure.message!!.contains("sealed"),
            "the refusal must SAY it is sealed, because a writer that hit this needs to know the " +
                "tail is final rather than transiently unwritable. Got: ${failure.message}",
        )
        assertEquals(
            OutputTailState.Sealed(14L),
            target.tailState(stream),
            "the refused append must not have grown the stream; a Sealed that then reported more " +
                "bytes would be a promise the store had already broken",
        )
    }

    /**
     * M1-F.3 — sealing a stream nobody ever opened is NOT a refusal;
     * it is the legitimate-absence case. The previous test asserted
     * an exception; the new shape answers with [SealOutcome.NeverOpened]
     * so a stdout-only script can seal stderr silently.
     */
    @Test
    fun `sealing a stream nobody ever opened returns NeverOpened`() {
        val target = store()

        val outcome = target.seal(OutputStreamId("run-1/build/sh-9/stdout"))

        assertEquals(
            SealOutcome.NeverOpened,
            outcome,
            "M1-F.3: an unopened stream is not a refusal — sealing it is a silent no-op, " +
                "because a stdout-only script produces stderr bytes that never existed",
        )
    }

    @Test
    fun `the two channels of one operation tail independently`() {
        val target = store()
        val stdout = OutputStreamId("run-1/build/sh-0/stdout")
        val stderr = OutputStreamId("run-1/build/sh-0/stderr")
        write(stdout, "out", target)
        write(stderr, "err", target)

        // Only one of them ended. A consumer merging the two must be able to keep tailing the one
        // that is still open, which is exactly what a single boolean for "the operation finished"
        // could not express.
        target.seal(stderr)

        assertEquals(OutputTailState.Sealed(3L), target.tailState(stderr))
        assertEquals(
            OutputTailState.Open(3L),
            target.tailState(stdout),
            "OBS-C2.3 gave an operation two channel streams, so sealing one must NOT seal the other",
        )
    }
}
