package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Where a shell transcript goes, as the shell substrate needs it.
 * ## Why this exists
 * The durable shell used to have exactly one destination for the child's merged output: a file in
 * the control directory, written by the wrapper and ingested by the application *after* the step
 * returned. That made the transcript observable only once the step had ended, which is useless for
 * a long build and impossible to consume remotely.
 * The runtime already owns a PIPE and a pump thread that drains the child through the redaction
 * seam. What it lacked was a destination other than the file. This interface is that destination,
 * and it is deliberately narrow: the shell decides **when** bytes are available, the composed sink
 * decides **where** they land.
 * ## What the SDK deliberately does not know
 * The substrate never names an Output Plane, a store, a filesystem, a renderer or a transport. It
 * writes bytes to whatever sink the application composed. That is the whole hexagonal requirement
 * for this seam, and it is what lets the canonical path and a test both drive the same pump.
 * ## Ordering
 * The pump writes bytes in the order the child produced them, and [write] must not reorder them.
 * A sink that cannot persist what it is given answers with [refusal] rather than dropping, because
 * a dropped process byte is indistinguishable at the read side from a byte the process never wrote.
 * @see RedactingOutputIngress, the application-side implementation
*/
interface ProcessOutputSink {

    /**
     * Appends `length` bytes from [bytes] starting at [offset], preserving order.
     * Called from the runtime's pump thread. Implementations must not assume they are called from
     * the caller's thread, and must not block indefinitely on a consumer: nothing downstream of
     * this call is allowed to apply backpressure to the child process.
*/
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /**
     * The first refusal this sink accepted responsibility for and could not fulfil, or `null`.
     * A refusal is not an exception the pump can swallow. The bytes are already gone from the
     * pipe at this point, so a silent failure would produce a transcript that is short, ordered
     * and plausible — the hardest kind of wrong. The caller reads this after the step and turns a
     * non-null value into a typed step failure.
*/
    fun refusal(): ProcessOutputRefusal?

    /**
     * Ends the stream. Called once, after the last [write].
     * For a store-backed sink this is where the final reservation is committed, so it MUST be
     * called or the tail of the transcript is never published.
*/
    fun close()
}

/**
 * A process-output write the sink could not make durable, or a drain it could not finish.
 * ## Why this is a sealed set and not one type with a nullable cause
 * The two shapes fail for unrelated reasons and carry unrelated evidence. A write failed and there
 * is a `Throwable` worth keeping; a pump was still alive when the budget expired and there is no
 * failure at all — it is an absence. Collapsing them into one class would force one of the two to
 * carry a field it does not have, and the only honest filler would be a fabricated exception,
 * which is precisely the "make the type lie so the call site compiles" shape the constitution
 * forbids. Each case carries its own payload and nothing else.
 * ## What `detail` may contain
 * An operator-facing description of WHAT failed, and never a process byte. `detail` reaches a step
 * failure message, an event and a journal record, so a refusal that quoted the transcript would
 * publish the secret the redaction seam exists to withhold — through the diagnostic channel rather
 * than through the Output Plane.
*/
sealed interface ProcessOutputRefusal {

    /** What failed, in words that identify the failure without reproducing any process output. */
    val detail: String

    /**
     * The sink accepted bytes it could not make durable, or its stream failed underneath it.
     * The bytes left the pipe when the sink was handed them, so nothing can recover them: this is
     * the "short, ordered, plausible transcript" failure and it has to be reported, never repaired.
*/
    data class WriteFailed(
        override val detail: String,
        val cause: Throwable,
    ) : ProcessOutputRefusal {
        override fun toString(): String = "ProcessOutputRefusal.WriteFailed($detail)"
    }

