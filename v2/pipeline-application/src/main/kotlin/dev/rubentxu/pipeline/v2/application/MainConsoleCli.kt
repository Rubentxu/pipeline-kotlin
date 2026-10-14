package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrame
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

    /**
     * R4 / WU-R4.6 — frames emitted per continuation by default on the per-frame path.
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
    val format: MainConsoleCli.ConsoleFormat

    /** Continue after [after] (`null` starts at the beginning), emitting at most [maxBytes] bytes. */
    data class Paged(
        override val controlDir: String,
        override val runId: String,
        override val opId: String,
        val after: OutputCursor?,
        val maxBytes: Int,
        override val format: MainConsoleCli.ConsoleFormat,
    ) : ConsoleReadRequest

    /** The committed span `[from, to)` of the merged offset space. No continuation is produced. */
    data class Range(
        override val controlDir: String,
        override val runId: String,
        override val opId: String,
        val from: Long,
        val to: Long,
        override val format: MainConsoleCli.ConsoleFormat,
    ) : ConsoleReadRequest

    /**
     * R4 / WU-R4.6 — per-frame walk.
     *
     * Continue after [afterOrdinal] (`-1` starts at the first frame), emitting at most
     * [frameLimit] frames in observation order. Unlike [Paged] (byte-budgeted, cursor-resumable)
     * and [Range] (byte span, no continuation), this path answers the question only the frame
     * index can: "what did this operation publish, frame by frame, in the order PipelineK
     * observed it?" The JSONL emitter walks this path and writes one record per real frame;
     * the per-record [OutputFrame.ordinal] / [OutputFrame.from] / [OutputFrame.to] ride on the wire.
     *
     * [frameLimit] is the loop bound. The constructor refuses non-positive values, so the
     * caller learns the rule at compile + admission rather than at the runtime loop.
     */
    data class Frames(
        override val controlDir: String,
        override val runId: String,
        override val opId: String,
        val afterOrdinal: Long,
        val frameLimit: Int,
        override val format: MainConsoleCli.ConsoleFormat,
    ) : ConsoleReadRequest {
        init {
            require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }
            require(afterOrdinal >= -1L) { "afterOrdinal must be -1 (from start) or non-negative, got $afterOrdinal" }
        }
    }
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

    /**
     * R4 / WU-R4.6 — a byte-budgeted or cursor-resume flag offered to a per-frame read, or vice
     * versa.
     *
     * The per-frame path answers "what did the operation publish, in observation order?" and
     * bounds the answer with [ConsoleReadRequest.Frames.frameLimit] (frame count) and
     * [ConsoleReadRequest.Frames.afterOrdinal] (frame ordinal). `--max-bytes`, `--after-cursor`,
     * and `--range` are the byte-budgeted / cursor-resume / byte-span affordances of the
     * legacy path; mixing them with `--frames` is a category error, not a numeric one. The
     * rendered refusal names the option so the caller deletes one token rather than editing two.
     */
    data class IncompatibleWithFrames(val option: String) : ConsoleCliRefusal {
        override fun toString(): String =
            "IncompatibleWithFrames($option): '$option' is a byte-budgeted / byte-cursor / " +
                "byte-span flag and has no meaning on the per-frame path. The per-frame path is " +
                "bounded by --frame-limit (frame count) and resumes by --after-ordinal (frame " +
                "ordinal), not by bytes. Drop '$option' to read frames, or drop --frames to read " +
                "bytes."
    }

    /**
     * R4 / WU-R4.3 — `--format` was given a value that is neither `text` nor `jsonl`.
     *
     * Distinct from [UnknownOption] (which is about a flag the verb does not recognise) and from
     * [MissingOptionValue] (the flag had no value). The closed [MainConsoleCli.ConsoleFormat] ADT
     * has exactly two cases; this refusal names the offending value so the caller edits one token
     * rather than guessing which one.
     */
    data class UnknownFormat(val value: String, val accepted: List<String>) : ConsoleCliRefusal
}

