package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import java.nio.file.Path

/**
 * The consumable read side: console output, addressed by [OutputCursor].
 *
 * ## What this is for
 *
 * `OutputReadPort` existed since, and nothing called it. A contract nobody can call is a
 * design, not a product. This is the surface a consumer actually reaches for: a Jenkins
 * `LogStorage`, a Fabric ACL adapter, or [MainConsoleCli].
 *
 * ## Why it is not built on `EventHistoryReader`
 *
 * The event plane continues over a store-assigned **sequence**; the output plane continues over a
 * **committed byte offset**. Building a console reader on the event sequence is precisely the
 * mistake this service refuses: a gap in the sequence is indistinguishable from output that was
 * never emitted, and unrelated event traffic perturbs the read. So this reads the Output Plane and
 * only the Output Plane, and its continuation token is `out-cursor-v1:`, never `evt-cursor-v1:`.
 *
 * ## Resolution, not guessing
 *
 * A consumer names a **run and an operation**, not a stream id. The mapping is
 * [OutputPlaneProvider.streamId] and it is one-way on purpose: this service never *derives* a
 * cursor, it only resolves what a consumer already asked for. A reader that invented a stream
 * identity would be one refactor away from reading the wrong run.
 *
 * ## Refusals are reported, not swallowed
 *
 * A refusal is a fact about the question, not an error: an unknown stream and a cursor from another
 * stream are both ways a consumer asked something that cannot be answered. They are rendered to the
 * caller, never turned into an empty page — an empty page is a valid answer, and conflating it with
 * "wrong stream" is how a reader ends up believing it read nothing when it read nothing at all.
*/
object ConsoleReadService {

    /** Bytes emitted per continuation by default. A page is bounded; this is the bound. */
    const val DEFAULT_PAGE_BYTES: Int = 64 * 1024

    /**
     * Frames emitted per continuation by default on the per-frame path.
     *
     * 1024 frames is a defensive default: at the typical 8 KiB frame size a step's transcript
     * fits well under it, while a noisy script that emits many small frames does not get capped
     * too soon. Callers that want a different bound pass `--frame-limit N`; the admission
     * refuses non-positive values.
*/
    const val DEFAULT_FRAME_LIMIT: Int = 1024

    /** What a consumer gets back: a bounded page, or the reason there isn't one. */
    sealed interface Result {
        data class Page(val page: OutputPage) : Result
        data class Refused(val reason: OutputRefusal) : Result
    }

