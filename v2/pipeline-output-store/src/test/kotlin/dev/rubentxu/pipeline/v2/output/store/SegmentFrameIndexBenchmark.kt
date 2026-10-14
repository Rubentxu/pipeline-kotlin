package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.system.measureNanoTime

/**
 * R3 / OBS-3 — baseline performance of [SegmentFrameIndex].
 *
 * The roadmap mandates benchmarks for 200 MiB–1 GiB scenarios. This test
 * establishes the **baseline** before any optimization, per the operator's
 * "Decidir wakeups solo después de medir el polling ya optimizado" rule:
 * we cannot decide whether the 25 ms polling is the bottleneck until the
 * frame index itself is O(1) per operation.
 *
 * ## What this measures
 *
 * 1. **append cost**: time to write one frame after N previous frames exist.
 *    Because [SegmentFrameIndex.append] calls [SegmentFrameIndex.sealTornTail]
 *    which reads the WHOLE frames file, this is currently O(N) per call.
 * 2. **framesOfRun cost**: time to list frames after the last ordinal, after
 *    N previous frames exist. Two sub-cases: with a small `limit` (the
 *    common follow case) and with a large `limit` (a catch-up read).
 * 3. **sealTornTail cost**: the underlying whole-file read that every append
 *    pays. This is the hot loop the R3 fix targets.
 *
 * ## What this does NOT measure
 *
 * - p95 latency under concurrent readers (needs JMH; deferred).
 * - Heap/RSS for the in-memory cache (deferred; needs async-profiler).
 * - FD consumption across the recovery path (deferred).
 *
 * ## Sizing
 *
 * Sizes are deliberately modest (≤ 5 000) because the current implementation
 * is O(N) per append (it re-reads the whole log), so 100 000 frames would
 * take >10 minutes for the pre-population alone. The R3 fix should let
 * these sizes go back up; that re-measurement is the "after" run.
 *
 * ## Reproducing
 *
 * Run on the baseline (commit `b962fe74`) to get the "before" numbers, then
 * again after the R3 fix lands to get the "after" numbers. The expected
 * shape is `O(n)` for the current implementation (every call re-reads the
 * whole log) and `O(1)` for the fixed one (only the tail line is checked
 * on append, only new lines are read on framesOfRun).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SegmentFrameIndexBenchmark {

    @TempDir
    lateinit var root: Path

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `baseline — append cost grows linearly with N`() {
        val runId = "bench-append"
        val (store, index) = freshStoreAndIndex(runId)
        val sizes = listOf(100, 1_000, 5_000)
        val timings = LinkedHashMap<Int, Long>()
        for (n in sizes) {
            // Pre-populate to n frames.
            for (i in 0 until n) appendFrame(index, store, runId, i.toLong())
            // Measure one append.
            val ns = measureNanoTime {
                appendFrame(index, store, runId, n.toLong())
            }
            timings[n] = ns
        }
        report("append-after-N", timings) { it }
        // Expected shape: O(N). The R3 fix should keep the curve flat.
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `baseline — framesOfRun cost grows linearly with N`() {
        val runId = "bench-framesOfRun"
        val (store, index) = freshStoreAndIndex(runId)
        val sizes = listOf(100, 1_000, 5_000)
        val timings = LinkedHashMap<Int, Long>()
        for (n in sizes) {
            for (i in 0 until n) appendFrame(index, store, runId, i.toLong())
            // Measure one framesOfRun with a small limit (the common follow case).
            val ns = measureNanoTime {
                index.framesOfRun(runId, afterOrdinal = -1L, limit = 10)
            }
            timings[n] = ns
        }
        report("framesOfRun(limit=10)-after-N", timings) { it }
        // Expected shape: O(N) before, O(limit) after. The "after" run with
        // limit=10 should be ~constant across N.
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `baseline — framesOfRun catch-up read is O(N)`() {
        val runId = "bench-framesOfRun-full"
        val (store, index) = freshStoreAndIndex(runId)
        val sizes = listOf(100, 1_000, 5_000)
        val timings = LinkedHashMap<Int, Long>()
        for (n in sizes) {
            for (i in 0 until n) appendFrame(index, store, runId, i.toLong())
            val ns = measureNanoTime {
                index.framesOfRun(runId, afterOrdinal = -1L, limit = Int.MAX_VALUE)
            }
            timings[n] = ns
        }
        report("framesOfRun(limit=MAX)-after-N", timings) { it }
        // Expected shape: O(N) for both before and after. The R3 fix should
        // not regress the catch-up read; the gain is in the limit=10 case.
    }

    /**
     * R3 / OBS-3 step 2 (ADR-OBS-004) — the cost claim the offset index underwrites.
     *
     * Before the fix, [SegmentFrameIndex.framesOfRun] re-read the whole frames log on every
     * call, so a `limit=10` read after `N` appends cost `O(N)`. With the per-run offset index
     * the reader binary-searches the entry for the floor(afterOrdinal) frame and reads `O(L)`
     * lines forward; the duration for `limit=10` is now bounded by `L`, not by `N`.
     *
     * Comparing a 100-frame run against a 10 000-frame run is the smallest reproducer that
     * exposes the difference: pre-fix, the 10 000-frame read pays ~100× more time than the
     * 100-frame read; post-fix, both are within an order of magnitude.
     *
     * No wall-clock threshold — the comparison is on **ratios**, not milliseconds.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `after-fix — framesOfRun(K, 10) is O(L) on a 10_000-frame run, not O(file size)`() {
        val runId = "bench-framesOfRun-after-fix"
        val (store, index) = freshStoreAndIndex(runId)
        val sizes = listOf(100, 1_000, 10_000)
        val timings = LinkedHashMap<Int, Long>()
        for (n in sizes) {
            for (i in 0 until n) appendFrame(index, store, runId, i.toLong())
            val ns = measureNanoTime {
                index.framesOfRun(runId, afterOrdinal = -1L, limit = 10)
            }
            timings[n] = ns
        }
        report("after-fix framesOfRun(limit=10)-after-N", timings) { it }

        // Property check: with the fix in place, the duration is roughly constant in N because
        // the read is O(L). The pre-fix shape would show the 10_000-row duration at least 100×
        // the 100-row duration; here we only ask that the 10_000-row duration is not more than
        // 10× the 100-row duration. A failing CI box could shift this by an order of magnitude
        // on its own, so we leave generous headroom and let the report carry the precise shape.
        val small = timings.getValue(100)
        val big = timings.getValue(10_000)
        check(big <= small * 10L) {
            "framesOfRun(K, 10) on 10 000 frames took ${big / 1_000_000.0} ms vs " +
                "${small / 1_000_000.0} ms on 100 frames — the O(L) shape regressed"
        }
    }

    // ----------------------------------------------------------------- helpers

    private data class BenchRun(
        val store: SegmentOutputStore,
        val index: SegmentFrameIndex,
    )

    private fun freshStoreAndIndex(runId: String): BenchRun {
        val storeRoot = root.resolve("store-${runId}")
        storeRoot.createDirectories()
        val store = SegmentOutputStore(storeRoot)
        // Recovery is required before any append (O3). We are not measuring
        // recovery here; we are measuring the frame index hot loop.
        store.recover()
        val index = store.frameIndex()
        // Declare the stream so frames have somewhere to live.
        val stream = OutputStreamAddress.of(runId, "s0-0", OutputChannel.STDOUT).stream
        index.declareStream(stream, OutputChannel.STDOUT)
        return BenchRun(store, index)
    }

    private fun appendFrame(
        index: SegmentFrameIndex,
        store: SegmentOutputStore,
        runId: String,
        i: Long,
    ) {
        val stream = OutputStreamAddress.of(runId, "s0-0", OutputChannel.STDOUT).stream
        val handle = store.open(stream)
        val reserved = handle.reserve(1)
        reserved.write(byteArrayOf(0))
        reserved.commit()
        val to = store.committedExtent(stream) ?: 1L
        index.append(stream, OutputChannel.STDOUT, from = i, to = to)
    }

    private fun report(
        name: String,
        timings: Map<Int, Long>,
        unit: (Long) -> Long,
    ) {
        val sb = StringBuilder()
        sb.append("R3 benchmark [").append(name).append("]:\n")
        for ((n, ns) in timings) {
            val ms = unit(ns) / 1_000_000.0
            sb.append("  N=").append(n).append(": ").append("%.3f ms".format(ms)).append('\n')
        }
        sb.append("  expected shape (before fix): O(N); (after fix): O(1) for limit-bound; O(N) for catch-up.\n")
        println(sb)
        // Sanity: the timings must be > 0. We don't assert a specific value
        // because the CI box varies; the report is the deliverable.
        check(timings.values.all { it > 0L }) { "timing underflow" }
    }
}
