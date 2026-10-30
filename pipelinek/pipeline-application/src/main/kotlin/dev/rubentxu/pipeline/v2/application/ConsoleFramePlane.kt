package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.console.OperationSelection
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.file.Path

object ConsoleFramePlane {

    /**
     * The per-frame reader over the run's Output Plane.
     *
     * ONE constructor for every frame path, so the paths cannot end up reading different stores.
     *
     * [OutputPlaneProvider.storeForReading] memoises per control root in a process-wide cache, so
     * two readers built for the same root share one store. That cache is process-wide state:
     * [OutputPlaneProvider.forgetAll] is what drops it, and a caller that needs a fresh root has
     * to ask for that.
     */
    internal fun frameReader(root: Path): FrameIndexedObservationOutputReader {
        val store = OutputPlaneProvider.storeForReading(root)
        return FrameIndexedObservationOutputReader(
            index = store.frameIndex(),
            bytes = store,
            tails = store,
        )
    }

    /**
     * Per-frame read.
     *
     * Uses [FrameIndexedObservationOutputReader]
     * (the per-op public seam) to walk the operation's frames in observation order
     * starting at [ConsoleReadRequest.Frames.afterOrdinal], bounded by [ConsoleReadRequest.Frames.frameLimit].
     * The opId filter is applied on the read side, as the read-side contract documents.
*/
    internal fun readFrames(
        request: ConsoleReadRequest.Frames,
        root: Path,
    ): ObservationOutputRead {
        val reader = frameReader(root)
        // Pass opId to the reader so the per-frame walk
        // is scoped to ONE operation, not the whole run. The opId-less overload is still
        // there for callers that legitimately want every frame in a run (observe --view
        // console, the live drain). The console verb addresses one op at a time and gets
        // the opId-scoped read.
        return readFramePage(reader, request.runId, request.opId, request.afterOrdinal, request.frameLimit)
    }

    /**
     * One bounded frame read, scoped or not.
     *
     * The scope decision lives HERE, in the verb, rather than in the reader, because that is
     * where the selection lives. `ObservationOutputReader` keeps its contract — a supplied
     * `opId` is a scope, never a filter — and this function is the single place that decides
     * whether a request asked for one operation or for the whole run.
     *
     * Supplying `null` reads the run wide; the selection then narrows. Supplying an opId reads
     * that operation and the selection may only remove from it. Both together are an
     * intersection, which is the honest reading of two constraints.
*/
    internal fun readFramePage(
        reader: FrameIndexedObservationOutputReader,
        runId: String,
        opId: String?,
        afterOrdinal: Long,
        frameLimit: Int,
    ): ObservationOutputRead =
        if (opId == null) {
            reader.readOutput(runId, afterOrdinal, frameLimit)
        } else {
            reader.readOutput(runId, opId, afterOrdinal, frameLimit)
        }

    /**
     * Filtered per-frame read.
     *
     * Reads the run's frames via the same per-frame reader as [ConsoleFramePlane.readFrames] (the reader stays
     * un-touched per "do not modify the reader" in the slice's scope). The filter is applied
     * at emit time, not read time — see [ConsolePresentation.emitFramesFiltered] — so the page returned here is
     * the full observation-ordered window the per-frame walk already produced, exactly the
     * same shape [ConsoleFramePlane.readFrames] returns. The verb main's `when (request)` dispatch in [MainConsoleCli.main]
     * sends the result to the filtered emit path; the read path is filter-agnostic.
*/
    internal fun readFramesFiltered(
        request: ConsoleReadRequest.FramesFiltered,
        root: Path,
    ): ObservationOutputRead {
        val reader = frameReader(root)
        // OpId-scoped read; see readFrames.
        return readFramePage(reader, request.runId, request.opId, request.afterOrdinal, request.frameLimit)
    }

    /**
     * Does [selection] admit this frame?
     *
     * The frame's stream id is `<runId>/<opId>/<channel>`, and the operation is the WHOLE
     * middle of it: it may carry separators of its own, so the middle is not a segment but a
     * range. The id is read from its two ends — the run first, the channel last — and what is
     * left is the operation, whole. Comparing that against the selection is what stops a
     * selection from admitting an operation that merely CONTAINS the selected one, and what
     * stops a composite operation from being cut in the wrong place and named as an operation
     * that does not exist.
     * Refusing to interpret it is the fail-closed direction: a frame whose operation cannot be
     * named is one no selection can honestly claim.
*/
    internal fun selectionSelects(selection: OperationSelection, streamId: String): Boolean {
        val address = OutputStreamAddress.parse(OutputStreamId(streamId)) ?: return false
        return selection.includes(address.operationId)
    }

    /**
     * Frame-ordinal continuation token, distinct from the byte cursor's prefix.
     *
     * The byte path encodes `out-cursor-v1:<stream>:<offset>`; the per-frame path encodes
     * `out-frame-ord-v1:<ordinal>`. Two prefixes, two planes, and a consumer that pastes the
     * wrong one in the wrong command is told so at decode, not by being silently advanced to
     * a position in the wrong bytes.
*/
    internal const val FRAME_ORDINAL_TOKEN_PREFIX: String = "out-frame-ord-v1"

    internal fun encodeFrameOrdinal(ordinal: Long): String =
        "$FRAME_ORDINAL_TOKEN_PREFIX:$ordinal"

    /**
     * Decode a frame-ordinal token. Returns `null` for anything else, including a byte-cursor
     * token (which the byte path would decode) — two planes, two decoders, no accidental
     * cross-wiring.
*/
    internal fun decodeFrameOrdinal(token: String): Long? {
        val parts = token.split(':')
        if (parts.size != 2) return null
        if (parts[0] != FRAME_ORDINAL_TOKEN_PREFIX) return null
        return parts[1].toLongOrNull()?.takeIf { it >= -1L }
    }
}