/** One stable line per refusal, so a script can branch on the reason. */
fun renderConsoleCliRefusal(refusal: ConsoleCliRefusal): String = when (refusal) {
    ConsoleCliRefusal.MissingArgument ->
        "Usage: pipeline console --control-dir <path> <runId> <opId> " +
            "[--max-bytes N] [--after-cursor TOKEN] " +
            "[--format text|jsonl] " +
            "| --range FROM:TO [--format text|jsonl]"
    is ConsoleCliRefusal.UnknownOption -> "Error: unknown option: ${refusal.option}"
    is ConsoleCliRefusal.UnexpectedArgument -> "Error: unexpected extra argument: ${refusal.value}"
    is ConsoleCliRefusal.NotAnInteger ->
        "Error: ${refusal.option} must be a positive integer, got: ${refusal.value}"
    is ConsoleCliRefusal.NotARange -> "Error: --range must be FROM:TO with integers, got ${refusal.value}"
    is ConsoleCliRefusal.NotACursorToken -> "Error: not an output cursor token: ${refusal.token}"
    is ConsoleCliRefusal.ControlDirNotFound -> "Error: control dir not found: ${refusal.path}"
    is ConsoleCliRefusal.MissingOptionValue -> "Error: ${refusal.option} requires a value"
    is ConsoleCliRefusal.IncompatibleWithRange -> "Error: ${refusal.toString()}"
    is ConsoleCliRefusal.IncompatibleWithFrames -> "Error: ${refusal.toString()}"
    is ConsoleCliRefusal.UnknownFormat ->
        "Error: --format must be one of ${refusal.accepted.joinToString("|")}, got: ${refusal.value}"
}

/**
 * M1-P3 — the console reader CLI.
 *
 * ```text
 * pipeline console --control-dir <path> <runId> <opId> [--max-bytes N] [--after-cursor TOKEN]
 * pipeline console --control-dir <path> <runId> <opId> --range FROM:TO
 * pipeline console --control-dir <path> <runId> <opId> --format text|jsonl ...
 * ```
 *
 * The console transcript goes to **stdout as bytes**, not as a rendered line, because a consumer
 * that has to strip a CLI's framing out of a transcript will eventually strip the wrong thing. The
 * continuation token goes to **stderr**, which is the convention [MainEventsCli] already uses and
 * keeps the two planes from colliding. A refusal is therefore also stderr-only: it must never reach
 * stdout, because a consumer reading bytes there cannot tell a refusal from an empty transcript.
 *
 * ## R4 / WU-R4.3 — `--format text | jsonl`
 *
 * The default is `text` (raw bytes to stdout, unchanged from M1-P3). `--format jsonl` switches
 * the emitter to [ObservationJsonLines]: one record per page, with the canonical
 * `{type:"output", channel, operation, ordinal, from, to, text/base64}` shape, the same one
 * `pipelinek observe --view console --format jsonl` already produces. Stdout stays the protocol
 * surface (one record per line, newline-terminated, flushed); the continuation token still
 * goes to stderr, so a script can resume without parsing the jsonl stream for control bytes.
 *
 * The jsonl path is bounded to the operation's merged page; a future slice can wire per-frame
 * records through `FrameIndexedObservationOutputReader` once the per-operation index is
 * available (CON-2.1/2.2 follow-up).
 *
 * ## Three steps, in that order
 *
 * [MainConsoleCliAdmission.parseTokens] collects argv and decides nothing. [admit] validates — and
 * validates CONFLICTS before TYPES, so `--range 0:10 --max-bytes abc` says the budget has no meaning
 * there rather than complaining about the number, which would send the caller to edit a token they
 * should delete. [MainConsoleCli.execute] interprets the admitted request.
 */
object MainConsoleCli {

    /**
     * R4 / WU-R4.3 — the closed ADT of console output formats.
     *
     * `TEXT` is the legacy raw-bytes path (unchanged from M1-P3). `JSONL` is the canonical
     * machine-readable projection [ObservationJsonLines] emits. Adding a third case is a
     * compile-time break for every caller of [ConsoleReadRequest.format] and for the emit
     * dispatch in [MainConsoleCli.emit].
     */
    enum class ConsoleFormat(val token: String) {
        /** Raw bytes to stdout. Default. */
        TEXT("text"),
        /** One [ObservationJsonLines] record per line. */
        JSONL("jsonl"),
        ;