    /**
     * The pump was asked to stop and had not, within its budget.
     * This is not a sink failure: the sink may have accepted every byte it was given. It is the
     * *completion* claim that cannot be made — the drain is the thing that makes "the transcript is
     * complete" true, and a pump still holding the read end means the step must not be reported as
     * finished with a transcript. [waitedMs] is how long the drain actually waited, so the
     * diagnostic says how long the runtime was willing to wait rather than implying an unbounded
     * hang. [cause] says WHICH of the two things went wrong, because the two call for different
     * answers: a stuck child needs a child that lets go of the transcript, and a drain that ran out
     * of time needs a machine that keeps up.
*/
    data class DrainIncomplete(
        override val detail: String,
        val waitedMs: Long,
        val cause: TranscriptDrainCause,
    ) : ProcessOutputRefusal {
        override fun toString(): String = "ProcessOutputRefusal.DrainIncomplete($detail)"
    }
}

/**
 * Why a transcript could not be completed.
 *
 * The two causes are different facts about different things, and the operator's next move differs:
 * one is about the child, the other about the machine. A refusal that named neither would be a
 * symptom.
 */
enum class TranscriptDrainCause {
    /**
     * A pump stopped reading. The child is holding the transcript open, so the bytes that are
     * missing were never going to arrive: waiting longer cannot produce them.
     */
    STUCK_CHILD,

    /**
     * The pumps were still moving when the drain's ceiling arrived. The transcript is behind, not
     * broken: the same child on a quieter machine would have finished.
     */
    OUT_OF_TIME,
}

