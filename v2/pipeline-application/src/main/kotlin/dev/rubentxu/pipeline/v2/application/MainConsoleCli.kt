package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * M1-P3 — the consumable read side: console output, addressed by [OutputCursor].
 *
 * ## What this is for
 *
 * `OutputReadPort` existed since M1-P1, and nothing called it. A contract nobody can call is a
 * design, not a product. This is the surface a consumer actually reaches for: a Jenkins
 * `LogStorage`, a Fabric ACL adapter, or [MainConsoleCli].
 *
 * ## Why it is not built on `EventHistoryReader`
 *
 * The event plane continues over a store-assigned **sequence**; the output plane continues over a
 * **committed byte offset**. Building a console reader on the event sequence is precisely the
 * conflation `ADR-M1 D3` forbids: a gap in the sequence is indistinguishable from output that was
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
 * OBS-C2.3 split an operation's output into two channel-addressed streams, and this method is where
 * a consumer is meant to see them as one console again. The concatenation happens HERE, on the read
 * side, and is never persisted — a merged run on disk would be the second byte authority that
 * `ADR-M1 §D2` exists to prevent.
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
 *   not clamped — see the class KDoc.
 */
    fun read(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        after: OutputCursor?,
        maxBytes: Int = DEFAULT_PAGE_BYTES,
    ): Result {
        val store = OutputPlaneProvider.storeFor(controlDirRoot)
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

            val cursor = if (address.stream == after?.stream) after!!
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
     * OBS-C2.3 gives an operation two channel streams, so "byte 30 of this console" is only
     * meaningful once the two are put in an order. That order is the fixed channel order used by
     * [read], which makes a range and a page agree by construction: the same offsets name the same
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
        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val ordered = OutputPlaneProvider.streamsOf(runId, opId).all

        // A channel that was never opened contributes zero bytes to the merged space, which is what
        // makes one range read correct for both plain and `returnStdout` invocations.
        val bounds = ordered.map { store.committedExtent(it.stream) ?: 0L }
        val total = bounds.sum()
        require(from >= 0 && to >= from && to <= total) {
            "range [$from, $to) is outside this operation's committed console of $total bytes"
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
    }
}

/**
 * M1-P3 — the console reader CLI.
 *
 * ```text
 * pipeline console --control-dir <path> <runId> <opId> [--max-bytes N] [--after-cursor TOKEN]
 * pipeline console --control-dir <path> <runId> <opId> --range FROM:TO
 * ```
 *
 * The console transcript goes to **stdout as bytes**, not as a rendered line, because a consumer
 * that has to strip a CLI's framing out of a transcript will eventually strip the wrong thing. The
 * continuation token goes to **stderr**, which is the convention [MainEventsCli] already uses and
 * keeps the two planes from colliding.
 */
object MainConsoleCli {