    /**
 * Read one bounded page of [opId]'s transcript in [runId], continuing after [after].
 *
 * ## Why one call still answers "the whole console"
 *
 * An operation's output was split into two channel-addressed streams, and this method is where
 * a consumer is meant to see them as one console again. The concatenation happens HERE, on the read
 * side, and is never persisted — a merged run on disk would be the second byte authority
 * that is not allowed.
 *
 * The continuation cursor names **the channel the page stopped on**, because that is the only thing
 * an [OutputCursor] can honestly name: it addresses a byte position inside ONE stream, and the type
 * refuses a cursor that claims otherwise. So a reader resuming from the returned cursor continues
 * exactly where it stopped, and the remaining channel is picked up afterwards. Reconstructing that
 * ordering across channels — the interleaving a real console shows — is `OutputFrameIndex`'s job, and
 * this method deliberately does NOT pretend to do it: it concatenates in a fixed channel order, which
 * is a projection and not a claim about what the kernel interleaved.
 *
 * @param after `null` reads from the start. A token for a different stream is **refused**,
 * not clamped — see the class KDoc.
*/
    fun read(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        after: OutputCursor?,
        maxBytes: Int = DEFAULT_PAGE_BYTES,
    ): Result {
        val store = OutputPlaneProvider.storeForReading(controlDirRoot)
        val ordered = OutputPlaneProvider.streamsOf(runId, opId).all

        // A cursor names one stream. If it names one of THIS operation's channels, the merged read
        // starts there. If it names some other stream entirely — another operation, another run —
        // the store is asked to refuse it against the stream it actually names, which is the refusal
        // that names the caller's real mistake. Falling through to "read from the start" instead
        // would silently answer a different question than the one that was asked.
        val startIndex = after
            ?.let { cursor -> ordered.indexOfFirst { it.stream == cursor.stream } }
            ?.takeIf { it >= 0 }

        if (after != null && startIndex == null) {
            return Result.Refused(
                OutputRefusal.ForeignStream(expected = ordered.first().stream, actual = after.stream),
            )
        }

        val first: Int = startIndex ?: 0

        val out = java.io.ByteArrayOutputStream()
        var nextCursor: OutputCursor? = null
        var anyChannelAnswered = false

        for (address in ordered.drop(first)) {
            if (out.size() >= maxBytes) break

            // The continuation applies ONLY to the channel it names. Every other channel starts
            // at its own beginning, and a read with no cursor at all starts everywhere.
            val cursor = if (after != null && address.stream == after.stream) after
            else OutputCursor.start(address.stream)

            val budget = maxOf(1, maxBytes - out.size())
            when (val result = store.read(address.stream, cursor, budget)) {
                is OutputReadResult.Refused -> {
                    // A channel that was never opened is not an error. In `returnStdout` mode stdout
                    // is the typed VALUE and goes to output.txt, so the stdout stream legitimately
                    // does not exist while stderr does. Skipping an UNKNOWN stream is what lets one
                    // read answer both invocation modes; any OTHER refusal is still propagated,
                    // because that is a fact about the question rather than about the output.
                    if (result.reason is OutputRefusal.UnknownStream) continue
                    return Result.Refused(result.reason)
                }
                is OutputReadResult.Page -> {
                    anyChannelAnswered = true
                    out.write(result.page.bytes)
                    if (result.page.next != null) {
                        nextCursor = result.page.next
                    }
                }
            }
            if (nextCursor != null) break
        }

        // Both channels absent is a fact the CALLER must be able to tell from "one channel, no
        // bytes": reporting an empty page here would let a silent step look like a step whose output
        // was truncated to nothing. The refusal carries the first channel's identity, which is the
        // one a reader would have asked for.
        if (!anyChannelAnswered) {
            val absent = ordered.getOrNull(first) ?: ordered.first()
            return Result.Refused(OutputRefusal.UnknownStream(absent.stream))
        }

        // `readRange` and `read` must name the SAME absent stream for one operation, so a caller
        // that switched entry points still recognises "nothing was written" as the same fact.

        // The page reports the stream and offset it actually came from. When the whole console fit,
        // the first channel is the honest answer; when it did not, `nextCursor` already names where
        // the reader stopped. When a channel was skipped as absent, the reported stream is the first
        // one that actually produced bytes — reporting a stream that holds nothing would be a page
        // that lies about its own origin.
        val reported = ordered.drop(first).firstOrNull { it.stream == nextCursor?.stream }
            ?: ordered.drop(first).firstOrNull { address ->
                (store.committedExtent(address.stream) ?: 0L) > 0L
            }
            ?: ordered.getOrNull(first)
            ?: ordered.first()

        return Result.Page(
            OutputPage(
                bytes = out.toByteArray(),
                stream = reported.stream,
                from = after?.takeIf { it.stream == reported.stream }?.committedOffset ?: 0L,
                next = nextCursor,
                committedEnd = after?.takeIf { it.stream == reported.stream }?.committedOffset
                    ?.plus(out.size().toLong())
                    ?: out.size().toLong(),
            ),
        )
    }