/**
 * The single place an exception becomes a refusal.
 * ## Why the pump needs this at all
 * The pump runs on a daemon thread whose entire purpose is to survive the child, so it cannot
 * propagate anything to the caller — and its `catch` used to discard everything it caught, with a
 * comment saying the bytes already persisted were worth keeping. That is true of the bytes that
 * arrived and silent about the ones that did not, and a transcript missing its tail is
 * indistinguishable at the read side from a step that never printed one. Worse, the paths that throw
 * here include `close()` — which is exactly where a store-backed sink commits its final
 * reservation, so a failure there loses the tail permanently and silently.
 * Wrapping rather than widening each sink is the point: every composition gets the same guarantee,
 * including [StreamProcessOutputSink], whose contract was previously "a stream write either throws
 * to the pump or succeeds, and the pump's own handling covers that" — a sentence describing the bug.
 * ## What it does not do
 * It does not retry, reorder, or re-read. A refused write leaves the pipe; the refusal is the whole
 * answer this seam can honestly give.
*/
internal class GuardedProcessOutputSink(
    private val delegate: ProcessOutputSink,
) : ProcessOutputSink {

    @Volatile
    private var failure: ProcessOutputRefusal.WriteFailed? = null

    /** First failure wins: later ones are consequences of the same broken destination. */
    fun recordPumpFailure(stage: String, t: Throwable) {
        if (failure == null) {
            failure = ProcessOutputRefusal.WriteFailed(
                detail = "process output was lost at $stage (${t.javaClass.simpleName})",
                cause = t,
            )
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // Once refused, further writes cannot be honoured. Continuing would append to a transcript
        // that already has an unexplained hole in it, which reads as complete and is not.
        if (failure != null) return
        try {
            delegate.write(bytes, offset, length)
        } catch (t: Throwable) {
            recordPumpFailure("write", t)
        }
    }

    override fun refusal(): ProcessOutputRefusal? = failure ?: delegate.refusal()

    override fun close() {
        // Never short-circuited on an existing refusal: `close` is where a store-backed sink
        // publishes its final reservation, so skipping it would turn a recoverable write failure
        // into an additional, unreported loss of the tail.
        try {
            delegate.close()
        } catch (t: Throwable) {
            recordPumpFailure("close", t)
        }
    }
}

/**
 * Which of a child process' two byte streams a [ProcessOutputSink] receives.
 * ## Why the substrate names channels at all
 * It does not, and that is the point. The shell knows it started a process with two pipes, and it
 * must route each pipe somewhere; that is a property of the process it launched, not of any Output
 * Plane. Naming the two streams here costs the substrate nothing and keeps
 * [dev.rubentxu.pipeline.v2.output.OutputChannel] — which carries a persisted stream id shape — on the
 * application side, where the Output Plane lives.
 * ## Why this is a closed set and not a boolean or a string
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
 * ## Why a sink pair rather than one sink that takes a channel per write
 * Routing the channel per **write** would put the channel in the same position as the bytes: a sink
 * would have to be told "this chunk came from stderr" on every call, and the pairing could be wrong
 * for one chunk out of thousands. Carrying the channel in the sink's own identity makes the pairing
 * structural — a stderr sink only ever receives stderr bytes, because there is no code path that
 * hands it anything else.
 * It also mirrors where the channel actually lives. The application maps each address to its own
 * channel-addressed stream, so the channel is part of *which stream the bytes are in*, and survives
 * a crash and a cursor hand-off with nothing extra to keep in step.
 * ## Merged output is not a third case
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
 * ## What it bounds now, after measuring it
 * It bounds the store's per-chunk bookkeeping. Every write here is one `reserve -> write -> commit`
 * cycle AND one frame in `SegmentFrameIndex`, and `SegmentFrameIndex.appendLine` opens a
 * `FileChannel` and calls `force(false)` for each one — so this window is literally the number of
 * fsyncs per byte of transcript. It is deliberately not a public contract, so changing it is not a
 * breaking change for a consumer of the read side.
 * ## Why it is 64 KiB and not 1024
 * The pump asks for `maxOf(1, minOf(window.size, redacted.available()))`, so a slow producer is
 * unaffected: a step printing 100 B/s commits 100-byte chunks whatever this says. The window only
 * governs a producer that fills the pipe, and there it is pure cost.
 * Measured over 8 MiB through the real `RedactingOutputIngress` and a real `SegmentOutputStore`:
 * ```text
 * window   transactions   frames=fsyncs   per MiB    MiB/s
 *   1024            8192             8192   1024.0      0.3
 *  16384             512              512     64.0     25.5
 *  65536             128              128     16.0    112.3
 * ```
 * 1024 was chosen when this WAS a liveness bound — at 8 KiB a step emitting 100 B/s withheld its
 * first visible byte for over a minute. That removed that role when the pump started asking for
 * what is ready, and the number stayed at the value its old justification had picked. The latency
 * floor is now the redactor's lookahead, at most `MIN_SECRET_WINDOW` bytes, and that one is
 * redaction correctness rather than tuning: a byte inside the lookahead could still begin a secret,
 * so emitting it early would be a leak rather than a latency.
 * 64 KiB is the knee of the curve — 128 KiB buys 43% more for twice the transaction memory — and it
 * is exactly `SegmentOutputStore.DEFAULT_RESERVATION_BYTES`. That alignment is the point: `reserve`
 * reserves `max(minBytes, 64 KiB)`, so a 1024-byte window was reserving 64 KiB, writing 1 KiB and
 * **discarding 98% of the reservation at every commit**. One window is now one reservation.
 * ## What it costs, named
 * A producer that saturates the pipe commits in 64 KiB units, so its first byte becomes visible
 * after ~64 ms rather than ~1 ms. That is the whole trade: first-byte latency for a fast producer,
 * against 377x the persistence throughput. At 0.3 MiB/s the cost was not merely slower — the sink
 * runs on the pump thread, so a fsync per KiB backs the child's pipe up and slows the build itself,
 * which is storage backpressure this subsystem is supposed to stay out of the way of.
*/
const val TRANSCRIPT_LIVE_WINDOW_BYTES: Int = 64 * 1024

/**
 * The failure code carried when a transcript could not be persisted or could not be drained.
 * It lives here, next to the refusal it describes, so the runtime that PRODUCES it and the caller
 * that acts on it cannot drift onto two spellings of the same fact. A caller branching on this must
 * name the constant, never the literal.
*/
const val OUTPUT_PERSISTENCE_REFUSED: String = "OUTPUT_PERSISTENCE_REFUSED"

/**
 * A [ProcessOutputSink] over a plain stream, for compositions that have not supplied one.
 * It exists so the pump has ONE write path rather than two, and so the legacy/test composition
 * that has no durable authority still produces a transcript.
 * It never refuses of its own accord: a stream write either throws or succeeds. That is not a gap,
 * because the pump wraps every sink in a [GuardedProcessOutputSink], and it is the guard — not each
 * sink, and not the pump's `catch` — that turns a thrown failure into a [ProcessOutputRefusal]. One
 * implementation, every composition.
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
