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
        // M1-A additions: the Output follow contract introduced two
        // new refusal cases. They can reach `console-refused` when a
        // follow produces a Refused event the application layer
        // surfaces. Render them with stable, script-branchable text.
        is OutputRefusal.StreamLostRetention ->
            "console-refused: stream-lost-retention stream=${reason.stream.value} last-committed=${reason.lastCommitted}"
        is OutputRefusal.FollowCancelled ->
            "console-refused: follow-cancelled run=${reason.runId}"
        // M1-B addition: a thrown exception from the read ports is
        // caught at the follower boundary and surfaced as a typed
        // refusal. The console renders the cause as a short string
        // so a script can branch on the prefix.
        is OutputRefusal.StorageError ->
            "console-refused: storage-error cause=${reason.cause}"
        // M3 additions: the digest/pin/retention surface introduced four
        // new refusal cases. Render them with stable, script-branchable text
        // so consumers can grep the prefix. The sealed ADT discipline means
        // adding a case forces this branch list to grow here at compile
        // time — the discipline the audit §B.2 (d) requires.
        is OutputRefusal.RetentionGap ->
            "console-refused: retention-gap stream=${reason.stream.value} last-committed=${reason.lastCommitted}"
        is OutputRefusal.Corrupt ->
            "console-refused: corrupt stream=${reason.stream.value} reason=${reason.reason}"
        OutputRefusal.Unavailable ->
            "console-refused: unavailable"
        is OutputRefusal.RangeLostRetention ->
            "console-refused: range-lost-retention stream=${reason.stream.value} last-committed=${reason.lastCommitted}"
    }
}

/**
 * What `pipeline console` was asked to read.
 *
 * ## Why this is a type and not a branch
 *
 * The two readings are not variants of one thing. A [Paged] read CONTINUES after a cursor and is
 * bounded by a budget. A [Range] read NAMES A SPAN of the merged offset space and is not resumable,
 * because continuation is expressed with a cursor and a span across two channel streams has no
 * honest single cursor.
 *
 * `main` used to hold a `String?` for `--range`, another for `--after-cursor`, and pick between them
 * with an `if`. Nothing in that program said the two are different requests, so `--range 0:10
 * --max-bytes 5` was not refused — the budget was silently discarded, and `readRange`'s `require`
 * then threw out of `main` for a span no run had committed. A third flag that cannot apply is a
 * refusal, and refusal is a case of the request type's absence, not a branch that quietly picks a
 * different case.
 */
sealed interface ConsoleReadRequest {
    val controlDir: String
    val runId: String
    val opId: String

    /** Continue after [after] (`null` starts at the beginning), emitting at most [maxBytes] bytes. */
    data class Paged(
        override val controlDir: String,
        override val runId: String,
        override val opId: String,
        val after: OutputCursor?,
        val maxBytes: Int,
    ) : ConsoleReadRequest

    /** The committed span `[from, to)` of the merged offset space. No continuation is produced. */
    data class Range(
        override val controlDir: String,
        override val runId: String,
        override val opId: String,
        val from: Long,
        val to: Long,
    ) : ConsoleReadRequest
}

/**
 * Why `pipeline console` will not do what it was asked.
 *
 * Every case is decided before a store is opened, except [ConsoleCliRefusal.ControlDirNotFound],
 * which is the first thing that touches the filesystem. A refusal names the token that is wrong,
 * because a diagnostic that does not is a diagnostic the caller has to guess from.
 */
sealed interface ConsoleCliRefusal {
    data object MissingArgument : ConsoleCliRefusal
    data class UnknownOption(val option: String) : ConsoleCliRefusal
    data class UnexpectedArgument(val value: String) : ConsoleCliRefusal
    data class NotAnInteger(val option: String, val value: String) : ConsoleCliRefusal
    data class NotARange(val value: String) : ConsoleCliRefusal
    data class NotACursorToken(val token: String) : ConsoleCliRefusal
    data class ControlDirNotFound(val path: String) : ConsoleCliRefusal

    /**
     * A valued option was written and its value was not.
     *
     * Distinct from [MissingArgument], which is about a REQUIRED positional that was not supplied.
     * This one is about an option that IS present, so the caller has already made a choice and must
     * learn that the choice is incomplete. Keeping them apart is not cosmetic: conflating them is how
     * `--max-bytes` at the end of the command line read as "no `--max-bytes`" and silently took the
     * default page size.
     */
    data class MissingOptionValue(val option: String) : ConsoleCliRefusal

    /**
     * A reader flag offered to a range read, or the other way round.
     *
     * [spansNothing] is the argument, in one token: the flag does not shrink the answer, it does not
     * change it, and it produces no output at all. There is no documented meaning to honour it by.
     */
    data class IncompatibleWithRange(val option: String) : ConsoleCliRefusal {
        override fun toString(): String =
            "IncompatibleWithRange($option): --range names a span of the committed console, and " +
                "'$option' has no meaning over a span. A range is not resumable, because continuation " +
                "is expressed with a cursor and a span across two channel streams has no single one. " +
                "Drop '$option', or read a page with --after-cursor '$option' instead."
    }
}

