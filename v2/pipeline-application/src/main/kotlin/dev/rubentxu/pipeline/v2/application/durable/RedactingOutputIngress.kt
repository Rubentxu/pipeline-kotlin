package dev.rubentxu.pipeline.v2.application.durable

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
 * @see ProcessOutputSink
 */
internal class RedactingOutputIngress(
    private val handle: OutputStreamHandle,
) : ProcessOutputSink {

    private var refusal: ProcessOutputRefusal? = null

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // Once refused, further writes cannot be honoured, and pretending otherwise would produce
        // a transcript that stops mid-stream without saying why.
        if (refusal != null || length <= 0) return
        require(offset >= 0 && length <= bytes.size - offset) {
            "write($offset, $length) is outside a ${bytes.size}-byte array"
        }
        try {
            val reservation = handle.reserve(length)
            // The zero-copy case matters: the pump hands over its own reuse buffer, and copying a
            // bounded window per chunk is the one allocation this path would otherwise add.
            val payload = if (offset == 0 && length == bytes.size) bytes
            else bytes.copyOfRange(offset, offset + length)
            reservation.write(payload)
            reservation.commit()
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