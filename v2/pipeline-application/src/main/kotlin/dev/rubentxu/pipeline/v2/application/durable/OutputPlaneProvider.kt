package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OperationOutputStreams
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.OutputAppendPort
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out the Output Plane for a control-directory root, recovered and ready.
 *
 * ## Why this is a provider and not a constructor argument
 *
 * `ADR-M1 D2` makes the Output Plane the single durable authority for process output. Reaching it
 * through a capability threaded from `CoreShellStep` would be the tidier shape, but the byte stream
 * is produced by a **child process** writing `console.log`, so the JVM cannot be the writer without
 * inventing a pipe protocol between the wrapper and the store. Ingesting the finished file through
 * this provider is the honest shape for the current shell wrapper.
 *
 * `console.log` is therefore a **staging buffer, not an authority**: it is written by the wrapper,
 * read once, ingested, and deleted. Nothing may read it afterwards, and
 * [OutputSingleAuthorityFitnessTest] enforces exactly that. The moment the wrapper grows a pipe
 * protocol, this provider disappears and the child writes the store directly.
 *
 * ## Lifecycle
 *
 * A store must be recovered before it acts ([ADR-M1 §D4] O3), and recovery is not free, so one
 * recovered store is held per control-directory root. A **new process** gets a new provider and
 * recovers again — that is the crash path, and it is exercised deliberately rather than cached
 * across it.
 *
 * @see ADR-M1 §D2
 */
object OutputPlaneProvider {

    private val stores = ConcurrentHashMap<Path, SegmentOutputStore>()

    /** Directory the Output Plane occupies inside a control-directory root. */
    const val OUTPUT_DIR: String = "output-plane"

    /**
     * The store for [controlDirRoot], recovered on first use.
     *
     * Idempotent per root: two steps in the same run share one store, which is what makes a cursor
     * handed to one step meaningful to a later one.
     */
    fun storeFor(controlDirRoot: Path): SegmentOutputStore =
        stores.computeIfAbsent(controlDirRoot.normalize()) { root ->
            SegmentOutputStore(root.resolve(OUTPUT_DIR)).also { it.recover() }
        }

    /**
     * The stream id for one operation's transcript.
     *
     * Shape is `{opId}` under a per-run stream so a cursor cannot be mistaken for one from a
     * different run: the id alone has to be enough to reject a foreign cursor, and a bare op id
     * would collide across runs.
     *
     * ## This is the pre-OBS-C2 shape, and it is kept deliberately
     *
     * Its trailing segment is `transcript`, which is **not** a channel, so
     * [dev.rubentxu.pipeline.v2.output.OutputStreamAddress.parse] returns `null` for it. That is the
     * correct reading: a stream written before the channels were separated carries no channel
     * attribution and must not be guessed at. The canonical producer uses [streamId] with a channel;
     * this overload exists for the callers that address a historical or whole-operation stream.
     */
    fun streamId(runId: String, opId: String): OutputStreamId =
        OutputStreamId("$runId/$opId/transcript")

    /**
     * The stream id carrying [channel]'s bytes for one operation.
     *
     * The channel is part of the **identity**, not metadata beside the bytes. Attribution then
     * survives a crash, a reopen and a cursor hand-off for free, because those already address bytes
     * by stream — a separate frame of metadata would be a second thing to keep in step with the
     * bytes, and keeping it in step is exactly what a crash interrupts.
     */
    fun streamId(runId: String, opId: String, channel: OutputChannel): OutputStreamId =
        OutputStreamAddress.of(runId, opId, channel).stream

    /**
     * The pair of channel streams one operation writes.
     *
     * Exposed as the address type rather than as two loose ids so a caller cannot mix one
     * operation's stdout with another's stderr.
     */
    fun streamsOf(runId: String, opId: String): OperationOutputStreams =
        OperationOutputStreams.of(runId, opId)

    /** Drop the cached store for [controlDirRoot], forcing the next access to recover again. */
    fun forget(controlDirRoot: Path) {
        stores.remove(controlDirRoot.normalize())
    }

    /** Drop every cached store. Used by tests and by anything simulating a process restart. */
    fun forgetAll() {
        stores.clear()
    }
}
