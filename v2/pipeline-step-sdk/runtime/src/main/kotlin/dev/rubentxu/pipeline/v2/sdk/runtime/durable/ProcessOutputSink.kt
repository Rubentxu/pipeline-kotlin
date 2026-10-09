package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Where a shell transcript goes, as the shell substrate needs it.
 *
 * ## Why this exists
 *
 * The durable shell used to have exactly one destination for the child's merged output: a file in
 * the control directory, written by the wrapper and ingested by the application *after* the step
 * returned. That made the transcript observable only once the step had ended, which is useless for
 * a long build and impossible to consume remotely.
 *
 * The runtime already owns a PIPE and a pump thread that drains the child through the redaction
 * seam. What it lacked was a destination other than the file. This interface is that destination,
 * and it is deliberately narrow: the shell decides **when** bytes are available, the composed sink
 * decides **where** they land.
 *
 * ## What the SDK deliberately does not know
 *
 * The substrate never names an Output Plane, a store, a filesystem, a renderer or a transport. It
 * writes bytes to whatever sink the application composed. That is the whole hexagonal requirement
 * for this seam, and it is what lets the canonical path and a test both drive the same pump.
 *
 * ## Ordering
 *
 * The pump writes bytes in the order the child produced them, and [write] must not reorder them.
 * A sink that cannot persist what it is given answers with [refusal] rather than dropping, because
 * a dropped process byte is indistinguishable at the read side from a byte the process never wrote.
 *
 * @see RedactingOutputIngress, the application-side implementation
 */
interface ProcessOutputSink {

    /**
     * Appends `length` bytes from [bytes] starting at [offset], preserving order.
     *
     * Called from the runtime's pump thread. Implementations must not assume they are called from
     * the caller's thread, and must not block indefinitely on a consumer: nothing downstream of
     * this call is allowed to apply backpressure to the child process.
     */
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /**
     * The first refusal this sink accepted responsibility for and could not fulfil, or `null`.
     *
     * A refusal is not an exception the pump can swallow. The bytes are already gone from the
     * pipe at this point, so a silent failure would produce a transcript that is short, ordered
     * and plausible — the hardest kind of wrong. The caller reads this after the step and turns a
     * non-null value into a typed step failure.
     */
    fun refusal(): ProcessOutputRefusal?

    /**
     * Ends the stream. Called once, after the last [write].
     *
     * For a store-backed sink this is where the final reservation is committed, so it MUST be
     * called or the tail of the transcript is never published.
     */
    fun close()
}

/**
 * A process-output write the sink could not make durable.
 *
 * @property detail operator-facing description of what failed
 * @property cause the underlying failure, kept for diagnostics
 */
data class ProcessOutputRefusal(
    val detail: String,
    val cause: Throwable,
) {
    override fun toString(): String = "ProcessOutputRefusal($detail)"
}

/**
 * Which of a child process' two byte streams a [ProcessOutputSink] receives.
 *
 * ## Why the substrate names channels at all
 *
 * It does not, and that is the point. The shell knows it started a process with two pipes, and it
 * must route each pipe somewhere; that is a property of the process it launched, not of any Output
 * Plane. Naming the two streams here costs the substrate nothing and keeps
 * [dev.rubentxu.pipeline.v2.output.OutputChannel] — which carries a persisted stream id shape — on the
 * application side, where the Output Plane lives.
 *
 * ## Why this is a closed set and not a boolean or a string
 *
 * A process has exactly two streams, so the state space is finite and closed. A
 * `stdout: Boolean` would make "redirect neither" and "redirect both" representable, and neither is
 * something a launch can mean; a `"out" | "err"` string would move the illegal case to run time.
 * With this enum, an exhaustive `when` over the two sinks is a compile-time obligation, which is what
 * makes "a third channel was silently dropped" a build failure rather than a missing byte.
 */
enum class ProcessOutputChannel {
    /** The child's standard output. */
    STDOUT,

    /** The child's standard error. */
    STDERR,
    ;

    companion object {
        /** Both channels, in a fixed order so a caller never has to invent one. */
        val all: List<ProcessOutputChannel> get() = entries
    }
}

/**
 * The two per-channel destinations of one launch.
 *
 * ## Why a sink pair rather than one sink that takes a channel per write
 *
 * Routing the channel per **write** would put the channel in the same position as the bytes: a sink
 * would have to be told "this chunk came from stderr" on every call, and the pairing could be wrong
 * for one chunk out of thousands. Carrying the channel in the sink's own identity makes the pairing
 * structural — a stderr sink only ever receives stderr bytes, because there is no code path that
 * hands it anything else.
 *
 * It also mirrors where the channel actually lives. The application maps each address to its own
 * channel-addressed stream, so the channel is part of *which stream the bytes are in*, and survives
 * a crash and a cursor hand-off with nothing extra to keep in step.
 *
 * ## Merged output is not a third case
 *
 * There is deliberately no `MERGED` entry. A merged console is what a reader builds from the two
 * streams; persisting it as a third sink would recreate the second byte authority this design
 * exists to remove, and the bytes would then exist twice with no authority over which is current.
 */