    fun main(args: Array<String>): Int {
        var controlDir: String? = null
        var runId: String? = null
        var opId: String? = null
        var maxBytes = ConsoleReadService.DEFAULT_PAGE_BYTES
        var maxBytesArg: String? = null
        var afterCursor: String? = null
        var range: String? = null

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                "--control-dir" -> controlDir = args.getOrNull(++i)
                // Parsed as text and validated below, so a flag that does not parse becomes a refusal
                // rather than the default. `--max-bytes abc` used to read as DEFAULT_PAGE_BYTES with
                // exit 0 — the same shape as a command that silently did something other than what was
                // asked. AUD-04, aligned with [MainEventsCli].
                "--max-bytes" -> maxBytesArg = args.getOrNull(++i)
                "--after-cursor" -> afterCursor = args.getOrNull(++i)
                "--range" -> range = args.getOrNull(++i)
                else -> when {
                    // AUD-04. This arm has no `else` of its own, so an unknown `--flag` matched it,
                    // satisfied neither condition, and completed as Unit — parsed as nothing, exit 0.
                    // A third positional did the same: dropped while the first two were kept.
                    arg.startsWith("--") -> {
                        System.err.println("Error: unknown option: $arg")
                        return 2
                    }
                    runId == null -> runId = arg
                    opId == null -> opId = arg
                    // The command reads ONE run and ONE op.
                    else -> {
                        System.err.println("Error: unexpected extra argument: $arg")
                        return 2
                    }
                }
            }
            i++
        }

        if (controlDir == null || runId == null || opId == null) {
            System.err.println(
                "Usage: pipeline console --control-dir <path> <runId> <opId> " +
                    "[--max-bytes N] [--after-cursor TOKEN] | --range FROM:TO",
            )
            return 2
        }

        // A flag that does not parse must not become the default.
        if (maxBytesArg != null) {
            val parsed = maxBytesArg.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                System.err.println("Error: --max-bytes must be a positive integer, got: $maxBytesArg")
                return 2
            }
            maxBytes = parsed
        }

        val root = Path.of(controlDir)
        if (!java.nio.file.Files.isDirectory(root)) {
            System.err.println("Error: control dir not found: $controlDir")
            return 2
        }

        if (maxBytes <= 0) {
            System.err.println("Error: --max-bytes must be positive, got $maxBytes")
            return 2
        }

        // An event cursor pasted here is a decode failure, not a silent offset into the wrong
        // bytes. The distinct token prefixes are the whole reason.
        val after = afterCursor?.let { token ->
            OutputCursor.decode(token) ?: run {
                System.err.println("Error: not an output cursor token: $token")
                return 2
            }
        }

        val result = if (range != null) {
            val bounds = range.split(':')
            val from = bounds.getOrNull(0)?.toLongOrNull()
            val to = bounds.getOrNull(1)?.toLongOrNull()
            if (bounds.size != 2 || from == null || to == null) {
                System.err.println("Error: --range must be FROM:TO with integers, got $range")
                return 2
            }
            ConsoleReadService.readRange(root, runId, opId, from, to)
        } else {
            ConsoleReadService.read(root, runId, opId, after, maxBytes)
        }

        return emit(result, System.out, System.err)
    }

    /** Emit the result. Split out so a test drives it without capturing the real process streams. */
    fun emit(result: ConsoleReadService.Result, out: PrintStream, err: PrintStream): Int =
        when (result) {
            is ConsoleReadService.Result.Refused -> {
                err.println(ConsoleReadService.renderRefusal(result.reason))
                1
            }
            is ConsoleReadService.Result.Page -> {
                // Raw bytes. The transcript is not text-shaped in general and re-encoding it here
                // would corrupt anything that is not valid UTF-8.
                out.write(result.page.bytes)
                out.flush()
                result.page.next?.let { err.println(it.encode()) }
                0
            }
        }

    /** Convenience for a consumer that wants the whole stream; bounded by the store, not by memory. */
    fun readWholeStream(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        maxBytes: Int = ConsoleReadService.DEFAULT_PAGE_BYTES,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = null
        var pages = 0
        while (true) {
            // Capped: a store that never ends the stream would otherwise loop here forever, in
            // production code, for any consumer that asks for the whole transcript.
            check(++pages < 1_000_000) { "readWholeStream did not terminate after $pages pages" }
            val result = ConsoleReadService.read(controlDirRoot, runId, opId, cursor, maxBytes)
            when (result) {
                is ConsoleReadService.Result.Refused -> throw IllegalStateException(
                    ConsoleReadService.renderRefusal(result.reason),
                )
                is ConsoleReadService.Result.Page -> {
                    out.write(result.page.bytes)
                    cursor = result.page.next ?: break
                }
            }
        }
        return out.toByteArray()
    }

    internal fun decodeForTest(token: String): OutputCursor? = OutputCursor.decode(token)

    /**
 * The stream id a consumer is handed for one operation's console.
 *
 * OBS-C2.3: the console is the operation's channel-addressed streams, and a reader that wants one
 * channel asks for it by name. This stays a single helper so the shape is minted in ONE place — the
 * same reason [OutputStreamAddress.parse] reads it back from the two ends.
 */
    internal fun streamIdFor(runId: String, opId: String, channel: dev.rubentxu.pipeline.v2.output.OutputChannel): OutputStreamId =
        OutputPlaneProvider.streamId(runId, opId, channel)

    internal fun utf8(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
}
