package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.store.OutputStreamHandle
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink

/**
 * The Output Plane as a [ProcessOutputSink]: every pump chunk becomes one reserve/write/commit.
 *
 * ## Why this is the composition seam and not the substrate's job
 *
 * `DurableShellExecutor` must not know that an Output Plane exists — it writes to
 * [ProcessOutputSink] and nothing more. This class is where the application says *which* durable
 * authority the bytes belong to. The dependency points inward, from the adapter to the port.
 *
 * ## Why one chunk is one reservation
 *
 * `OutputStreamHandle.appendFrom` is the pull-shaped operation for an unbounded producer: it takes
 * as many reservations as it needs and returns the new committed offset. This is the push-shaped
 * equivalent, for a producer that already has its own windowing — the redaction pump. Each chunk
 * is a complete `reserve -> write -> commit` cycle, so a reader tailing the stream sees the
 * transcript as it is produced rather than at the end of the step. That property is the whole
 * reason this class exists.
 *
 * ## Redaction is not this class's job, and must never become it
 *
 * The bytes arriving here are already sanitized: the runtime's pump passes them through
 * `StreamingRedactor` before calling [write]. A store that redacted on read would already have
 * persisted the secret, so redaction is a write-side obligation and this class sits downstream of
 * it. The security law is therefore structural — the raw bytes have no path to a reservation.
 *
 * ## Refusal, not drop
 *
 * A failure to persist must not be swallowed by the pump's `catch`. Once the bytes have left the
 * pipe they cannot be re-read, so a silent failure yields a transcript that is short, ordered and
 * plausible. [refusal] records the first such failure and the caller turns it into a typed step
 * failure.
 *
 * ## Why the stream is declared on the FIRST BYTE and not up front
 *
 * `openStream` is called lazily, on the first write of at least one byte. Declaring both channels
 * before the child ran — which is what `ShExecution` used to do — makes every step look like it
 * produced console output, including `sh("true")` and a capture-mode `sh` whose stderr is silent. A
 * reader that sees a stream cannot tell "this step was quiet" from "this step printed something I
 * lost", and a consumer tailing a run gets a stream per step per channel that never advances.
 *
 * The recovery property that motivated the eager declaration is preserved exactly: the first
 * `reserve` is what creates the first committed byte, and by then the stream HAS been declared. A
 * crash between `commit` and `appendFrame` therefore still leaves a declared stream, which is the
 * case recovery needs. What the change removes is declaring streams for bytes that never existed.
 *
 * @see ProcessOutputSink
 */
internal class RedactingOutputIngress(
    private val openStream: () -> OutputStreamHandle,
    private val frameIndex: OutputFrameIndex,
    private val address: OutputStreamAddress,
) : ProcessOutputSink {

    private var refusal: ProcessOutputRefusal? = null

    /**
     * The durable handle, opened and declared exactly once, on first use.
     *
     * `lateinit` would work and would hide the "not opened yet" case from the type; a nullable field
     * says it, and the accessor is the only place that has to care.
     */
    private var opened: OutputStreamHandle? = null

    private fun handle(): OutputStreamHandle {
        val existing = opened
        if (existing != null) return existing
        val fresh = openStream()
        // Declared BEFORE the first reservation, so a crash after `commit` and before `appendFrame`
        // still leaves a stream recovery knows about. That ordering is the contract; do not move it.
        frameIndex.declareStream(fresh.stream, address.channel)
        opened = fresh
        return fresh
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // Once refused, further writes cannot be honoured, and pretending otherwise would produce
        // a transcript that stops mid-stream without saying why.
        if (refusal != null || length <= 0) return
        require(offset >= 0 && length <= bytes.size - offset) {
            "write($offset, $length) is outside a ${bytes.size}-byte array"
        }
        val handle = handle()
        try {
            val reservation = handle.reserve(length)
            // The zero-copy case matters: the pump hands over its own reuse buffer, and copying a
            // bounded window per chunk is the one allocation this path would otherwise add.
            val payload = if (offset == 0 && length == bytes.size) bytes
            else bytes.copyOfRange(offset, offset + length)
            reservation.write(payload)
            val committedEnd = reservation.commit()

            // OBS-C2.3: the frame records the order PipelineK OBSERVED this range, and it is
            // appended AFTER the bytes are committed — that ordering is the whole contract, since
            // it is what makes a committed-but-unframed range recoverable rather than lost. The
            // channel is not passed here: it rides in the stream's own identity, so a crash cannot
            // separate attribution from the bytes it describes.
            frameIndex.append(address.stream, address.channel, committedEnd - length, committedEnd)
        } catch (t: Throwable) {
            refusal = ProcessOutputRefusal(
                detail = "the transcript could not be persisted to stream ${handle.stream.value}",
                cause = t,
            )
        }
    }

    override fun refusal(): ProcessOutputRefusal? = refusal

    override fun close() {
        // Nothing to release: every chunk already ended in its own commit, and a chunk that failed
        // left a reservation the store's own recovery reconciles.
    }
}