        companion object {
            fun parse(token: String): ConsoleFormat? = values().firstOrNull { it.token == token }
        }
    }

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
                // R4 / WU-R4.6 — the dispatch is on the request variant: Paged / Range go
                // through the byte-budgeted [emit] path (unchanged from WU-R4.3); Frames
                // goes through the per-frame path, which reads the index directly and
                // emits one record per real frame.
                when (request) {
                    is ConsoleReadRequest.Frames ->
                        emitFrames(readFrames(request, root), request, System.out, System.err)
                    is ConsoleReadRequest.Paged,
                    is ConsoleReadRequest.Range ->
                        emit(execute(request, root), request, System.out, System.err)
                }
            }
        }
    }

    /**
     * R4 / WU-R4.6 — per-frame read.
     *
     * Uses [dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader]
     * (the WU-R4.1.3 per-op public seam) to walk the operation's frames in observation order
     * starting at [ConsoleReadRequest.Frames.afterOrdinal], bounded by [ConsoleReadRequest.Frames.frameLimit].
     * The opId filter is applied on the read side, exactly as WU-R4.1.3 documents it.
     */
    private fun readFrames(
        request: ConsoleReadRequest.Frames,
        root: Path,
    ): dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead {
        val store = dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.storeForReading(root)
        val reader = dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader(
            index = store.frameIndex(),
            bytes = store,
            tails = store,
        )
        return reader.readOutput(request.runId, request.afterOrdinal, request.frameLimit)
    }

    /**
     * R4 / WU-R4.6 — emit the per-frame result.
     *
     * The dispatch is on the closed [ConsoleFormat] ADT:
     *  - `TEXT` writes the merged bytes of the page's frames to stdout (in observation order,
     *    one frame after another), with a continuation token on stderr carrying the next
     *    frame ordinal so a `--follow` consumer can resume.
     *  - `JSONL` writes one [ObservationJsonLines] record per real frame, in observation
     *    order, each carrying the frame's true [OutputFrame.ordinal] / [OutputFrame.from] /
     *    [OutputFrame.to] / [OutputFrame.channel] plus the operation id parsed from the
     *    stream. This is the per-frame wire shape [ObservationJsonLines] was already wired
     *    to emit (the WU-R4.3 path was a synthetic-merged-page workaround).
     */
    fun emitFrames(
        result: dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead,
        request: ConsoleReadRequest.Frames,
        out: PrintStream,
        err: PrintStream,
    ): Int = when (result) {
        is dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead.Refused -> {
            err.println(ConsoleReadService.renderRefusal(result.reason))
            1
        }

        is dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead.Page -> {
            val page = result.page
            when (request.format) {
                ConsoleFormat.TEXT -> {
                    // Concatenate the per-frame bytes. Frames in the page are already in
                    // observation order (the index owns that ordering). A consumer that
                    // wanted a per-frame line keeps the per-frame record on the wire by
                    // switching to --format jsonl; this path is the bytes-only view.
                    for (record in page.records) out.write(record.bytes)
                    out.flush()
                    if (page.moreFrames) {
                        // The continuation token is a decimal frame ordinal, not a byte
                        // cursor: the per-frame path is bounded by frame count and resumes
                        // by frame ordinal, and that is the only honest way to express it.
                        err.println(encodeFrameOrdinal(request.afterOrdinal + page.records.size))
                    }
                    0
                }
                ConsoleFormat.JSONL -> {
                    // Per-frame records: one jsonl line per real frame, in observation order.
                    // The encoder [ObservationJsonLines.writeTo] flushes after each record
                    // so a `--follow` consumer does not wait for the page to fill. The
                    // writer wraps `out` in an OutputStreamWriter so a PrintStream reaches
                    // the Writer overload without losing the auto-flush behaviour the
                    // emitter relies on.
                    java.io.OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines.writeTo(
                            page.records,
                            writer,
                        )
                    }
                    if (page.moreFrames) {
                        err.println(encodeFrameOrdinal(request.afterOrdinal + page.records.size))
                    }
                    0
                }
            }
        }
    }

    /**
     * R4 / WU-R4.6 — frame-ordinal continuation token, distinct from [OutputCursor.TOKEN_PREFIX].
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

            // `Frames` is handled in [main] via [readFrames] + [emitFrames] before [execute]
            // is reached. The branch is exhaustive; this case is unreachable but makes the
            // closed [ConsoleReadRequest] ADT explicit to the type-checker.
            is ConsoleReadRequest.Frames ->
                error("unreachable: Frames requests bypass execute() and use readFrames+emitFrames instead")
        }

    /**
     * Emit the result. Split out so a test drives it without capturing the real process streams.
     *
     * R4 / WU-R4.3 — the format dispatch is a `when` over the closed [ConsoleFormat] ADT (two
     * cases: `TEXT` raw bytes, `JSONL` envelope). The `JSONL` path builds a synthetic
     * [ObservationRecord.Output] from the page so the existing [ObservationJsonLines] writer
     * stays the only encoder; an [ObservationFrameOrdinal] of `0` and a synthetic
     * [OutputFrame] are honest about the fact that the merged page is one reader-side
     * projection, not a kernel-recorded frame.
     */
    fun emit(
        result: ConsoleReadService.Result,
        request: ConsoleReadRequest,
        out: PrintStream,
        err: PrintStream,
    ): Int = when (result) {
        is ConsoleReadService.Result.Refused -> {
            err.println(ConsoleReadService.renderRefusal(result.reason))
            1
        }
        is ConsoleReadService.Result.Page -> {
            when (request.format) {
                ConsoleFormat.TEXT -> {
                    // Raw bytes. The transcript is not text-shaped in general and re-encoding it
                    // here would corrupt anything that is not valid UTF-8.
                    out.write(result.page.bytes)
                    out.flush()
                    result.page.next?.let { err.println(it.encode()) }
                    0
                }
                ConsoleFormat.JSONL -> emitJsonlPage(result.page, out, err)
            }
        }
    }

    /**
     * R4 / WU-R4.3 — emit one [ObservationJsonLines] record for a merged [OutputPage].
     *
     * The page names one stream (`page.stream`) and a byte range (`page.from..page.committedEnd`).
     * We construct an [ObservationRecord.Output] carrying the page's bytes so the existing
     * encoder handles the text/base64 split (`ObservationJsonLines.encodeOutputLine`). The
     * ordinal is `0L` (synthetic) and the [OutputFrame] is named from the page's reported
     * stream and range; the page's "next" cursor still goes to stderr so a `--follow` consumer
     * can resume without parsing the jsonl stream for control bytes.
     */
    private fun emitJsonlPage(
        page: OutputPage,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        // The merged page's stream id names the channel that contributed the FIRST byte
        // (see [ConsoleReadService.read]). `OutputStreamAddress.parse` reads it back; an
        // unknown shape falls back to STDOUT because a `null` channel would be a fact a
        // consumer would have to invent a default for, and `STDOUT` is the honest answer
        // when the read could not name anything else.
        val address = OutputStreamAddress.parse(page.stream)
        val channel = address?.channel ?: OutputChannel.STDOUT
        val syntheticFrame = OutputFrame(
            ordinal = 0L,
            stream = page.stream,
            channel = channel,
            from = page.from,
            to = page.committedEnd,
        )
        val record = ObservationRecord.Output(
            frame = syntheticFrame,
            bytes = page.bytes,
            text = String(page.bytes, StandardCharsets.UTF_8),
        )
        val writer = java.io.PrintWriter(java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), true)
        ObservationJsonLines.writeTo(listOf(record), writer)
        writer.flush()
        page.next?.let { err.println(it.encode()) }
        return 0
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
    /** R4 / WU-R4.6 — `--frames` opts the admission into the per-frame read path. */
    val frames: Boolean = false,
    /** R4 / WU-R4.6 — `--frame-limit N` overrides the default frame count (1024). */
    val frameLimit: String? = null,
    /** R4 / WU-R4.6 — `--after-ordinal N` resumes from frame ordinal N (`-1` = from start). */
    val afterOrdinal: String? = null,
    /** R4 / WU-R4.3 — `--format` value as written; validated in [MainConsoleCliAdmission.validate]. */
    val formatToken: String? = null,
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
        var formatToken: String? = null
        var frames = false
        var frameLimit: String? = null
        var afterOrdinal: String? = null
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when (arg) {
                // Every valued option consumes the NEXT token, and the four of them share one arm so
                // the rule cannot diverge between them. When there is no next token the flag is
                // present and its value is missing — a different fact from the flag being absent.
                // Reading both as null applied the default page size to a command that never
                // supplied one, and left the caller unable to tell which command they had run.
                "--control-dir", "--max-bytes", "--after-cursor", "--range",
                "--frame-limit", "--after-ordinal", "--format" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return ConsoleCliParse.Rejected(ConsoleCliRefusal.MissingOptionValue(arg))
                    when (arg) {
                        "--control-dir" -> controlDir = value
                        "--max-bytes" -> maxBytes = value
                        "--after-cursor" -> afterCursor = value
                        "--range" -> range = value
                        "--frame-limit" -> frameLimit = value
                        "--after-ordinal" -> afterOrdinal = value
                        "--format" -> formatToken = value
                    }
                    index++
                }
                // Boolean opt-in: a presence-only flag. Reading it twice is a no-op so a
                // redundant `--frames --frames` does not flip the value.
                "--frames" -> frames = true
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
            ConsoleCliTokens(
                controlDir, runId, opId, maxBytes, afterCursor, range,
                frames, frameLimit, afterOrdinal, formatToken,
            ),
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

        // R4 / WU-R4.6 — `--frames` is mutually exclusive with the byte-budgeted / cursor /
        // range flags. The category error is decided before integer parsing, so a caller that
        // mixed --frames with --max-bytes=abc still gets a refusal that names --max-bytes,
        // not a number-format error that would send them to edit the wrong token.
        if (tokens.frames) {
            val framesIncompatible = when {
                range != null -> "--range"
                tokens.maxBytes != null -> "--max-bytes"
                tokens.afterCursor != null -> "--after-cursor"
                else -> null
            }
            if (framesIncompatible != null) {
                return rejected(ConsoleCliRefusal.IncompatibleWithFrames(framesIncompatible))
            }
        }

        // R4 / WU-R4.3 — parse `--format` into the closed [MainConsoleCli.ConsoleFormat] ADT
        // (two cases: text, jsonl). An unknown value refuses with a typed reason that names
        // the accepted list, so a caller edits one token rather than guessing.
        val format = tokens.formatToken?.let { token ->
            MainConsoleCli.ConsoleFormat.parse(token)
                ?: return rejected(
                    ConsoleCliRefusal.UnknownFormat(
                        value = token,
                        accepted = MainConsoleCli.ConsoleFormat.values().map { it.token },
                    ),
                )
        } ?: MainConsoleCli.ConsoleFormat.TEXT

        if (tokens.frames) return admitFrames(controlDir, runId, opId, tokens, format)
        if (range != null) return admitRange(controlDir, runId, opId, range, format)
        return admitPage(controlDir, runId, opId, tokens, format)
    }

    private fun admitRange(
        controlDir: String,
        runId: String,
        opId: String,
        range: String,
        format: MainConsoleCli.ConsoleFormat,
    ): ConsoleCliAdmission {
        val bounds = range.split(':')
        val from = bounds.getOrNull(0)?.toLongOrNull()
        val to = bounds.getOrNull(1)?.toLongOrNull()
        if (bounds.size != 2 || from == null || to == null) {
            return rejected(ConsoleCliRefusal.NotARange(range))
        }
        return ConsoleCliAdmission.Admitted(ConsoleReadRequest.Range(controlDir, runId, opId, from, to, format))
    }

    private fun admitPage(
        controlDir: String,
        runId: String,
        opId: String,
        tokens: ConsoleCliTokens,
        format: MainConsoleCli.ConsoleFormat,
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

        return ConsoleCliAdmission.Admitted(
            ConsoleReadRequest.Paged(controlDir, runId, opId, after, maxBytes, format),
        )
    }

    /**
     * R4 / WU-R4.6 — admit the per-frame read path.
     *
     * `--frame-limit N` defaults to 1024. `--after-ordinal N` defaults to -1 (from the start).
     * Both values are typed: a non-positive `--frame-limit` refuses, a negative `--after-ordinal`
     * other than -1 refuses. The byte-budgeted / cursor / range flags are refused earlier in
     * [validate] under [ConsoleCliRefusal.IncompatibleWithFrames], so this method never sees them.
     */
    private fun admitFrames(
        controlDir: String,
        runId: String,
        opId: String,
        tokens: ConsoleCliTokens,
        format: MainConsoleCli.ConsoleFormat,
    ): ConsoleCliAdmission {
        val frameLimit = tokens.frameLimit?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: return rejected(ConsoleCliRefusal.NotAnInteger("--frame-limit", value))
        } ?: ConsoleReadService.DEFAULT_FRAME_LIMIT

        val afterOrdinal = tokens.afterOrdinal?.let { value ->
            value.toLongOrNull()?.takeIf { it >= -1L }
                ?: return rejected(ConsoleCliRefusal.NotAnInteger("--after-ordinal", value))
        } ?: -1L

        return ConsoleCliAdmission.Admitted(
            ConsoleReadRequest.Frames(controlDir, runId, opId, afterOrdinal, frameLimit, format),
        )
    }

    private fun rejected(refusal: ConsoleCliRefusal): ConsoleCliAdmission =
        ConsoleCliAdmission.Rejected(refusal)
}
