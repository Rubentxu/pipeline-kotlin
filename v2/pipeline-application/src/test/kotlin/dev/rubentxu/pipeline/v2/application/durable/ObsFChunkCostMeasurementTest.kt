package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * OBS-F — what a chunk actually costs, measured instead of argued.
 *
 * ## The question this file exists to answer
 *
 * `TRANSCRIPT_LIVE_WINDOW_BYTES` is documented as "the store's per-chunk bookkeeping ... this is the
 * transaction size, and OBS-F measures the throughput/RSS trade-off against data rather than by
 * taste". This is that measurement, and it was the last thing the window's value rested on.
 *
 * ## Why the window is now only a transaction size
 *
 * The pump reads `maxOf(1, minOf(window.size, redacted.available()))`. Because it asks for what is
 * READY, a slow producer is unaffected by the window — a step printing 100 B/s commits 100-byte
 * chunks whatever the window says. The window therefore governs exactly one thing: **how many
 * durable transactions a fast producer costs**, because a full pipe makes `available()` larger than
 * the window and every read is then exactly one window.
 *
 * That is the trade-off, and both halves of it are real:
 *
 * ```text
 * a SMALL window  -> more transactions per byte -> more fsyncs, lower throughput
 * a LARGE window  -> fewer transactions per byte -> better throughput, coarser live granularity
 * ```
 *
 * ## What is asserted here is produced by production, never by this file
 *
 * An earlier draft of this file counted the writes in its own loop and then asserted that its own
 * count equalled `ceil(bytes / window)`. That is a tautology: it would have passed with the ingress
 * deleted. Every claim below is instead read back out of the durable authority:
 *
 * - the frame index holds exactly one frame per write — production's own count, not the loop's;
 * - those frames are **dense** in ordinal, so no ordinal was skipped;
 * - their byte ranges **tile the stream exactly**, contiguous from 0 with no overlap and no hole.
 *
 * That third one is the claim worth having. Two overlapping frames would mean bytes attributed
 * twice; a hole would mean bytes committed but unattributable after a crash, which is the failure
 * `SegmentFrameIndex.recoverUnframedBytes` exists to reconcile. Both are invisible to a digest and
 * invisible to a count.
 *
 * ## What is only reported
 *
 * Milliseconds and MiB/s. A millisecond threshold is a property of this machine and its
 * filesystem; asserting one would make this file a flaky gate rather than a measurement. The
 * numbers are printed so a decision can be taken against them once, deliberately.
 *
 * ## Fidelity
 *
 * This crosses the productive authority for the question — the real [RedactingOutputIngress] over a
 * real [SegmentOutputStore] on disk — and drives it the way the pump drives it, with whole-window
 * writes. It is deliberately **not** a claim about a live run's latency: that is
 * `ObsBLiveOutputIngressTest`, which holds a step on a barrier. This file asks a different question
 * and reuses no production decision to answer it.
 */
class ObsFChunkCostMeasurementTest {

    /** The bytes every candidate window has to move, so the ratios are the same comparison. */
    private val totalBytes = 8L * 1024L * 1024L

    /**
     * The candidates the plan named, measured rather than picked: 16 / 32 / 64 / 128 KiB, plus the
     * 1 KiB the branch actually ships today so the table has a current row to argue with.
     */
    private val candidateWindows =
        listOf(1 * 1024, 16 * 1024, 32 * 1024, 64 * 1024, 128 * 1024)

