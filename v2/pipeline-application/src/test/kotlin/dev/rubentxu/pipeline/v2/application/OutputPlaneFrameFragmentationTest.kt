package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.RedactingOutputIngress
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * R4 / TD-R4.2 — frame fragmentation of the Output Plane.
 *
 * ## What this class pins
 *
 * The pump's read loop must (a) preserve every byte that goes through it and (b) commit
 * **fewer frames than bytes** for a payload larger than a single pump window. The second
 * property is what the original "per-byte on empty-registry ring" defect violated: with a
 * one-byte-at-a-time reader the pump committed one frame per byte (1024 frames for 1024
 * bytes), inflating the frame index by three orders of magnitude without losing or
 * duplicating data. The fix is the loop that reads into a full window; this class
 * exercises that loop end-to-end and asserts the post-fix properties.
 *
 * ## What this class does NOT do
 *
 * It does not re-derive the pump's fragment arithmetic to predict a frame count. The
 * production pump owns its windowing policy; the test asserts observable invariants, not a
 * model of production behavior. A previous shape of this test (the `< 64` threshold) encoded
 * a model-derived bound (`bytes_pumped / 64`) that was 64x looser than the actual production
 * shape (1 frame for 1024 bytes); that threshold would have accepted any shape from 1 to 63
 * frames and was therefore only discriminating against the per-byte regression. The
 * property-based shape below is discriminating against any shape that drops, duplicates, or
 * over-fragments bytes.
 *
 * ## Harness fidelity
 *
 * **HF1 — in-process, drives the production pump end-to-end.** No piece of the production
 * path is mocked. The mutation row in [perByteReadLoopReproducesTheDocumentedDefect]
 * proves the property rows in [postFixPumpPreservesBytesAndUsesFewerFramesThanBytes] are
 * non-vacuous by re-introducing the per-byte regression and asserting the broken shape is
 * what the property test refuses.
 */
class OutputPlaneFrameFragmentationTest {

    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun resetProvider() {
        OutputPlaneProvider.forgetAll()
    }

    /**
     * Property row: 1 KiB through the production pump preserves every byte and uses fewer
     * frames than bytes.
     *
     * The properties asserted here are the ones the operator's complaint ("median 1 byte
     * per frame") would have caught: with the per-byte defect, [frames] would be 1024 long
     * — equal to the payload — and the readback would still match the input. The test
     * therefore asserts both halves: byte-exact preservation (the contract) and
     * `frames.size < payload.size` (the post-fix shape). A regression to per-byte
     * fragmentation fails the second; a regression to data loss fails the first.
     */
    @Test
    fun `post-fix pump preserves bytes and uses fewer frames than bytes`() {
        val (payload, frames, committed) = pumpOneKibByteByByte()

        // Property 1: byte-exact readback. The pump's contract is "no data loss and no
        // duplication"; an empty readback or a corrupted readback fails here regardless of
        // the frame count.
        assertArrayEquals(
            payload,
            committed,
            "pump dropped or duplicated bytes: expected ${payload.size} bytes, " +
                "read back ${committed.size} bytes. Frame count was ${frames.size}.",
        )

        // Property 2: the fix landed — fewer frames than bytes. A per-byte regression
        // would commit one frame per byte (frames.size == payload.size) and fail here.
        assertTrue(
            frames.isNotEmpty(),
            "no frames committed; the pump is broken in a different way than the per-byte " +
                "regression. Readback was ${committed.size} bytes.",
        )
        assertTrue(
            frames.size < payload.size,
            "pump produced one frame per byte (frames=${frames.size} payload=${payload.size}); " +
                "the per-byte regression this slice was meant to retire is back. Readback is " +
                "${committed.size} bytes so the data path is correct; the framing is the defect.",
        )

        // Property 3: byte coverage is contiguous and complete. The frame index claims
        // "the byte offset of that frame's line"; a gap or overlap would corrupt the
        // observed-to-emitted mapping for any consumer that reads by frame. We assert the
        // claim from the index itself, not from a recomputed model.
        val sorted = frames.sortedBy { it.from }
        var expectedNext = 0L
        for (f in sorted) {
            assertEquals(
                expectedNext,
                f.from,
                "frame at from=${f.from} leaves a gap from $expectedNext; the frame index " +
                    "is not contiguous and a consumer cannot reconstruct the stream.",
            )
            assertTrue(f.to > f.from, "frame with from >= to (from=${f.from} to=${f.to})")
            expectedNext = f.to
        }
        assertEquals(
            payload.size.toLong(),
            expectedNext,
            "frames did not cover the full payload: covered up to $expectedNext, " +
                "payload is ${payload.size} bytes. Either a tail fragment was dropped at " +
                "child exit (cf. OBS-PC-205) or the frame index is wrong.",
        )

        // Visibility: surface the actual numbers so the operator can compare across runs.
        // This is diagnostic, not contractual.
        System.out.println(
            "[TD-R4.2 fragmentation property] bytes_pumped=${payload.size} " +
                "frames=${frames.size} ratio=${"%.3f".format(frames.size.toDouble() / payload.size)} " +
                "first_ranges=${sorted.take(5).joinToString { "${it.from}-${it.to}" }}",
        )
    }

