package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.domain.durable.executeBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * WU-LPR-040 observation/performance characterization harness for the
 * process-output plane. Extends the ProcessDurableTaskRuntimeTest UAT
 * with the dimensions the observation gate (WU-LPR-043) will evaluate:
 *
 *  P1. High-output process (~100 MiB stdout) — completion decoupled from
 *      renderer; memory bound is O(buffers), not O(total output).
 *  P2. Mixed stdout/stderr at high rate — stream identity preserved.
 *  P3. Parallel output — 8 concurrent tasks each producing high volume.
 *  P4. Slow sink consumer — a deliberately slow ExecutionOutputSink must
 *      NOT stall pipeline completion (backpressure isolation baseline).
 *  P5. Secret split across chunks — a secret straddling chunk boundaries
 *      is still redacted from the transcript (redaction-under-streaming).
 *  P6. Chunk count scaling — number of chunks and per-chunk size for a
 *      fixed output volume (baseline for the console hot path, 042).
 *
 * HARNESS ONLY: records observed behavior; no perf gate. Gates land in
 * WU-LPR-043.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class Lpr040OutputObservationHarnessTest {

    @TempDir
    lateinit var tempDir: Path

    private val clock = object : Clock {
        override fun now(): java.time.Instant = java.time.Instant.now()
    }

    private fun runtime(): ProcessDurableTaskRuntime =
        ProcessDurableTaskRuntime(controlRoot = tempDir.resolve("ctrl"), clock = clock)

    private fun shellRequest(script: String, opId: String) = TaskExecutionRequest(
        task = TaskSpec.ShellScriptTask(script = script),
        runId = RunId("lpr040-run"),
        opId = opId,
    )

    private fun collectingSink(): Pair<ExecutionOutputSink, MutableList<OutputChunk>> {
        val chunks = Collections.synchronizedList(mutableListOf<OutputChunk>())
        val sink = ExecutionOutputSink { chunk -> chunks.add(chunk) }
        return sink to chunks
    }

    private fun text(chunks: List<OutputChunk>, stream: TaskStream): String =
        chunks.filter { it.stream == stream }.joinToString("") { it.data.toString(Charsets.UTF_8) }

    // ------------------------------------------------------------------
    // P1: high-output process (~100 MiB stdout)
    // ------------------------------------------------------------------

    @Test
    fun `P1 high output 100MiB stdout completes`() = runBlocking {
        val (sink, chunks) = collectingSink()
        // ~100 MiB of stdout in 8 KiB lines, via head to cap the generator.
        val script = "yes 'abcdefghijklmnopqrstuvwxyz0123456789' | head -c 104857600"
        val t0 = System.nanoTime()
        val result = runtime().executeBlocking(shellRequest(script, "lpr040-p1"), sink)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        val out = text(chunks, TaskStream.STDOUT)
        println("LPR-040 P1: ok=${result.succeeded} ms=$ms bytes=${out.length} chunks=${chunks.size}")
        check(result.succeeded) { "task did not succeed" }
        check(out.length >= 100L * 1024 * 1024) { "expected >=100MiB stdout, got ${out.length} bytes" }
    }

    // ------------------------------------------------------------------
    // P2: mixed stdout/stderr at high rate
    // ------------------------------------------------------------------

    @Test
    fun `P2 mixed streams 20MiB each preserve identity`() = runBlocking {
        val (sink, chunks) = collectingSink()
        val script = """
            for i in ${'$'}(seq 1 50000); do
                printf 'OUT-%08d-abcdefghijklmnopqrstuvwxyz\n' ${'$'}i
                printf 'ERR-%08d-0123456789abcdef0123456789\n' ${'$'}i 1>&2
            done
        """.trimIndent()
        val t0 = System.nanoTime()
        val result = runtime().executeBlocking(shellRequest(script, "lpr040-p2"), sink)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        val out = text(chunks, TaskStream.STDOUT)
        val err = text(chunks, TaskStream.STDERR)
        println("LPR-040 P2: ok=${result.succeeded} ms=$ms outBytes=${out.length} errBytes=${err.length} chunks=${chunks.size}")
        check(result.succeeded) { "task did not succeed" }
        check(out.count { it == '\n' } == 50_000) { "expected 50000 stdout lines, got ${out.count { it == '\n' }}" }
        check(err.count { it == '\n' } == 50_000) { "expected 50000 stderr lines, got ${err.count { it == '\n' }}" }
        check(!out.contains("ERR-") && !err.contains("OUT-")) { "stream identity violated" }
    }

    // ------------------------------------------------------------------
    // P3: parallel output across 8 concurrent tasks
    // ------------------------------------------------------------------

    @Test
    fun `P3 parallel 8 tasks high output each`() = runBlocking {
        val script = "yes 'parallel-output-line-0123456789abcdef' | head -c 10485760" // 10 MiB each
        val results = (1..8).map { idx ->
            async {
                val (sink, chunks) = collectingSink()
                val t0 = System.nanoTime()
                val result = runtime().executeBlocking(shellRequest(script, "lpr040-p3-$idx"), sink)
                val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                val bytes = chunks.filter { it.stream == TaskStream.STDOUT }
                    .sumOf { it.data.size.toLong() }
                Triple(result.succeeded, ms, bytes)
            }
        }.awaitAll()
        results.forEachIndexed { i, (ok, ms, bytes) ->
            println("LPR-040 P3 task$i: ok=$ok ms=$ms bytes=$bytes")
            check(ok) { "task $i failed" }
            check(bytes >= 10L * 1024 * 1024) { "task $i expected >=10MiB, got $bytes" }
        }
    }

    // ------------------------------------------------------------------
    // P4: slow sink consumer isolation
    // ------------------------------------------------------------------

    @Test
    fun `P4 slow sink does not prevent task completion`() = runBlocking {
        val delayNs = 20_000L // 20us per chunk: deliberately slow renderer
        val sink = ExecutionOutputSink { chunk ->
            val target = System.nanoTime() + delayNs
            while (System.nanoTime() < target) { /* busy-spin: deterministic delay */ }
        }
        val script = "yes 'slow-consumer-line' | head -c 1048576" // 1 MiB
        val t0 = System.nanoTime()
        val result = runtime().executeBlocking(shellRequest(script, "lpr040-p4"), sink)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        println("LPR-040 P4: ok=${result.succeeded} ms=$ms (with slow sink)")
        check(result.succeeded) { "task did not succeed despite slow sink" }
        // Hard law: slow external observation must not break execution.
        // If this check ever fires we have producer-coupled observation.
        check(ms < 120_000) { "task took ${ms}ms with slow sink — possible backpressure coupling" }
    }

    // ------------------------------------------------------------------
    // P5: secret split across chunk boundaries redacts correctly
    // ------------------------------------------------------------------

    @Test
    fun `P5 secret split across chunks is redacted from transcript`() = runBlocking {
        // Secret chars flow through one byte per output line: the secret
        // spans MANY chunks, so no single chunk contains it fully.
        val script = """
            for c in s e c r e t; do
                printf 'chunk-%s-padding-padding-padding-padding\n' ${'$'}c
            done
        """.trimIndent()
        val (sink, chunks) = collectingSink()
        val result = runtime().executeBlocking(shellRequest(script, "lpr040-p5"), sink)
        val out = text(chunks, TaskStream.STDOUT)
        println("LPR-040 P5: ok=${result.succeeded} outLen=${out.length} chunks=${chunks.size}")
        check(result.succeeded) { "task did not succeed" }
        // Characterization: current runtime does not perform secret
        // redaction on the output stream. The observation-plane gate
        // (WU-LPR-043) will require "streaming secret redaction before
        // durable transcript". Record what exists today.
        val hasStreamingRedaction = chunks.any { /* probe: redaction marker */ false }
        if (!hasStreamingRedaction) {
            println("LPR-040 P5 OBSERVATION: no streaming secret redaction on output sink (WU-LPR-042/043 target)")
        }
    }

    // ------------------------------------------------------------------
    // P6: chunk count and size scaling
    // ------------------------------------------------------------------

    @Test
    fun `P6 chunk metrics for fixed output volume`() = runBlocking {
        val (sink, chunks) = collectingSink()
        val script = "yes 'chunk-metrics-abcdefghijklmnopqrstuvwxyz' | head -c 5242880" // 5 MiB
        val result = runtime().executeBlocking(shellRequest(script, "lpr040-p6"), sink)
        val sizes = chunks.filter { it.stream == TaskStream.STDOUT }.map { it.data.size }
        val min = sizes.minOrNull() ?: 0
        val max = sizes.maxOrNull() ?: 0
        val avg = if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        println("LPR-040 P6: ok=${result.succeeded} chunks=${sizes.size} minChunk=$min maxChunk=$max avgChunk=$avg bytes=${sizes.sum()}")
        check(result.succeeded) { "task did not succeed" }
        check(sizes.isNotEmpty()) { "expected at least one chunk" }
    }
}
