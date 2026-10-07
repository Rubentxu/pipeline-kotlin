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
 * ## What it bounds, and what it no longer does
 *
 * It bounds the store's per-chunk bookkeeping: every write here is one reserve/write/commit cycle,
 * so this is the transaction size, and OBS-F measures the throughput/RSS trade-off against data
 * rather than by taste. It is deliberately not a public contract, so changing it is not a breaking
 * change for a consumer of the read side.
 *
 * It used to ALSO be the liveness bound, because the pump asked `redacted.read(window)` for a whole
 * window and `RedactingInputStream.read` returns only at `len` bytes or EOF. That made the console
 * quantised in 1024-byte steps: at 8 KiB — the value before OBS-B — a step emitting 100 B/s
 * withheld its first visible byte for over a minute, and even at 1 KiB a 20-byte transcript stayed
 * invisible until the step ended. Both were measured, not argued.
 *
 * The pump now asks for `min(this, available())`, so this is a CEILING and the visible latency comes
 * from the redactor's lookahead instead — at most `MIN_SECRET_WINDOW` bytes. That is the floor that
 * cannot be tuned away: a byte inside the lookahead could still begin a secret, so emitting it early
 * would be a leak rather than a latency.
 */
const val TRANSCRIPT_LIVE_WINDOW_BYTES: Int = 1024

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