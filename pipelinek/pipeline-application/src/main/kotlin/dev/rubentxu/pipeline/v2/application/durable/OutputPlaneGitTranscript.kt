package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.observation.WakeupSignal
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitTranscript
import java.nio.file.Path

/**
 * The Output Plane as a [GitTranscript]: the git plugin's bytes land in the same durable
 * authority, under the same cursor and through the same frame index as the shell's.
 *
 * ## Why the destination is composed HERE and not in the plugin
 * The Output Plane's durable authority lives in `pipeline-application`; the `scm-git` plugin
 * lives BELOW it in the dependency graph. The dependency cannot be inverted — a plugin cannot
 * reach the core's store — so the destination is INJECTED: the core binds the capability, the
 * plugin asks for it. The plugin never learns which store the bytes land in; the core never
 * learns how git spells its argv.
 *
 * ## Why the transcript lands at THIS step's address
 * The cursor is the address `{runId}/{opId}/{channel}`, and `opId` is the identity of the
 * step being dispatched. A reader that asks "what did step 2 print?" therefore finds the git
 * transcript beside the shell transcript of the same step, with no second index to learn and
 * no second place to look. The durable task journal's own ids are NOT used here: those
 * identify the git command inside the journal, and a transcript filed under them would be
 * unreachable by any cursor a reader can hold.
 *
 * ## Why the store is resolved on the FIRST BYTE
 * Recovering the writer store creates the `output-plane` directory in the control root, and
 * a checkout that printed nothing must not leave a transcript behind that says it produced
 * console output — a reader could not tell "this step was quiet" from "this step printed
 * something I lost". The store is therefore resolved on the first write of at least one byte,
 * which keeps the recovery property the shell relies on (the first reservation creates the
 * first committed byte, and the stream is declared by then) without declaring streams for
 * bytes that never existed.
 *
 * ## Why the registry travels with the destination
 * Redaction is a write-side obligation: a store that masked on read would already have
 * persisted the secret. The producer therefore redacts before the bytes leave it, and it
 * needs the run's registry to do it — the same registry the shell's pump uses. One
 * capability, one thing the host must get right.
 */
internal class OutputPlaneGitTranscript(
    private val runId: String,
    private val opId: String,
    private val controlDirRoot: Path,
    override val secretPatterns: SecretPatternRegistry?,
    private val outputWakeups: WakeupSignal?,
) : GitTranscript {

    /**
     * One destination per channel, kept.
     *
     * The plugin asks ONCE per channel and keeps the answer, because the refusal is read from
     * that very destination: a destination that answered a fresh instance every time would
     * split the refusal in two, and a refusal nobody can read is a transcript that looks
     * complete and is not.
     */
    private val sinks: MutableMap<ProcessOutputChannel, ProcessOutputSink> = HashMap()

    override fun sink(channel: ProcessOutputChannel): ProcessOutputSink =
        sinks.getOrPut(channel) { LazyPlaneIngress(channel) }

    /**
     * The destination for one channel, resolved on its first byte.
     *
     * The laziness is the whole point of this class: the store, the frame index and the
     * stream handle are all created by the first write, so a step that printed nothing costs
     * nothing and leaves nothing behind.
     */
    private inner class LazyPlaneIngress(
        private val channel: ProcessOutputChannel,
    ) : ProcessOutputSink {

        private var delegate: ProcessOutputSink? = null

        /** A failure to even OPEN the destination, kept as a refusal like any other. */
        private var failure: ProcessOutputRefusal? = null

        private fun resolve(): ProcessOutputSink {
            val existing = delegate
            if (existing != null) return existing
            val store = OutputPlaneProvider.storeForWriting(controlDirRoot)
            val streams = OutputPlaneProvider.streamsOf(runId, opId)
            val address = when (channel) {
                ProcessOutputChannel.STDOUT -> streams.stdout
                ProcessOutputChannel.STDERR -> streams.stderr
            }
            val fresh = RedactingOutputIngress(
                { store.open(address.stream) },
                store.frameIndex(),
                address,
                afterChunkCommitted = { outputWakeups?.signal() },
            )
            delegate = fresh
            return fresh
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            // Once refused, further writes cannot be honoured: continuing would append to a
            // transcript that already has an unexplained hole in it, which reads as complete.
            if (failure != null || length <= 0) return
            try {
                resolve().write(bytes, offset, length)
            } catch (t: Throwable) {
                failure = ProcessOutputRefusal.WriteFailed(
                    detail = "the git transcript could not be opened for channel ${channel.name}",
                    cause = t,
                )
            }
        }

        override fun refusal(): ProcessOutputRefusal? = failure ?: delegate?.refusal()

        override fun close() {
            // Nothing to release: every chunk ended in its own commit. The destination is the
            // host's, and the host decides its own lifetime.
            delegate?.close()
        }
    }
}
