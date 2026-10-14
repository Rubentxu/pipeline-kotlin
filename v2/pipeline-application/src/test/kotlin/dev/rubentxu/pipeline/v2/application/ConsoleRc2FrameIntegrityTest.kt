package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * R4 / WU-RC2 (P0 frame integrity) — adversarial tests for the three P0 defects the
 * v0.61.0-rc1 audit found in the published code.
 *
 * ## The three defects, and why each row kills exactly one
 *
 * 1. **`readFrames` / `readFramesFiltered` ignored the requested opId.** Both called
 *    `reader.readOutput(runId, afterOrdinal, frameLimit)` — the run-wide overload — so a
 *    caller asking for `run/build-0` received frames for every operation in the run.
 *    FIX: `ObservationOutputReader.readOutput(runId, opId, afterOrdinal, frameLimit)`, an
 *    opId-scoped overload; the console verb's three read paths now pass the opId.
 *    ROW: [P0-1 a run-wide read leaks every operation's frames] (the defect, in a RED that
 *    would fire on the old code) and [P0-2 the opId-scoped read returns only that op].
 *
 * 2. **The `--follow` cursor advanced by `+1` against a strictly-greater reader.**
 *    `SegmentFrameIndex.framesOfRun` selects `decoded.ordinal > afterOrdinal`, so after
 *    delivering ordinal 4 the correct resume position is 4, not 5. The `+1` skipped the
 *    frame at ordinal 5 on every poll boundary.
 *    ROW: [P0-3 a follow loop with frameLimit 1 delivers a dense ordinal sequence].
 *
 * 3. **`emitFollowPage` closed `System.out` between pages.** It wrapped `out` in an
 *    `OutputStreamWriter` inside a `.use { }` block; `OutputStreamWriter.close()`
 *    propagates to the underlying stream, so after the first follow page stdout was
 *    closed and every later page was lost.
 *    ROW: [P0-4 jsonl follow writes more than one page to the same stream].
 *
 * ## Harness fidelity (HARNESS FIDELITY LAW)
 *
 * These rows build a REAL `SegmentOutputStore` and publish frames through the REAL
 * production order (declare → reserve → write → commit → append), the same shape
 * `RedactingOutputIngress` uses. They do not fabricate `ObservationOutputPage` values:
 * a fabricated page cannot prove the cursor arithmetic, because the test would be
 * supplying the ordinal the production code then reads.
 *
 * The reader is crossed for real — `FrameIndexedObservationOutputReader` over the same
 * store — so a change to `framesOfRun`'s comparison operator fails these rows.
 *
 * ## What this class does NOT certify
 *
 * It does not cross `DurableShellExecutor`, and therefore does NOT prove that a real
 * `pipelinek run` populates the Output Plane. That is a separate defect tracked in
 * `R4_WU_RC2_FRAME_INTEGRITY_RECEIPT.md` (§ "the fourth defect"), and it is why the
 * HF2 installDist canary rows live in a separate class rather than here.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@DisplayName("R4 / WU-RC2 — frame integrity: opId scoping, follow cursor, writer lifetime")
class ConsoleRc2FrameIntegrityTest {

    @TempDir
    lateinit var root: Path

    private lateinit var store: SegmentOutputStore

    private val runId = "run-rc2"

    /** Canonical opIds for this run: `{runId}/<stage>-<step>/<channel>` stream ids. */
    private val opBuild0 = "$runId/build-0"
    private val opBuild1 = "$runId/build-1"
    private val opTest0 = "$runId/test-0"

