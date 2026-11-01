package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.RedactorState
import dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink

/**
 * Publishes what a git command printed to the host's transcript, with the run's secrets
 * removed BEFORE the bytes leave the plugin.
 *
 * ## Why the redaction happens HERE and not in the sink
 * Redaction is a write-side obligation. A sink that masked on read would already have
 * persisted the secret, and the persistence is the thing nobody can take back. The shell's
 * own transcript pump makes the same choice for the same reason: it wraps the child's stream in
 * the redactor and the bytes that reach the sink have already been through it. This class is
 * that pump for git, with the same redactor and the same seam.
 *
 * The alternative — handing the raw bytes to the host and letting the host redact — was
 * rejected because the host cannot redact what it does not know is secret, and because the
 * plugin also READS the same bytes for its own diagnostics: a redaction that only existed on
 * one of the two paths would leave the other one leaking.
 *
 * ## Why one redactor per command
 * A redactor carries a lookahead: bytes that cannot yet be decided because a secret literal
 * might still start there. That lookahead belongs to ONE stream. The shell creates one
 * redactor per command for the same reason. Sharing one across commands would let a secret
 * split across two commands be masked, and would let a word that merely straddles a command
 * boundary be read as one literal — a transcript that says something the process never said.
 *
 * ## Why the tail is flushed by [finish] and not by [close]
 * The redactor's lookahead holds bytes that are only decidable at end-of-stream. The sink is
 * the HOST's, and its lifetime is not the plugin's to decide, so the plugin cannot use
 * "close the sink" as its end-of-stream signal: it would close a destination the host may
 * still be using, and it would lose the tail whenever the host chose not to close it. [finish]
 * is the plugin's own end-of-stream, and it is idempotent.
 */
internal class GitTranscriptWriter(
    private val transcript: GitTranscript,
) : ExecutionOutputSink {

    /**
     * Null when the run registered no secret. That is the ONLY case in which bytes pass through
     * untouched, and it is the run's own fact, not a default this class chose.
     */
    private val redactor: StreamingRedactor? = transcript.secretPatterns?.let { StreamingRedactor(it) }

    /** One independent redaction pass per channel: the two channels are two streams. */
    private val states: MutableMap<TaskStream, RedactorState> = HashMap()

    /**
     * The destination of each channel this writer spoke to.
     *
     * Asked ONCE per channel and kept, because the refusal is read from this very sink: the
     * refusal belongs to the destination that refused the write. A channel that never carried a
     * byte has no sink here, and therefore no refusal to report — a destination that was never
     * asked for cannot have refused anything.
     */
    private val sinks: MutableMap<TaskStream, ProcessOutputSink> = HashMap()

    private var finished: Boolean = false

    override suspend fun append(chunk: OutputChunk) {
        val bytes = redact(chunk.stream, chunk.data)
        if (bytes.isEmpty()) return
        sinkOf(chunk.stream).write(bytes, 0, bytes.size)
    }

    /**
     * Ends this command's transcript: the bytes the lookahead was holding become decidable and
     * are published. Idempotent, because the caller that owns the command may reach it through
     * more than one path and a second flush would publish the tail twice.
     */
    fun finish() {
        if (finished) return
        finished = true
        for (stream in TaskStream.entries) {
            val tail = tailOf(stream)
            if (tail.isEmpty()) continue
            sinkOf(stream).write(tail, 0, tail.size)
        }
    }

    /**
     * The first refusal the host's destination reported for the bytes this writer published.
     *
     * Read from the sinks this writer actually wrote to, which is where the refusal lives: the
     * host's destination is the only party that knows whether the bytes it was handed became
     * durable.
     */
    fun refusal(): ProcessOutputRefusal? =
        sinks.values.asSequence().mapNotNull { it.refusal() }.firstOrNull()

    /** The destination of one channel, asked once and kept. */
    private fun sinkOf(stream: TaskStream): ProcessOutputSink =
        sinks.getOrPut(stream) { transcript.sink(channelOf(stream)) }

    /**
     * The bytes this writer is willing to publish for [stream]: the redacted output decided so
     * far. The lookahead is NOT included — those bytes are not decided yet, and a transcript
     * that showed them would show a secret that is about to be masked.
     */
    private fun redact(stream: TaskStream, raw: ByteArray): ByteArray {
        val redactor = redactor ?: return raw
        val state = states.getOrPut(stream) { redactor.initialState() }
        redactor.feed(state, raw, 0, raw.size)
        val available = redactor.available(state)
        if (available <= 0) return ByteArray(0)
        val out = ByteArray(available)
        redactor.take(state, out, 0, available)
        return out
    }

    /** The end-of-stream flush of one channel, or nothing when that channel never spoke. */
    private fun tailOf(stream: TaskStream): ByteArray {
        val redactor = redactor ?: return ByteArray(0)
        val state = states[stream] ?: return ByteArray(0)
        return redactor.finish(state)
    }

    private fun channelOf(stream: TaskStream): ProcessOutputChannel = when (stream) {
        TaskStream.STDOUT -> ProcessOutputChannel.STDOUT
        TaskStream.STDERR -> ProcessOutputChannel.STDERR
    }
}
