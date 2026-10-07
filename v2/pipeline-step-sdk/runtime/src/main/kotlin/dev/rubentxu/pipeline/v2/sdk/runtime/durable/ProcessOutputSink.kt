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
 * How many sanitized bytes the pump asks for per read — and therefore how much transcript a reader
 * waits for before it can see any of it.
 *
 * ## Why this number is a liveness bound and not a buffer size
 *
 * `RedactingInputStream.read` returns only once it has produced `len` bytes or reached EOF. The
 * requested length is therefore not merely an allocation hint: **it is the delay between the child
 * writing a byte and a reader being able to observe it.** At 8 KiB — the value this call site used
 * before OBS-B — a step emitting 100 B/s would withhold its first visible byte for over a minute,
 * which is the defect `ObsBLiveOutputIngressCharacterisationTest` pins.
 *
 * ## Why not smaller
 *
 * Every chunk is one reserve/write/commit cycle in the composed sink, so the window is also the
 * store's per-chunk bookkeeping. OBS-F measures the throughput/RSS trade-off and tunes this against
 * data rather than by taste; it is deliberately not a public contract, so changing it is not a
 * breaking change for a consumer of the read side.
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