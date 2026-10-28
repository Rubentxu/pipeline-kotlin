package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OperationOutputStreams
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out the Output Plane for a control-directory root, recovered and ready.
 * ## Why this is a provider and not a constructor argument
 * The durable shell protocol makes the Output Plane the single durable authority for process
 * output. Reaching it
 * through a capability threaded from `CoreShellStep` would be the tidier shape, but the byte stream
 * is produced by a **child process**, so the JVM cannot be the writer without a pipe between the
 * child and the store. Handing the store to the launcher, which streams the child's bytes into it
 * while the step runs, is the honest shape for the current shell wrapper.
 * `console.log` is the durable protocol's console file and it is NOT an authority over the bytes
 * it holds: the store is. The protocol writes that file for the wrapper's own log, and — when the
 * child's bytes are not pumped through the JVM — for the child's merged transcript. The bytes the
 * Output Plane holds are handed to it by the launcher, and the file itself is read back only by the
 * legacy projection and by the heartbeat the reconciler measures.
 * ## Lifecycle
 * A store must be recovered before it acts ([storeForWriting]), and recovery is not free, so one
 * recovered store is held per control-directory root. A **new process** gets a new provider and
 * recovers again — that is the crash path, and it is exercised deliberately rather than cached
 * across it.
 * ## What the provider owns
 * The registry of the roots this process has touched, and through it the [OutputPlane] of each
 * root. It lives as long as the process does; [forget] discards the stores of one root and
 * [forgetAll] drops the roots themselves, which is what a process restart would do on its own.
 * @see storeForWriting
 * @see storeForReading
*/
object OutputPlaneProvider {

    /**
     * One plane per control root, and the plane is what holds that root's stores and that root's
     * mutual exclusion. The registry is the provider's own; the plane's resources belong to the
     * plane.
     */
    private val planes = ConcurrentHashMap<Path, OutputPlane>()

    /** Directory the Output Plane occupies inside a control-directory root. */
    const val OUTPUT_DIR: String = "output-plane"

    /**
     * The store for [controlDirRoot], **without** recovering. The read-side opening.
     * A reader must never reconcile durable state, because reconciliation truncates the
     * uncommitted tail of a stream and an uncommitted tail is exactly what a writer that is alive
     * right now looks like. The measured cost of violating it: a successful
     * `console` query in a second JVM destroyed 4096 bytes a live writer had written and
     * acknowledged, and the writer was never told.
     * Reads are still honest without recovery. A reader only ever serves bytes at or below the
     * committed offset, so it cannot observe an unacknowledged byte; recovery is what makes debris
     * disappear, not what makes a read true.
     *
     * The reader is a DIFFERENT store from the writer of the same root, and the two share that
     * root's mutual exclusion: the retention pass deletes a directory under the stream's lock, and
     * a reader that does not share that lock can be served from a directory being removed
     * underneath it.
    */
    fun storeForReading(controlDirRoot: Path): SegmentOutputStore = planeFor(controlDirRoot).reader()

    /**
     * The store for [controlDirRoot] on the WRITE side: recovered, and therefore permitted to
     * reconcile.
     * Recovery is scoped by ownership, so this pass skips every stream a live writer holds — in this
     * process or any other — rather than deciding for them that their bytes are debris.
    */
    fun storeForWriting(controlDirRoot: Path): SegmentOutputStore = planeFor(controlDirRoot).reconcileWriter()

    /**
     * Retained for callers that are neither purely reading nor purely writing — retention in
     * particular, which deletes committed bytes and therefore is neither.
     * It resolves to the writing store because retention has always recovered, and quietly changing
     * that would change what a retention pass is allowed to do without anyone deciding it. Splitting
     * it is a separate decision, recorded here rather than taken silently.
    */
    fun storeFor(controlDirRoot: Path): SegmentOutputStore = storeForWriting(controlDirRoot)

    /**
     * The stream id carrying [channel]'s bytes for one operation.
     * The channel is part of the **identity**, not metadata beside the bytes. Attribution then
     * survives a crash, a reopen and a cursor hand-off for free, because those already address bytes
     * by stream — a separate frame of metadata would be a second thing to keep in step with the
     * bytes, and keeping it in step is exactly what a crash interrupts.
    */
    fun streamId(runId: String, opId: String, channel: OutputChannel): OutputStreamId =
        OutputStreamAddress.of(runId, opId, channel).stream

    /**
     * The pair of channel streams one operation writes.
     * Exposed as the address type rather than as two loose ids so a caller cannot mix one
     * operation's stdout with another's stderr.
    */
    fun streamsOf(runId: String, opId: String): OperationOutputStreams =
        OperationOutputStreams.of(runId, opId)

    /**
     * Discard the stores cached for [controlDirRoot] — the reader and the writer together — so the
     * next access opens new ones.
     *
     * Both, and not the writer alone: a discarded writer left its reader alive, and the reader
     * that survived kept a view of the root's bytes that nothing had asked to keep.
     *
     * The root's mutual exclusion survives the discard. It protects the root's directories, not the
     * stores, and a store that a live caller still holds has to keep excluding the root's next
     * writer.
     */
    fun forget(controlDirRoot: Path) {
        planes[controlDirRoot.normalize()]?.discardStores()
    }

    /** Drop every root. Used by tests and by anything simulating a process restart. */
    fun forgetAll() {
        planes.clear()
    }

    /**
     * The plane of [controlDirRoot], created on first use.
     *
     * The plane is what owns the root's mutual exclusion and its two stores, so it is what the
     * registry holds — one entry per root, and one set of locks per root.
     */
    internal fun planeFor(controlDirRoot: Path): OutputPlane =
        planes.computeIfAbsent(controlDirRoot.normalize()) { root ->
            OutputPlane(root.resolve(OUTPUT_DIR))
        }
}