    @Test
    fun `one write is one frame, and the frames tile the stream exactly`(@TempDir root: Path) {
        // The table is quoted in a receipt, so the numbers are formatted under a fixed locale.
        // Default formatting emitted `0,3` under a Spanish locale, which turns a measurement into
        // a sentence fragment: `0,3` reads as a list, and `113,3` as a thousand.
        val locale = Locale.ROOT
        val report = StringBuilder()
        report.appendLine("OBS-F chunk cost — %d bytes per candidate".format(totalBytes))
        report.appendLine(
            "%10s %12s %12s %14s %10s".format(
                locale, "window", "writes", "frames/fsync", "transactions/MiB", "MiB/s",
            ),
        )

        for (window in candidateWindows) {
            val cost = measure(root.resolve("w$window"), window)
            report.appendLine(
                "%10d %12d %12d %14.1f %10.1f".format(
                    locale,
                    cost.windowBytes,
                    cost.writes,
                    cost.frames.size,
                    cost.frames.size * MIB / totalBytes,
                    cost.mibPerSecond,
                ),
            )

            // PRODUCTION says how many frames exist. This file does not get a vote.
            //
            // `toLong()` is not decoration: `List.size` is an Int and the expected count is a Long,
            // and JUnit's boxed overload compares `Long(8192)` against `Integer(8192)` as unequal.
            // The first run of this row failed on that alone, with both sides printing 8192 — a
            // RED that said nothing about the ingress, which is the exact shape of failure this
            // repository keeps paying for and cannot be read as a product defect.
            assertEquals(
                expectedFrames(window), cost.frames.size.toLong(),
                "at window=$window the ingress must have framed exactly one range per write, " +
                    "because that frame is what costs the fsync",
            )

            // Dense ordinals: nothing was skipped between the first and the last frame.
            cost.frames.forEachIndexed { i, frame ->
                assertEquals(
                    i.toLong(), frame.ordinal,
                    "at window=$window frame $i has ordinal ${frame.ordinal}; the ordinals are " +
                        "the order the ingress published and a gap would mean a frame was lost",
                )
            }

            // The ranges tile the stream: contiguous, non-overlapping, covering every byte.
            var expectedFrom = 0L
            cost.frames.forEach { frame ->
                assertEquals(
                    expectedFrom, frame.from,
                    "at window=$window a frame starts at ${frame.from} but the previous one " +
                        "ended at $expectedFrom: committed bytes with no frame are unattributable",
                )
                expectedFrom = frame.to
            }
            assertEquals(
                totalBytes, expectedFrom,
                "at window=$window the last frame ends at $expectedFrom but $totalBytes bytes " +
                    "were written",
            )

            // No byte is lost or duplicated at any window.
            assertEquals(totalBytes, cost.committed, "at window=$window the committed extent moved")
            assertEquals(
                cost.expectedDigest, cost.actualDigest,
                "at window=$window the bytes that came back are not the bytes that went in",
            )

            // The tail answers the third question the run plane cannot.
            assertEquals(
                OutputTailState.Sealed(totalBytes), cost.tailState,
                "at window=$window a sealed stream must report Sealed at its final extent",
            )
            assertNull(cost.refusal, "at window=$window the ingress refused: ${cost.refusal}")
        }

        println(report)
    }

    private fun expectedFrames(windowBytes: Int): Long =
        (totalBytes + windowBytes - 1) / windowBytes

    private fun measure(
        root: Path,
        windowBytes: Int,
    ): ChunkCost {
        val store = SegmentOutputStore(root.resolve(OutputPlaneProvider.OUTPUT_DIR))
        store.recover()
        val address = OutputStreamAddress.of("run-obs-f", "sh-0", OutputChannel.STDOUT)
        store.frameIndex().declareStream(address.stream, address.channel)
        val ingress =
            RedactingOutputIngress(store.open(address.stream), store.frameIndex(), address)

        val chunk = ByteArray(windowBytes) { (it % 251).toByte() }
        val expected = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var writes = 0L

        val startedAt = System.nanoTime()
        while (written < totalBytes) {
            // Exactly what the pump does with a full pipe: a whole window per write.
            val n = min(windowBytes.toLong(), totalBytes - written).toInt()
            ingress.write(chunk, 0, n)
            expected.update(chunk, 0, n)
            written += n
            writes++
        }
        ingress.close()
        val elapsedNanos = System.nanoTime() - startedAt

        store.seal(address.stream)

        val actual = MessageDigest.getInstance("SHA-256")
        var cursor: OutputCursor? = OutputCursor.start(address.stream)
        while (cursor != null) {
            val page =
                assertInstanceOf(
                    OutputReadResult.Page::class.java,
                    store.read(address.stream, cursor, 1 shl 20),
                )
            actual.update(page.page.bytes)
            cursor = page.page.next
        }

        val frames = store.frameIndex()
            .framesOfRun("run-obs-f", afterOrdinal = -1, limit = Int.MAX_VALUE)
        val committed = store.committedExtent(address.stream)

        return ChunkCost(
            windowBytes = windowBytes,
            writes = writes,
            frames = frames,
            committed = committed ?: -1L,
            expectedDigest = expected.digest().toHex(),
            actualDigest = actual.digest().toHex(),
            tailState = store.tailState(address.stream),
            refusal = ingress.refusal(),
            mibPerSecond = totalBytes.toDouble() / MIB / (elapsedNanos / 1_000_000_000.0),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

    private class ChunkCost(
        val windowBytes: Int,
        val writes: Long,
        /** Read back out of the durable frame index, never counted by this file. */
        val frames: List<OutputFrame>,
        val committed: Long,
        val expectedDigest: String,
        val actualDigest: String,
        val tailState: OutputTailState?,
        val refusal: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal?,
        val mibPerSecond: Double,
    )

    private companion object {
        const val MIB = 1024.0 * 1024.0
    }
}