    /**
     * Read an arbitrary committed byte range of [opId]'s console.
     *
     * ## The merged offset space
     *
     * An operation has two channel streams, so "byte 30 of this console" is only
     * meaningful once the two are put in an order. That order is the fixed channel order used by
     * [ConsoleReadService.read], which makes a range and a page agree by construction: the same offsets name the same
     * bytes whichever way a reader asks for them. It is a **projection**, not a claim about what the
     * kernel interleaved — reconstructing the real observation order is `OutputFrameIndex`'s job.
*/
    fun readRange(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        from: Long,
        to: Long,
    ): Result {
        val store = OutputPlaneProvider.storeForReading(controlDirRoot)
        val ordered = OutputPlaneProvider.streamsOf(runId, opId).all

        // A channel that was never opened contributes zero bytes to the merged space, which is what
        // makes one range read correct for both plain and `returnStdout` invocations.
        val bounds = ordered.map { store.committedExtent(it.stream) ?: 0L }
        val total = bounds.sum()
        // REFUSED, not thrown. This used to be a `require`, so a span past the end of a short run let
        // an IllegalArgumentException escape `main` — the reader crashed instead of answering, and the
        // crash said nothing a caller could act on. Both refusals were already in [OutputRefusal];
        // nothing in the program was reading them, which is why the hole survived the ADT being closed.
        if (from < 0L || to < from) {
            return Result.Refused(OutputRefusal.InvalidRange(from, to))
        }
        if (to > total) {
            return Result.Refused(OutputRefusal.OffsetBeyondCommitted(requested = to, committed = total))
        }

        val out = java.io.ByteArrayOutputStream()
        var consumed = 0L
        for ((address, extent) in ordered.zip(bounds)) {
            // The channel's own slice of the merged space is [consumed, consumed + extent).
            val channelStart = consumed
            val channelEnd = consumed + extent
            consumed = channelEnd
            if (extent == 0L) continue
            if (channelEnd <= from) continue
            if (channelStart >= to) break

            val sliceFrom = maxOf(from, channelStart) - channelStart
            val sliceTo = minOf(to, channelEnd) - channelStart
            if (sliceTo <= sliceFrom) continue

            when (val result = store.readRange(address.stream, sliceFrom, sliceTo)) {
                is OutputReadResult.Refused -> return Result.Refused(result.reason)
                is OutputReadResult.Page -> out.write(result.page.bytes)
            }
        }

        val bytes = out.toByteArray()
        // `OutputPage` reports one stream, so this names the channel that contributed the FIRST byte
        // of the range. A range is not a resumable read — `next` is null by construction, because
        // continuation is expressed with a cursor and there is no honest single cursor for a span of
        // two streams.
        val firstChannel = ordered.withIndex().firstOrNull { (index, _) ->
            val start = bounds.take(index).sum()
            start + bounds[index] > from && bounds[index] > 0L
        }?.value ?: ordered.first()

        return Result.Page(
            OutputPage(
                bytes = bytes,
                stream = firstChannel.stream,
                from = from,
                next = null,
                committedEnd = from + bytes.size,
            ),
        )
    }

    /** Render a refusal as one stable line, so a script can branch on the reason. */
    fun renderRefusal(reason: OutputRefusal): String = when (reason) {
        is OutputRefusal.ForeignStream ->
            "console-refused: foreign-stream expected=${reason.expected.value} actual=${reason.actual.value}"
        is OutputRefusal.UnknownStream ->
            "console-refused: unknown-stream ${reason.stream.value}"
        is OutputRefusal.OffsetBeyondCommitted ->
            "console-refused: offset-beyond-committed requested=${reason.requested} committed=${reason.committed}"
        is OutputRefusal.InvalidRange ->
            "console-refused: invalid-range [${reason.from}, ${reason.to})"
        is OutputRefusal.DanglingCommit ->
            "console-refused: dangling-commit end=${reason.requestedEnd} readable=${reason.readableBytes}"
        OutputRefusal.RecoveryNotCompleted ->
            "console-refused: recovery-not-completed"
        // A follow produces a Refused event that the application layer
        // surfaces. Render it with stable, script-branchable text.
        is OutputRefusal.StreamLostRetention ->
            "console-refused: stream-lost-retention stream=${reason.stream.value} last-committed=${reason.lastCommitted}"
        // A thrown exception from the read ports is
        // caught at the follower boundary and surfaced as a typed
        // refusal. The console renders the cause as a short string
        // so a script can branch on the prefix.
        is OutputRefusal.StorageError ->
            "console-refused: storage-error cause=${reason.cause}"
        // The digest/pin/retention surface brought refusal cases of its own.
        // Render them with stable, script-branchable text so consumers can
        // grep the prefix. The sealed ADT discipline means adding a case forces
        // this branch list to grow here at compile time: a refusal nobody can
        // name is a refusal nobody can act on.
        is OutputRefusal.RetentionGap ->
            "console-refused: retention-gap stream=${reason.stream.value} last-committed=${reason.lastCommitted}"
    }

    fun readWholeStream(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        maxBytes: Int = DEFAULT_PAGE_BYTES,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = null
        var pages = 0
        while (true) {
            // Capped: a store that never ends the stream would otherwise loop here forever, in
            // production code, for any consumer that asks for the whole transcript.
            check(++pages < 1_000_000) { "readWholeStream did not terminate after $pages pages" }
            val result = read(controlDirRoot, runId, opId, cursor, maxBytes)
            when (result) {
                is Result.Refused -> error(
                    renderRefusal(result.reason),
                )
                is Result.Page -> {
                    out.write(result.page.bytes)
                    cursor = result.page.next ?: break
                }
            }
        }
        return out.toByteArray()
    }
}