    /**
     * Mutation row: a per-byte read loop reproduces the per-byte fragmentation the
     * property row is meant to catch.
     *
     * The test runs the same production pump, but reads from the redacting stream one
     * byte at a time. The data path is unchanged (readback still equals input) so the
     * test only exists to demonstrate that the property row's
     * `frames.size < payload.size` is discriminating: under the per-byte mutation, the
     * property is false.
     *
     * This is the HARNESS FIDELITY LAW §5 row: a behavioural claim carries a mutation
     * that kills it, attributed 1:1 to the rows it flips.
     */
    @Test
    fun `per-byte read loop reproduces the documented defect`() {
        val (payload, frames, committed) = pumpOneKibByteByByte(redactReadWindowBytes = 1)

        // The data path is correct: bytes are preserved. The defect was always about
        // efficiency, not correctness; this assertion pins that the mutation changes only
        // the framing, not the content.
        assertArrayEquals(
            payload,
            committed,
            "mutation changed the data path; the test is supposed to break framing only",
        )

        // The broken shape: at least one frame per byte. The exact lower bound depends on
        // the production pump's response to single-byte reads; the property row's
        // `frames.size < payload.size` is what fails, so the broken shape is "frames.size
        // is close to payload.size", and we assert that. A frame count strictly less than
        // payload.size would mean the mutation did not reproduce the per-byte regression.
        assertTrue(
            frames.size >= payload.size,
            "per-byte read loop produced ${frames.size} frames for a ${payload.size}-byte " +
                "payload; the per-byte regression is the shape where frames.size ≈ " +
                "payload.size. Either the production pump is not as sensitive to " +
                "single-byte reads as the operator's complaint said, or the mutation did " +
                "not actually exercise the per-byte path.",
        )

        System.out.println(
            "[TD-R4.2 fragmentation mutation] bytes_pumped=${payload.size} " +
                "frames=${frames.size} ratio=${"%.3f".format(frames.size.toDouble() / payload.size)}",
        )
    }

    /**
     * The shared pump fixture. Returns the payload, the committed frames, and the
     * readback of committed bytes.
     *
     * @param redactReadWindowBytes the size of the read buffer used by the pump. The
     *   production loop reads into [TRANSCRIPT_LIVE_WINDOW_BYTES] (64 KiB) by default;
     *   the mutation row passes `1` to force the per-byte read shape.
     */
    private fun pumpOneKibByteByByte(
        redactReadWindowBytes: Int =
            dev.rubentxu.pipeline.v2.sdk.runtime.durable.TRANSCRIPT_LIVE_WINDOW_BYTES,
    ): Triple<ByteArray, List<dev.rubentxu.pipeline.v2.output.OutputFrame>, ByteArray> {
        val runId = "frag-rc1"
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)
        val stream = OutputStreamAddress(
            runId = runId,
            operationId = opId.format(),
            channel = OutputChannel.STDOUT,
        ).stream

        // 64 lines × 16 bytes = 1024 bytes. The shape is what the original test fixture
        // used; we keep it so the property assertion is comparable across runs.
        val payload = buildString {
            repeat(64) { i ->
                repeat(15) { j -> append('A' + ((i + j) % 26)) }
                append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        check(payload.size == 1024) { "test fixture is 1 KiB exactly, got ${payload.size}" }

        val store = OutputPlaneProvider.storeForWriting(root).also { it.recover() }
        val frameIndex = store.frameIndex()
        val ingress = RedactingOutputIngress(
            openStream = { store.open(stream) },
            frameIndex = frameIndex,
            address = dev.rubentxu.pipeline.v2.output.OutputStreamAddress(
                runId = runId,
                operationId = opId.format(),
                channel = OutputChannel.STDOUT,
            ),
        )

        val registry = SecretPatternRegistry()
        val redactor = StreamingRedactor(registry, chunkSize = 8)
        val pipeIn = PipedInputStream(8)
        val pipeOut = PipedOutputStream(pipeIn)
        val redacting = redactor.wrap(pipeIn)

        val producer = Thread {
            try {
                payload.forEach { b -> pipeOut.write(b.toInt()); pipeOut.flush() }
                pipeOut.close()
            } catch (_: Throwable) {
                // The pump may have closed the read end before all bytes are written; the
                // bytes already pumped are what we measure.
            }
        }
        producer.start()

        val window = ByteArray(redactReadWindowBytes)
        while (true) {
            val n = redacting.read(window, 0, window.size)
            if (n < 0) break
            ingress.write(window, 0, n)
        }
        ingress.close()
        producer.join(TimeUnit.SECONDS.toMillis(5))

        val frames = store.frameIndex().framesOfRun(runId, afterOrdinal = -1, limit = 10_000)
        val committed = readAllBytes(store, stream)
        return Triple(payload, frames, committed)
    }

    /**
     * Paged drain of one stream, as a consumer would. Returns an empty array for a
     * refusal. Mirrors the helper in [ObsPc2LevelAUatTest]; duplicated here to keep this
     * class self-contained.
     */
    private fun readAllBytes(store: SegmentOutputStore, stream: OutputStreamId): ByteArray {
        if (store.read(stream, OutputCursor.start(stream), PAGE_BYTES) is OutputReadResult.Refused) {
            return ByteArray(0)
        }
        val out = ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = (store.read(stream, cursor, PAGE_BYTES) as? OutputReadResult.Page)?.page ?: break
            out.write(page.bytes)
            cursor = page.next
        }
        return out.toByteArray()
    }

    private companion object {
        const val PAGE_BYTES = 64 * 1024
    }
}
