package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.output.store.SealOutcome
import dev.rubentxu.pipeline.v2.runtime.durable.OutputPlaneProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the sealer did with the output of the run it was asked about.
 */
internal sealed interface SupervisedRunSeal {

    /**
     * Every stream of the run is sealed, or never held a byte.
     *
     * @property streams how many streams the run had on the plane.
     */
    data class Sealed(val streams: Int) : SupervisedRunSeal

    /** The plane could not be sealed, and [detail] says why in one line. */
    data class Refused(val detail: String) : SupervisedRunSeal
}

/**
 * The door that seals the output of a run whose writer is gone.
 *
 * ## Why the supervisor needs this door
 *
 * Because the seal is the statement "no more bytes will be written to this
 * stream", and the one who writes the bytes is the one who says it. A child
 * that is KILLED says nothing: its streams stay open for ever, and whoever
 * follows the output of that run waits for a seal that nobody writes. The
 * service is the only thing alive that can say it, and it says it after the
 * child is gone, so the statement is true.
 */
internal fun interface SupervisedRunOutputSealer {

    /**
     * Seals every stream of [runId].
     *
     * @param runId the run whose writer is gone.
     * @return what the sealer did.
     */
    fun sealOutputOf(runId: String): SupervisedRunSeal
}

/**
 * The sealer that writes the seal on the output plane of the control root.
 *
 * ## Why the plane is the one the control port uses
 *
 * Because the cancellation of a run seals through the same door, and a run
 * that is closed by either path must be sealed the same way: the writing
 * store of the root, reconciled, and the streams the index declares for the
 * run. Two ways of sealing would be two opinions about when a stream is done.
 *
 * ## Why the store is reconciled before it seals
 *
 * Because the child died in the middle of a write: the bytes it had reserved
 * and not committed are debris, and the seal records the committed extent.
 * The reconciliation skips every stream a live writer holds, so a run that is
 * still writing elsewhere is not touched.
 *
 * ## Why a root without a plane is not an error
 *
 * Because a child that died before its first byte left no output to seal, and
 * a sealer that opened the plane to find that out would create it. The root
 * is left as it was found.
 *
 * ## What this sealer does NOT do
 *
 * It does not take the lease of the run. It is asked about a run whose child
 * has ended, and the child was the only writer the run had; a process that
 * the child left behind and that still writes to the plane is a writer the
 * reconciliation recognises and skips.
 */
internal class OutputPlaneSupervisedRunOutputSealer(
    private val controlRoot: Path,
) : SupervisedRunOutputSealer {

    override fun sealOutputOf(runId: String): SupervisedRunSeal {
        if (!Files.isDirectory(controlRoot.resolve(OutputPlaneProvider.OUTPUT_DIR))) {
            return SupervisedRunSeal.Sealed(0)
        }
        return try {
            val plane = OutputPlaneProvider.storeForWriting(controlRoot)
            plane.recover()
            val streams = plane.frameIndex().streamsOfRun(runId)
            for (stream in streams) {
                val outcome = plane.seal(stream)
                if (outcome is SealOutcome.Failure) {
                    return SupervisedRunSeal.Refused(
                        "the stream $stream could not be sealed: " +
                            (outcome.cause.message ?: outcome.cause.javaClass.simpleName),
                    )
                }
            }
            SupervisedRunSeal.Sealed(streams.size)
        } catch (failure: Exception) {
            SupervisedRunSeal.Refused(failure.message ?: failure.javaClass.simpleName)
        }
    }
}