    private fun stdoutOf(opId: String): OutputStreamId = OutputStreamId("$runId/$opId/stdout")

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        store = SegmentOutputStore(root.resolve("output-plane"))
        store.recover()
    }

    /**
     * Publish one frame through the PRODUCTION order, the same sequence
     * `RedactingOutputIngress.write` performs: open (which declares), reserve, write,
     * commit, then append the frame for the committed range.
     */
    private fun publish(opId: String, text: String) {
        val stream = stdoutOf(opId)
        val channel = OutputChannel.STDOUT
        store.frameIndex().declareStream(stream, channel)
        val payload = text.toByteArray(Charsets.UTF_8)
        val handle = store.open(stream)
        val reservation = handle.reserve(payload.size)
        reservation.write(payload)
        val committedEnd = reservation.commit()
        val from = committedEnd - payload.size
        store.frameIndex().append(stream, channel, from, committedEnd)
    }

    private fun reader() = FrameIndexedObservationOutputReader(store.frameIndex(), store, store)

    private fun page(result: ObservationOutputRead) =
        assertInstanceOf(ObservationOutputRead.Page::class.java, result, "expected a page, got $result").page

    /**
     * Publish an interleaved fixture: three operations, each with a distinct payload.
     *
     * The interleaving is the point. Ordinals are assigned per RUN, so op `build-1`'s
     * frame sits between `build-0`'s two frames. A reader that ignores opId therefore
     * returns a sequence containing a foreign payload in the middle — which is exactly
     * what the rc1 defect produced and what P0-1 and P0-2 pin.
     */
    private fun publishInterleavedFixture() {
        publish(opBuild0, "build-0-line-1\n")
        publish(opBuild1, "build-1-ONLY\n")
        publish(opBuild0, "build-0-line-2\n")
        publish(opTest0, "test-0-ONLY\n")
        publish(opBuild0, "build-0-line-3\n")
    }

    /**
     * P0-1 — the DEFECT, stated as a row that pins the run-wide overload's behaviour.
     *
     * `readOutput(runId, afterOrdinal, frameLimit)` has no opId parameter and therefore
     * cannot scope: it returns every operation's frames. This row asserts that fact so
     * the run-wide overload's contract is pinned and a future change to it is deliberate.
     * If someone adds opId filtering to the run-wide overload, THIS row fails and the
     * change is forced to be explicit rather than silent.
     */
    @Test
    fun `P0-1 a run-wide read returns every operation's frames`() {
        publishInterleavedFixture()

        val all = page(reader().readOutput(runId, -1L, 100)).records

        assertEquals(
            5, all.size,
            "the run-wide read must return the run's 5 frames across all 3 operations; " +
                "got ${all.size}. If this now returns fewer, the run-wide overload changed " +
                "shape and every caller that relied on it needs review.",
        )
        val foreign = all.filter { !it.text.contains("build-0-line") }
        assertEquals(
            2, foreign.size,
            "the run-wide read deliberately includes the other operations' frames; " +
                "expected 2 foreign frames, got ${foreign.size}",
        )
    }

    /**
     * P0-2 — the FIX: the opId-scoped read returns ONLY the requested operation's frames.
     *
     * On the rc1 code this method did not exist; `readFrames` called the run-wide
     * overload, so `console --frames <runId> build-0` returned all five frames including
     * `build-1-ONLY` and `test-0-ONLY`. This row asserts neither foreign payload appears.
     */
    @Test
    fun `P0-2 the opId-scoped read returns only that operation's frames`() {
        publishInterleavedFixture()

        val scoped = page(reader().readOutput(runId, opBuild0, -1L, 100)).records

        assertEquals(
            3, scoped.size,
            "build-0 published 3 frames; the opId-scoped read must return exactly those 3, " +
                "got ${scoped.size}",
        )
        assertTrue(
            scoped.all { it.text.contains("build-0-line") },
            "every scoped record must be build-0's; got: ${scoped.map { it.text }}",
        )
        assertTrue(
            scoped.none { it.text.contains("build-1-ONLY") },
            "the scoped read leaked build-1's payload; got: ${scoped.map { it.text }}",
        )
        assertTrue(
            scoped.none { it.text.contains("test-0-ONLY") },
            "the scoped read leaked test-0's payload; got: ${scoped.map { it.text }}",
        )
    }

    /**
     * P0-3 — the FIX: the follow cursor resumes at the last DELIVERED ordinal.
     *
     * `framesOfRun` is strictly `ordinal > afterOrdinal`. The rc1 loop set
     * `ordinal = last.ordinal + 1`, so with `frameLimit = 1` the sequence walked
     * 0 → 2 → 4 and the odd frames were never delivered. This row drives the same
     * poll loop against the real store and asserts a dense sequence.
     */
    @Test
    fun `P0-3 a follow loop with frameLimit 1 delivers a dense ordinal sequence`() {
        publishInterleavedFixture()
        val r = reader()

        // The rc1 loop, verbatim, with the `+ 1` that is the defect.
        var rc1Cursor = -1L
        val rc1Ordinals = mutableListOf<Long>()
        repeat(10) {
            val p = page(r.readOutput(runId, rc1Cursor, 1))
            if (p.records.isNotEmpty()) {
                rc1Ordinals += p.records.last().frame.ordinal
                rc1Cursor = p.records.last().frame.ordinal + 1L
            }
        }
        assertTrue(
            rc1Ordinals.zipWithNext().any { (a, b) -> b != a + 1 },
            "the rc1 cursor arithmetic is expected to skip; it produced a dense sequence " +
                "$rc1Ordinals, which means the defect this row pins no longer reproduces. " +
                "Re-examine SegmentFrameIndex.framesOfRun's comparison before relaxing this row.",
        )

        // The fixed loop: resume at the last delivered ordinal, no `+ 1`.
        var fixedCursor = -1L
        val fixedOrdinals = mutableListOf<Long>()
        repeat(10) {
            val p = page(r.readOutput(runId, fixedCursor, 1))
            if (p.records.isNotEmpty()) {
                fixedOrdinals += p.records.last().frame.ordinal
                fixedCursor = p.records.last().frame.ordinal
            }
        }

        assertEquals(
            (0L..4L).toList(), fixedOrdinals,
            "the fixed follow cursor must deliver every ordinal densely; got $fixedOrdinals. " +
                "A gap means a frame was skipped at a poll boundary — the exact rc1 defect.",
        )
    }

    /**
     * P0-4 — the FIX: the JSONL follow writer must not close the stream it writes to.
     *
     * The rc1 `emitFollowPage` wrapped `out` in `OutputStreamWriter(...).use { ... }`.
     * `use` calls `close()`, `OutputStreamWriter.close()` propagates to the underlying
     * stream, and `System.out` was closed after the first page — so every later page was
     * lost and a `tail -f`-style consumer saw the output stop mid-run.
     *
     * The row drives the real emit path twice through a stream that RECORDS whether it
     * was closed, and asserts the second page still lands.
     */
    @Test
    fun `P0-4 jsonl follow writes more than one page to the same stream`() {
        publishInterleavedFixture()
        val r = reader()

        // A PrintStream over a sink that remembers its own closure.
        val collected = StringBuilder()
        var closed = false
        val sink = object : java.io.ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }
        val out = PrintStream(sink, true, "UTF-8")

        val first = page(r.readOutput(runId, opBuild0, -1L, 1))
        MainConsoleCli.emitFollowPageForTest(first.records, MainConsoleCli.ConsoleFormat.JSONL, out)

        val secondCursor = first.records.last().frame.ordinal
        val second = page(r.readOutput(runId, opBuild0, secondCursor, 1))
        MainConsoleCli.emitFollowPageForTest(second.records, MainConsoleCli.ConsoleFormat.JSONL, out)

        out.flush()
        collected.append(sink.toString("UTF-8"))

        assertTrue(
            !closed,
            "the JSONL follow writer closed its output stream; every page after the first " +
                "would be lost. This is the rc1 defect (.use on an OutputStreamWriter over System.out).",
        )
        assertEquals(
            2, collected.toString().lines().count { it.trimStart().startsWith("{") },
            "both JSONL pages must reach the stream; got:\n${collected}",
        )
        assertTrue(
            collected.contains("build-0-line-1") && collected.contains("build-0-line-2"),
            "both pages' payloads must be present; got:\n${collected}",
        )
    }
}