/** One stable line per refusal, so a script can branch on the reason. */
fun renderConsoleCliRefusal(refusal: ConsoleCliRefusal): String = when (refusal) {
    ConsoleCliRefusal.MissingArgument ->
        "Usage: pipeline console --control-dir <path> <runId> <opId> " +
            "[--max-bytes N] [--after-cursor TOKEN] | --range FROM:TO"
    is ConsoleCliRefusal.UnknownOption -> "Error: unknown option: ${refusal.option}"
    is ConsoleCliRefusal.UnexpectedArgument -> "Error: unexpected extra argument: ${refusal.value}"
    is ConsoleCliRefusal.NotAnInteger ->
        "Error: ${refusal.option} must be a positive integer, got: ${refusal.value}"
    is ConsoleCliRefusal.NotARange -> "Error: --range must be FROM:TO with integers, got ${refusal.value}"
    is ConsoleCliRefusal.NotACursorToken -> "Error: not an output cursor token: ${refusal.token}"
    is ConsoleCliRefusal.ControlDirNotFound -> "Error: control dir not found: ${refusal.path}"
    is ConsoleCliRefusal.MissingOptionValue -> "Error: ${refusal.option} requires a value"
    is ConsoleCliRefusal.IncompatibleWithRange -> "Error: ${refusal.toString()}"
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
 * keeps the two planes from colliding. A refusal is therefore also stderr-only: it must never reach
 * stdout, because a consumer reading bytes there cannot tell a refusal from an empty transcript.
 *
 * ## Three steps, in that order
 *
 * [MainConsoleCliAdmission.parseTokens] collects argv and decides nothing. [admit] validates — and
 * validates CONFLICTS before TYPES, so `--range 0:10 --max-bytes abc` says the budget has no meaning
 * there rather than complaining about the number, which would send the caller to edit a token they
 * should delete. [MainConsoleCli.execute] interprets the admitted request.
 */
object MainConsoleCli {

    fun main(args: Array<String>): Int = when (val admitted = MainConsoleCliAdmission.admit(args)) {
        is ConsoleCliAdmission.Rejected -> {
            System.err.println(renderConsoleCliRefusal(admitted.refusal))
            2
        }

        is ConsoleCliAdmission.Admitted -> {
            val request = admitted.request
            val root = Path.of(request.controlDir)
            // The first effect in the program. Everything above is a decision about the QUESTION;
            // this is the question being put to a filesystem that may not have the answer.
            if (!java.nio.file.Files.isDirectory(root)) {
                System.err.println(
                    renderConsoleCliRefusal(ConsoleCliRefusal.ControlDirNotFound(request.controlDir)),
                )
                2
            } else {
                emit(execute(request, root), System.out, System.err)
            }
        }
    }

    /** Interpret one admitted request. The branch is exhaustive because the request says which. */
    private fun execute(request: ConsoleReadRequest, root: Path): ConsoleReadService.Result =
        when (request) {
            is ConsoleReadRequest.Paged -> ConsoleReadService.read(
                root,
                request.runId,
                request.opId,
                request.after,
                request.maxBytes,
            )

            is ConsoleReadRequest.Range -> ConsoleReadService.readRange(
                root,
                request.runId,
                request.opId,
                request.from,
                request.to,
            )
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


/** argv as written, before anything has been decided. Pure: this step cannot refuse anything but shape. */
private data class ConsoleCliTokens(
    val controlDir: String? = null,
    val runId: String? = null,
    val opId: String? = null,
    val maxBytes: String? = null,
    val afterCursor: String? = null,
    val range: String? = null,
)

private sealed interface ConsoleCliParse {
    data class Collected(val tokens: ConsoleCliTokens) : ConsoleCliParse
    data class Rejected(val refusal: ConsoleCliRefusal) : ConsoleCliParse
}

private sealed interface ConsoleCliAdmission {
    data class Admitted(val request: ConsoleReadRequest) : ConsoleCliAdmission
    data class Rejected(val refusal: ConsoleCliRefusal) : ConsoleCliAdmission
}

/**
 * argv -> one admitted [ConsoleReadRequest], or one refusal.
 *
 * ## Conflicts are decided before types
 *
 * `--range 0:10 --max-bytes abc` is refused as "the budget has no meaning over a range", not as
 * "abc is not a number". The flag is wrong whatever its value, so answering about the value sends
 * the caller to edit a token they should delete — and if they fix the number, the run still does not
 * do what they asked. This ordering is a decision, not an accident, and CONSOLE-RANGE-BEATS-TYPE is
 * the row that keeps it.
 *
 * Nothing here opens a store. A reader flag that cannot apply is refused while it is still a
 * question about the QUESTION.
 */
private object MainConsoleCliAdmission {

    fun admit(args: Array<String>): ConsoleCliAdmission =
        when (val parsed = parseTokens(args)) {
            is ConsoleCliParse.Rejected -> ConsoleCliAdmission.Rejected(parsed.refusal)
            is ConsoleCliParse.Collected -> validate(parsed.tokens)
        }

    private fun parseTokens(args: Array<String>): ConsoleCliParse {
        var controlDir: String? = null
        var runId: String? = null
        var opId: String? = null
        var maxBytes: String? = null
        var afterCursor: String? = null
        var range: String? = null
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when (arg) {
                // Every valued option consumes the NEXT token, and the four of them share one arm so
                // the rule cannot diverge between them. When there is no next token the flag is
                // present and its value is missing — a different fact from the flag being absent.
                // Reading both as null applied the default page size to a command that never
                // supplied one, and left the caller unable to tell which command they had run.
                "--control-dir", "--max-bytes", "--after-cursor", "--range" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return ConsoleCliParse.Rejected(ConsoleCliRefusal.MissingOptionValue(arg))
                    when (arg) {
                        "--control-dir" -> controlDir = value
                        "--max-bytes" -> maxBytes = value
                        "--after-cursor" -> afterCursor = value
                        else -> range = value
                    }
                    index++
                }
                else -> {
                    // AUD-04. This arm has no `else` of its own, so an unknown `--flag` matched it,
                    // satisfied neither condition, and completed as Unit — parsed as nothing, exit 0.
                    // A third positional did the same: dropped while the first two were kept.
                    positional(arg, runId, opId)?.let { return ConsoleCliParse.Rejected(it) }
                    if (runId == null) runId = arg else opId = arg
                }
            }
            index++
        }
        return ConsoleCliParse.Collected(
            ConsoleCliTokens(controlDir, runId, opId, maxBytes, afterCursor, range),
        )
    }

    /** The command reads ONE run and ONE op, so there are exactly two positionals. */
    private fun positional(arg: String, runId: String?, opId: String?): ConsoleCliRefusal? = when {
        arg.startsWith("--") -> ConsoleCliRefusal.UnknownOption(arg)
        runId != null && opId != null -> ConsoleCliRefusal.UnexpectedArgument(arg)
        else -> null
    }

    private fun validate(tokens: ConsoleCliTokens): ConsoleCliAdmission {
        val controlDir = tokens.controlDir ?: return rejected(ConsoleCliRefusal.MissingArgument)
        val runId = tokens.runId ?: return rejected(ConsoleCliRefusal.MissingArgument)
        val opId = tokens.opId ?: return rejected(ConsoleCliRefusal.MissingArgument)

        val range = tokens.range
        val incompatible = when {
            range == null -> null
            tokens.afterCursor != null -> "--after-cursor"
            else -> "--max-bytes".takeIf { tokens.maxBytes != null }
        }
        if (range != null && incompatible != null) {
            return rejected(ConsoleCliRefusal.IncompatibleWithRange(incompatible))
        }

        if (range != null) return admitRange(controlDir, runId, opId, range)
        return admitPage(controlDir, runId, opId, tokens)
    }

    private fun admitRange(controlDir: String, runId: String, opId: String, range: String): ConsoleCliAdmission {
        val bounds = range.split(':')
        val from = bounds.getOrNull(0)?.toLongOrNull()
        val to = bounds.getOrNull(1)?.toLongOrNull()
        if (bounds.size != 2 || from == null || to == null) {
            return rejected(ConsoleCliRefusal.NotARange(range))
        }
        return ConsoleCliAdmission.Admitted(ConsoleReadRequest.Range(controlDir, runId, opId, from, to))
    }

    private fun admitPage(
        controlDir: String,
        runId: String,
        opId: String,
        tokens: ConsoleCliTokens,
    ): ConsoleCliAdmission {
        val maxBytes = tokens.maxBytes?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: return rejected(ConsoleCliRefusal.NotAnInteger("--max-bytes", value))
        } ?: ConsoleReadService.DEFAULT_PAGE_BYTES

        // An event cursor pasted here is a decode failure, not a silent offset into the wrong
        // bytes. The distinct token prefixes are the whole reason.
        val after = tokens.afterCursor?.let { token ->
            OutputCursor.decode(token) ?: return rejected(ConsoleCliRefusal.NotACursorToken(token))
        }

        return ConsoleCliAdmission.Admitted(ConsoleReadRequest.Paged(controlDir, runId, opId, after, maxBytes))
    }

    private fun rejected(refusal: ConsoleCliRefusal): ConsoleCliAdmission =
        ConsoleCliAdmission.Rejected(refusal)
}