data class ProcessOutputSinks(
    val stdout: ProcessOutputSink,
    val stderr: ProcessOutputSink,
) {
    /** The sink [channel] addresses, for a launch that pumps one pipe per channel. */
    operator fun get(channel: ProcessOutputChannel): ProcessOutputSink = when (channel) {
        ProcessOutputChannel.STDOUT -> stdout
        ProcessOutputChannel.STDERR -> stderr
    }

    /** Both sinks, in a fixed order so an iteration order is never invented by a caller. */
    fun all(): List<ProcessOutputSink> = listOf(stdout, stderr)

    companion object {
        /** Two sinks built from [stdoutSink] and [stderrSink]. */
        fun of(stdoutSink: ProcessOutputSink, stderrSink: ProcessOutputSink): ProcessOutputSinks =
            ProcessOutputSinks(stdoutSink, stderrSink)
    }
}

/**
 * The most sanitized bytes the pump moves per write into the composed sink.
 *
 * ## What it bounds now, after OBS-F measured it
 *
 * It bounds the store's per-chunk bookkeeping. Every write here is one `reserve -> write -> commit`
 * cycle AND one frame in `SegmentFrameIndex`, and `SegmentFrameIndex.appendLine` opens a
 * `FileChannel` and calls `force(false)` for each one — so this window is literally the number of
 * fsyncs per byte of transcript. It is deliberately not a public contract, so changing it is not a
 * breaking change for a consumer of the read side.
 *
 * ## Why it is 64 KiB and not 1024
 *
 * The pump asks for `maxOf(1, minOf(window.size, redacted.available()))`, so a slow producer is
 * unaffected: a step printing 100 B/s commits 100-byte chunks whatever this says. The window only
 * governs a producer that fills the pipe, and there it is pure cost.
 *
 * Measured over 8 MiB through the real `RedactingOutputIngress` and a real `SegmentOutputStore`
 * (`ObsFChunkCostMeasurementTest`; receipt
 * `docs/v2/07-uat/OBSF_PUBLISHED_READ_SIDE_AND_CHUNK_COST_RECEIPT.md`):
 *
 * ```text
 * window   transactions   frames=fsyncs   per MiB    MiB/s
 *   1024            8192             8192   1024.0      0.3
 *  16384             512              512     64.0     25.5
 *  65536             128              128     16.0    112.3
 * ```
 *
 * 1024 was chosen when this WAS a liveness bound — at 8 KiB a step emitting 100 B/s withheld its
 * first visible byte for over a minute. OBS-B removed that role when the pump started asking for
 * what is ready, and the number stayed at the value its old justification had picked. The latency
 * floor is now the redactor's lookahead, at most `MIN_SECRET_WINDOW` bytes, and that one is
 * redaction correctness rather than tuning: a byte inside the lookahead could still begin a secret,
 * so emitting it early would be a leak rather than a latency.
 *
 * 64 KiB is the knee of the curve — 128 KiB buys 43% more for twice the transaction memory — and it
 * is exactly `SegmentOutputStore.DEFAULT_RESERVATION_BYTES`. That alignment is the point: `reserve`
 * reserves `max(minBytes, 64 KiB)`, so a 1024-byte window was reserving 64 KiB, writing 1 KiB and
 * **discarding 98% of the reservation at every commit**. One window is now one reservation.
 *
 * ## What it costs, named
 *
 * A producer that saturates the pipe commits in 64 KiB units, so its first byte becomes visible
 * after ~64 ms rather than ~1 ms. That is the whole trade: first-byte latency for a fast producer,
 * against 377x the persistence throughput. At 0.3 MiB/s the cost was not merely slower — the sink
 * runs on the pump thread, so a fsync per KiB backs the child's pipe up and slows the build itself,
 * which is storage backpressure this subsystem is supposed to stay out of the way of.
 */
const val TRANSCRIPT_LIVE_WINDOW_BYTES: Int = 64 * 1024

/**
 * A [ProcessOutputSink] over a plain stream, for compositions that have not supplied one.
 *
 * It exists so the pump has ONE write path rather than two, and so the legacy/test composition
 * that has no durable authority still produces a transcript. It never refuses: a stream write
 * either throws to the pump or succeeds, and the pump's own handling covers that.
 */
internal class StreamProcessOutputSink(
    private val stream: java.io.OutputStream,
) : ProcessOutputSink {

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        stream.write(bytes, offset, length)
    }

    override fun refusal(): ProcessOutputRefusal? = null

    override fun close() {
        stream.flush()
        stream.close()
    }
}
