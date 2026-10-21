package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.observation.FollowDecision
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.application.observation.RunPlane
import dev.rubentxu.pipeline.v2.application.console.ConsoleOperationSelection
import dev.rubentxu.pipeline.v2.application.console.OperationSelection
import dev.rubentxu.pipeline.v2.application.console.SelectionOutcome
import dev.rubentxu.pipeline.v2.application.console.SelectionRefusal
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.application.observation.followDecision
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * the consumable read side: console output, addressed by [OutputCursor].
 *
 * ## What this is for
 *
 * `OutputReadPort` existed for a long time and nothing called it. A contract nobody can call is a
 * design, not a product. This is the surface a consumer actually reaches for: a Jenkins
 * `LogStorage`, a Fabric ACL adapter, or [MainConsoleCli].
 *
 * ## Why it is not built on `EventHistoryReader`
 *
 * The event plane continues over a store-assigned **sequence**; the output plane continues over a
 * **committed byte offset**. Building a console reader on the event sequence conflates two planes:
 * a gap in the sequence is indistinguishable from output that was never emitted, and unrelated
 * event traffic perturbs the read. So this reads the Output Plane and only the Output Plane,
 * and its continuation token is `out-cursor-v1:`, never `evt-cursor-v1:`.
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
 * the Output Plane split split an operation's output into two channel-addressed streams, and this method is where
 * a consumer is meant to see them as one console again. The concatenation happens HERE, on the read
 * side, and is never persisted — a merged run on disk would be the second byte authority that
 * `the byte-offset rule` exists to prevent.
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
     * the Output Plane split gives an operation two channel streams, so "byte 30 of this console" is only
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
        // typed-output additions: the Output follow contract introduced two
        // new refusal cases. They can reach `console-refused` when a
        // follow produces a Refused event the application layer
        // surfaces. Render them with stable, script-branchable text.
        is OutputRefusal.StreamLostRetention ->
            "console-refused: stream-lost-retention stream=${reason.stream.value} last-committed=${reason.lastCommitted}"
        is OutputRefusal.FollowCancelled ->
            "console-refused: follow-cancelled run=${reason.runId}"
        // exception-tightening addition: a thrown exception from the read ports is
        // caught at the follower boundary and surfaced as a typed
        // refusal. The console renders the cause as a short string
        // so a script can branch on the prefix.
        is OutputRefusal.StorageError ->
            "console-refused: storage-error cause=${reason.cause}"
        // M3 additions: the digest/pin/retention surface introduced four
        // new refusal cases. Render them with stable, script-branchable text
        // so consumers can grep the prefix. The sealed ADT discipline means
        // adding a case forces this branch list to grow here at compile
        // time — the discipline the the audit gate that requires the dispatch order to be explicit requires.
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

    /**
     * the read-side audit chain — the operation to read, or `null` to read the WHOLE RUN and let the
     * [dev.rubentxu.pipeline.v2.application.console.OperationSelection] narrow it.
     *
     * Nullable because a required opId made `--stage`, `--step` and `--kind` unable to select
     * among operations at all: the read was scoped to ONE operation before any filter ran, so a
     * filter could only ever narrow within it. `--stage build` against `run-1-s0-0` could never
     * reach `run-1-s1-0`, whatever the filter said.
     *
     * `null` is therefore the shape a content filter REQUIRES, and the positional stays
     * mandatory for an unfiltered read so `console <runId> <opId>` keeps its meaning. Both may
     * be supplied together and then INTERSECT: the positional names one operation and the
     * selection may only remove from it.
     */
    val opId: String?
    val format: MainConsoleCli.ConsoleFormat

    /**
     * the read-side audit chain — the events journal this request may consult, or `null` when none was supplied.
     *
     * It is a PATH, not an opened store: the parser admits a request without performing an
     * effect (the builder-only contract — a builder describes, it does not interpret),
     * so opening the journal happens in `main`, after admission, where a failure is a typed
     * refusal rather than a parse error.
     *
     * `null` is a real, load-bearing value and not an omission. Stage/step NAMES and `--kind`
     * live only in the journal, so without it those axes cannot be honoured and
     * [dev.rubentxu.pipeline.v2.application.console.ConsoleOperationSelection.resolve] refuses
     * them by name. Defaulted so every existing construction stays valid and so the byte paths
     * ([Paged], [Range]) — which have no filter and need no journal — are unaffected.
     */
    val dbPath: String? get() = null

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
     * the per-frame read contract — per-frame walk.
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
        override val opId: String?,
        val afterOrdinal: Long,
        val frameLimit: Int,
        override val format: MainConsoleCli.ConsoleFormat,
    ) : ConsoleReadRequest {
        init {
            require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }
            require(afterOrdinal >= -1L) { "afterOrdinal must be -1 (from start) or non-negative, got $afterOrdinal" }
        }
    }

    /**
     * the console follow flag — `--follow` over the per-frame path.
     *
     * A thin loop over [Frames] reads. Each poll re-reads with
     * `afterOrdinal = (last seen ordinal) + 1` (or `-1` for the first poll), emits any new
     * frames, and continues until either:
     *
     *   - the read returns 0 new frames AND every known output stream is [Sealed]
     *     (the durable authority for "the writer closed this stream") — EOF, exit 0;
     *   - the [followConfig] has been polled [FollowConfig.maxPolls] times — exit 0;
     *   - the JVM receives a SIGINT/SIGTERM — exit 2.
     *
     * The cursor between polls is local state, fully reconstructible from the durable
     * authority ([afterOrdinal] + [frameLimit]); it is NOT journaled (the durable-read discipline).
     * A fresh process invocation starts over from [afterOrdinal]; a follow does NOT resume
     * across runs.
     *
     * The follow is reading, not deciding: the read-only decision contract is in force. The typed decision
     * ([FollowFrames]) is what reaches the loop; the loop is a pure interpreter of that
     * decision and must not mutate any journal state.
     */
    data class FollowFrames(
        override val controlDir: String,
        override val runId: String,
        override val opId: String?,
        val afterOrdinal: Long,
        val frameLimit: Int,
        val followConfig: FollowConfig,
        override val format: MainConsoleCli.ConsoleFormat,
        /**
         * the read-side audit chain — the same content filter a single [FramesFiltered] read carries.
         *
         * This field is the audit's **the audit point the dispatcher must answer**. `CliParser.consoleValidate` dispatched `--follow`
         * BEFORE the filter branch, so `console --frames --follow --stage build` admitted a
         * filterless [FollowFrames] and dropped the filter without a word: the operator asked to
         * see one stage and was shown the whole run, with a successful exit code.
         *
         * Defaulting to [ConsoleFrameFilters.None] rather than being required keeps every
         * existing construction valid, and the default is the honest value — "no filter was
         * asked for" is not the same as "no filter could be applied", and only the latter is a
         * defect.
         */
        val filters: ConsoleFrameFilters = ConsoleFrameFilters.None,
        override val dbPath: String? = null,
    ) : ConsoleReadRequest {
        init {
            require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }
            require(afterOrdinal >= -1L) { "afterOrdinal must be -1 (from start) or non-negative, got $afterOrdinal" }
        }
    }

    /**
     * the console filter contract — per-frame walk with content filters.
     *
     * Same per-frame contract as [Frames] (resume by frame ordinal, bounded by frame count) but
     * with a [filters] constraint applied at emit time. The filters name stage, step and event
     * kind; the verb main resolves those into a set of opIds by walking the events journal,
     * and only emits frames whose stream names a matching opId.
     *
     * A filter request without [filters] being [ConsoleFrameFilters.Active] is a [Frames] read;
     * a filter request with no `--frames` flag is refused at admission. The closed [filters] ADT
     * keeps the "no filters" path on the same sealed type as the active path so the verb main's
     * `when (request)` stays exhaustive without an `else` branch.
     */
    data class FramesFiltered(
        override val controlDir: String,
        override val runId: String,
        override val opId: String?,
        val afterOrdinal: Long,
        val frameLimit: Int,
        val filters: ConsoleFrameFilters,
        override val format: MainConsoleCli.ConsoleFormat,
        override val dbPath: String? = null,
    ) : ConsoleReadRequest {
        init {
            require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }
            require(afterOrdinal >= -1L) { "afterOrdinal must be -1 (from start) or non-negative, got $afterOrdinal" }
            require(filters is ConsoleFrameFilters.Active) {
                "FramesFiltered requires ConsoleFrameFilters.Active; got ${filters::class.simpleName}"
            }
        }
    }
}

/**
 * the console follow flag — the bounded knobs that turn a [ConsoleReadRequest.Frames] read into a loop.
 *
 * A closed payload rather than a flag bag: [pollIntervalMillis] is the wait between rounds,
 * [maxPolls] is the loop bound. The constructor refuses non-positive values so a malformed
 * `--follow-*` is a typed refusal at admission, not a silent degradation at runtime.
 *
 * The defaults (1000 ms / 60 polls) make one `--follow` invocation roughly a minute of
 * follow time, which is the budget the events verb established and which we keep here
 * for parity. The CLI surface admits `--follow-poll-interval` and `--follow-max-polls`
 * explicitly so an operator can tune them.
 */
data class FollowConfig(
    val pollIntervalMillis: Long,
    val maxPolls: Int,
) {
    init {
        require(pollIntervalMillis > 0) { "pollIntervalMillis must be positive, got $pollIntervalMillis" }
        require(maxPolls > 0) { "maxPolls must be positive, got $maxPolls" }
    }

    companion object {
        /** One poll per second, bounded to one minute by default. The events verb's defaults. */
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 1000L
        const val DEFAULT_MAX_POLLS: Int = 60

        val DEFAULT: FollowConfig = FollowConfig(DEFAULT_POLL_INTERVAL_MILLIS, DEFAULT_MAX_POLLS)
    }
}

/**
 * the follow-loop exit audit — why a `--follow` loop stopped.
 *
 * ## Why this is an ADT and not an `Int`
 *
 * The loop previously returned a bare `Int`, with `0` meaning both "the output lane is final" and
 * "we ran out of polls" and `1`/`2` meaning a refusal and a signal. Three facts about a run were
 * encoded in one number with no way to tell them apart, and two of them — a finished follow and an
 * abandoned one — were reported **identically**. A caller could not distinguish "this operation's
 * output is complete" from "we gave up looking", which is the same false-green the follow
 * decision itself was fixed for: a truncated transcript that looks like a clean exit.
 *
 * [exitCode] is a *projection* of the case, defined once here so the mapping is stated in exactly
 * one place and cannot drift per call site.
 *
 * ## Why `PollBudgetExhausted` is NOT zero
 *
 * Running out of polls means the loop never observed a terminal fact. It says nothing about the
 * run: the run may be alive, may have failed, may have finished a millisecond after the last poll.
 * Reporting that as success claims the product completed an observation it never completed.
 *
 * The cost is real and deliberate: this verb has **no event-plane authority** (it never opens the
 * journal — see the note on the filter resolver), so for an op that owns no stream — a silent
 * `sh("true")` — the poll budget is the ONLY exit, and such a follow now reports exhaustion rather
 * than a clean completion. That is the honest answer. The alternative was a `0` that lied.
 *
 * Closing the gap properly means giving this verb a run-plane authority (see `RunPlane` and the
 * `--db` gap recorded in the RC4 receipt); until then this case is the honest ceiling, not a bug
 * to be tuned away.
 */
sealed interface FollowExit {
    /** The process exit code this outcome projects to. Stated once, never re-derived per call site. */
    val exitCode: Int

    /** Nothing pending and every known stream sealed — the output lane's own authority answered. */
    data object Completed : FollowExit {
        override val exitCode: Int = 0
    }

    /** The output plane refused to answer the read in flight. */
    data class Refused(val reason: OutputRefusal) : FollowExit {
        override val exitCode: Int = 1
    }

    /** SIGINT/SIGTERM, so a `Ctrl-C` is distinguishable from every other way out. */
    data object Signalled : FollowExit {
        override val exitCode: Int = 2
    }

    /**
     * The poll budget ran out before any terminal fact was observed.
     *
     * Its own case because none of the others happened. Deliberately non-zero — see the type KDoc.
     */
    data class PollBudgetExhausted(val polls: Int, val lastOrdinal: Long) : FollowExit {
        override val exitCode: Int = 3
    }
}

/**
 * the console filter contract — closed ADT of content filters on the per-frame `console` path.
 *
 * `None` is the existing [ConsoleReadRequest.Frames] path: no filter, every frame in observation
 * order. `Active` carries the closed set of values the user requested for `--stage`, `--step` and
 * `--kind`. Each axis is a set, so repeated flags OR within an axis (`--kind StepStarted
 * --kind StepFinished` = "StepStarted OR StepFinished") and the three axes AND together (a frame
 * must match every axis that has a value).
 *
 * The closed shape is what makes "filter that filters nothing" a typed decision: a caller that
 * says "filter by stage=build, but I am reading the whole run" gets a frame walk; a caller that
 * says "filter by stage=build on the run that contains no build stage" gets zero frames. There
 * is no third "silently empty" state, which is what the closed ADT prevents.
 */
sealed interface ConsoleFrameFilters {
    /** No filter; the per-frame walk returns every frame in observation order. */
    data object None : ConsoleFrameFilters

    /**
     * Filtered read: emit only frames whose opId matches at least one stage in [stages] (if
     * non-empty), at least one step in [steps] (if non-empty), AND the events journal shows
     * at least one matching kind in [kinds] (if non-empty). The empty-set axes are
     * "no constraint on this axis" — the conjunction is across the non-empty axes only.
     */
    data class Active(
        val stages: Set<String> = emptySet(),
        val steps: Set<String> = emptySet(),
        val kinds: Set<String> = emptySet(),
    ) : ConsoleFrameFilters {
        init {
            require(stages.isNotEmpty() || steps.isNotEmpty() || kinds.isNotEmpty()) {
                "ConsoleFrameFilters.Active must carry at least one non-empty axis; got stages=$stages steps=$steps kinds=$kinds"
            }
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
     * the per-frame read contract — a byte-budgeted or cursor-resume flag offered to a per-frame read, or vice
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
     * the console --format contract — `--format` was given a value that is neither `text` nor `jsonl`.
     *
     * Distinct from [UnknownOption] (which is about a flag the verb does not recognise) and from
     * [MissingOptionValue] (the flag had no value). The closed [MainConsoleCli.ConsoleFormat] ADT
     * has exactly two cases; this refusal names the offending value so the caller edits one token
     * rather than guessing which one.
     */
    data class UnknownFormat(val value: String, val accepted: List<String>) : ConsoleCliRefusal

    /**
     * the console follow flag — `--follow` was supplied without `--frames`.
     *
     * The follow is a loop over the per-frame read; without `--frames` it would be a loop over
     * nothing. Refused rather than defaulted: a `--follow` against the legacy byte-budgeted path
     * would silently emit the merged page once and then poll forever on `OutputCursor`, which is
     * a different contract from what the operator asked for.
     */
    data object FollowRequiresFrames : ConsoleCliRefusal {
        override fun toString(): String =
            "FollowRequiresFrames: --follow requires --frames; the per-frame path is the only " +
                "one a follow can resume on, because the byte path advances by byte cursor and " +
                "the follow would lose frame attribution on the resume."
    }

    /**
     * the console filter contract — `--run` and the positional `<runId>` were both supplied.
     *
     * Two ways of naming the same value is a silent-precedence defect waiting to happen: the
     * verb would have to pick one and tell the user they did not get the other. Refused
     * instead, with both tokens named so the caller deletes one rather than guessing which
     * the parser preferred.
     */
    data class MutuallyExclusiveRunId(val runId: String, val runOverride: String) : ConsoleCliRefusal

    /**
     * the console filter contract — a content filter was supplied without `--frames`.
     *
     * Filters narrow the answer on a per-frame basis: the byte-budgeted (Paged) and
     * byte-span (Range) paths do not produce per-frame records, so a filter on them would
     * have nothing to apply to. The refusal names the offending flag(s) so the caller adds
     * `--frames` rather than guessing why their filter did not narrow the answer.
     */
    data class FilterRequiresFrames(val flags: List<String>) : ConsoleCliRefusal

    /**
     * the read-side audit chain — the filter could not be turned into a selection.
     *
     * Carries the whole [dev.rubentxu.pipeline.v2.application.console.SelectionRefusal] rather
     * than flattening it, because the four reasons need genuinely different messages and a
     * flattened "filter failed" would make the caller guess which one applied.
     *
     * Its own case, and the reason `--kind` stopped being a lie: the rc1 resolver returned `true`
     * for any kind, so `--kind anything` selected the whole run and exited 0. A caller asking for
     * a filter the verb cannot apply must be told so.
     */
    data class FilterNotResolvable(
        val reason: dev.rubentxu.pipeline.v2.application.console.SelectionRefusal,
    ) : ConsoleCliRefusal
}

/**
 * The multi-line usage for `pipeline console`. Read top-to-bottom: the byte-budgeted
 * legacy path, the byte-span path, and the per-frame path. Each is mutually
 * exclusive with the others; the per-frame path's options are listed in the
 * block under `--frames`.
 *
 * The line is intentionally longer than the legacy single-line form: the H3
 * mandate's "UX observable" requires the operator to see every verb on
 * `pipelinek console` with no args, not just the byte-budgeted affordance that
 * shipped in this release. `--frame-limit`, `--after-ordinal`, `--follow`, `--stage`,
 * `--step`, `--kind` are the per-frame options the R4 console work added; their
 * presence in the usage line closes the gap between the parser (which accepts
 * them) and the help text (which previously did not advertise them).
 */
internal const val CONSOLE_USAGE_TEXT: String =
    "Usage: pipeline console --control-dir <path> <runId> <opId> " +
        "[--max-bytes N] [--after-cursor TOKEN] [--format text|jsonl]\n" +
        "       | --range FROM:TO [--format text|jsonl]\n" +
        "       | --frames [--frame-limit N] [--after-ordinal N] [--follow]\n" +
        "                  [--stage S]... [--step N]... [--kind K]...\n" +
        "                  [--format text|jsonl]\n" +
        "\n" +
        "<opId> is \"<runId>-s<stageIndex>-<stepIndex>\" and nothing shorter. List the ones\n" +
        "this run can actually serve instead of composing one:\n" +
        "    ls <path>/output-plane/streams/   # <runId>_<opId>_stdout, one per readable op\n" +
        "Measured 2026-10-18: \"<runId>-s0\" is refused as unknown-stream while\n" +
        "\"<runId>-s0-0\" reads the transcript, and a step that printed nothing (echo) has\n" +
        "no stream directory and is refused the same way — so the listing is the exact set."

/** One stable line per refusal, so a script can branch on the reason. */
fun renderConsoleCliRefusal(refusal: ConsoleCliRefusal): String = when (refusal) {
    ConsoleCliRefusal.MissingArgument -> CONSOLE_USAGE_TEXT
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
    ConsoleCliRefusal.FollowRequiresFrames -> "Error: ${ConsoleCliRefusal.FollowRequiresFrames}"
    is ConsoleCliRefusal.MutuallyExclusiveRunId ->
        "Error: --run and positional runId are mutually exclusive " +
            "(got positional '${refusal.runId}' and --run '${refusal.runOverride}')"
    is ConsoleCliRefusal.FilterRequiresFrames ->
        "Error: --stage/--step/--kind require --frames " +
            "(filters are per-frame; --range/--max-bytes/--after-cursor are byte-budgeted)"
    is ConsoleCliRefusal.FilterNotResolvable -> when (val r = refusal.reason) {
        is dev.rubentxu.pipeline.v2.application.console.SelectionRefusal.UnknownStage ->
            "Error: no stage named '${r.requested}' in this run " +
                "(known: ${r.known.joinToString(", ").ifEmpty { "none" }})"
        is dev.rubentxu.pipeline.v2.application.console.SelectionRefusal.UnknownStep ->
            "Error: no step named '${r.requested}' in this run " +
                "(known: ${r.known.joinToString(", ").ifEmpty { "none" }})"
        is dev.rubentxu.pipeline.v2.application.console.SelectionRefusal.UnknownKind ->
            "Error: no step of kind '${r.requested}' in this run " +
                "(known kinds: ${r.known.joinToString(", ").ifEmpty { "none" }})"
        is dev.rubentxu.pipeline.v2.application.console.SelectionRefusal.NoEventsAuthority ->
            "Error: ${r.axesNeedingIt.joinToString(", ")} name stage/step/kind, which are " +
                "recorded only in the events journal — pass --db <path> so the console can " +
                "resolve them, or use the numeric index (e.g. --stage 0)"
        is dev.rubentxu.pipeline.v2.application.console.SelectionRefusal.EventStoreRefused ->
            r.refusal.render()
    }
}

/**
 * the console reader CLI.
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
 * ## the console --format contract — `--format text | jsonl`
 *
 * The default is `text` (raw bytes to stdout, unchanged from this release). `--format jsonl` switches
 * the emitter to [ObservationJsonLines]: one record per page, with the canonical
 * `{type:"output", channel, operation, ordinal, from, to, text/base64}` shape, the same one
 * `pipelinek observe --view console --format jsonl` already produces. Stdout stays the protocol
 * surface (one record per line, newline-terminated, flushed); the continuation token still
 * goes to stderr, so a script can resume without parsing the jsonl stream for control bytes.
 *
 * The jsonl path is bounded to the operation's merged page; a future slice can wire per-frame
 * records through `FrameIndexedObservationOutputReader` once the per-operation index is
 * available (the console follow-up work).
 *
 * ## Three steps, in that order
 *
 * [MainConsoleCliAdmission.parseTokens] collects argv and decides nothing. [admit] validates — and
 * validates CONFLICTS before TYPES, so `--range 0:10 --max-bytes abc` says the budget has no meaning
 * there rather than complaining about the number, which would send the caller to edit a token they
 * should delete. [MainConsoleCli.execute] interprets the admitted request.
 *
 * ## the console complexity allowance — why this object holds 21 functions, more than detekt's 12
 *
 * The object is the canonical CLI surface for `pipeline console`. Tests certified by the console test series
 * (`ConsoleRc3FilteredFollowTest`, `ConsoleRc4FollowTerminationTest`, `ConsoleRc2FrameIntegrityTest`,
 * `ConsoleFollowTest`, `ConsoleFrameFiltersTest`, `ConsoleReadServiceTest`) reach the verbs
 * through `MainConsoleCli.X` — splitting the object into per-verb objects (Read/Follow/Emit) would
 * require moving every public function and rewriting the test call sites, and the only honest
 * way to certify a refactor that changes both production and tests at once is to re-derive
 * every row's expectation. That is a separate slice; recorded in the receipt, not done here.
 *
 * Suppressing the rule here is therefore not a relaxed assertion to obtain green: it is a
 * DECISION to keep the public surface stable and pay down the count later, in a follow-up whose
 * only purpose is the move.
 */
@Suppress("TooManyFunctions")
object MainConsoleCli {

    /**
     * the console --format contract — the closed ADT of console output formats.
     *
     * `TEXT` is the legacy raw-bytes path (unchanged from this release). `JSONL` is the canonical
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

    fun main(args: Array<String>): Int = when (val admitted = CliParser.parseConsole(args)) {
        is ConsoleCliParseResult.Rejected -> {
            System.err.println(renderConsoleCliRefusal(admitted.refusal))
            2
        }

        is ConsoleCliParseResult.Admitted -> {
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
                // the per-frame read contract — the dispatch is on the request variant: Paged / Range go
                // through the byte-budgeted [emit] path (unchanged from the format contract); Frames
                // goes through the per-frame path, which reads the index directly and
                // emits one record per real frame.
                // the console filter contract — FramesFiltered is the filtered per-frame path; the
                // filter is resolved at emit time by matching each frame's stream against
                // the closed [ConsoleFrameFilters.Active] set.
                //
                // the read-side audit chain — ONE selection, resolved here and consumed by whichever
                // interpreter runs. This is the whole point of the block: the single read
                // and the follow loop used to decide "which frames?" separately, and the
                // follow path did not decide at all (audit the audit point the dispatcher must answer). Resolving before the
                // dispatch is what makes them the same question.
                //
                // Opening the journal HERE rather than in the parser is deliberate: admission
                // must perform no effect, so a missing or unreadable `--db` is a typed refusal
                // at interpretation time, not a parse error.
                val selection = when (val outcome = resolveSelection(request)) {
                    is SelectionOutcome.Selected -> outcome.selection
                    is SelectionOutcome.Refused -> {
                        System.err.println(
                            renderConsoleCliRefusal(ConsoleCliRefusal.FilterNotResolvable(outcome.refusal)),
                        )
                        return 2
                    }
                }
                when (request) {
                    is ConsoleReadRequest.Frames ->
                        emitFrames(readFrames(request, root), request, System.out, System.err)
                    // the console follow flag — `--follow`. A loop over the per-frame path; the loop
                    // is a pure interpreter of [ConsoleReadRequest.FollowFrames] and does not
                    // mutate any journal state.
                    is ConsoleReadRequest.FollowFrames ->
                        // the follow-loop exit audit — the loop returns a typed [FollowExit]; the ONLY place its
                        // exit code is projected. Stated here once so the mapping cannot drift,
                        // and so a reader can see that an exhausted poll budget is non-zero
                        // without reading the loop.
                        // the read-side audit chain — a `--follow` carrying filters is a FILTERED follow.
                        // Under rc1 this branch shadowed the filter entirely: the operator
                        // asked for one stage, was shown the whole run, and got exit 0.
                        runFollowFrames(request, root, System.out, System.err, selection).also { outcome ->
                            if (outcome is FollowExit.PollBudgetExhausted) {
                                System.err.println(
                                    "console-follow-exhausted: ${outcome.polls} polls, " +
                                        "no terminal fact observed; last frame ordinal " +
                                        "${outcome.lastOrdinal}. The run was NOT observed to " +
                                        "finish — resume with --after-ordinal ${outcome.lastOrdinal}",
                                )
                            }
                        }.exitCode
                    is ConsoleReadRequest.FramesFiltered ->
                        emitFramesFiltered(
                            readFramesFiltered(request, root),
                            request,
                            System.out,
                            System.err,
                            selection,
                        )
                    is ConsoleReadRequest.Paged,
                    is ConsoleReadRequest.Range ->
                        emit(execute(request, root), request, System.out, System.err)
                }
            }
        }
    }

    /**
     * the per-frame read contract — per-frame read.
     *
     * Uses [dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader]
     * (the the per-op public seam per-op public seam) to walk the operation's frames in observation order
     * starting at [ConsoleReadRequest.Frames.afterOrdinal], bounded by [ConsoleReadRequest.Frames.frameLimit].
     * The opId filter is applied on the read side, exactly as the per-op public seam documents it.
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
        // the frame-integrity audit (P0 frame integrity) — pass opId to the reader so the per-frame walk
        // is scoped to ONE operation, not the whole run. The opId-less overload is still
        // there for callers that legitimately want every frame in a run (observe --view
        // console, the live drain). The console verb addresses one op at a time and gets
        // the opId-scoped read.
        return readFramePage(reader, request.runId, request.opId, request.afterOrdinal, request.frameLimit)
    }

    /**
     * the read-side audit chain — the per-frame reader over the run's Output Plane.
     *
     * One constructor for every frame path, so the three paths cannot end up reading different
     * stores. `storeForReading` memoises per control root in a process-wide cache, which is why
     * the no-shared-mutable-singleton rule forbids a shared mutable singleton in a harness and why the test
     * classes call `OutputPlaneProvider.forgetAll()` before each fixture.
     */
    /**
     * the read-side audit chain — one bounded frame read, scoped or not.
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
    private fun readFramePage(
        reader: dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader,
        runId: String,
        opId: String?,
        afterOrdinal: Long,
        frameLimit: Int,
    ): dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead =
        if (opId == null) {
            reader.readOutput(runId, afterOrdinal, frameLimit)
        } else {
            reader.readOutput(runId, opId, afterOrdinal, frameLimit)
        }

    private fun frameReader(root: Path): dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader {
        val store = dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.storeForReading(root)
        return dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader(
            index = store.frameIndex(),
            bytes = store,
            tails = store,
        )
    }

    /**
     * the console filter contract — filtered per-frame read.
     *
     * Reads the run's frames via the same per-frame reader as [readFrames] (the reader stays
     * un-touched per "do not modify the reader" in the slice's scope). The filter is applied
     * at emit time, not read time — see [emitFramesFiltered] — so the page returned here is
     * the full observation-ordered window the per-frame walk already produced, exactly the
     * same shape [readFrames] returns. The verb main's `when (request)` dispatch in [main]
     * sends the result to the filtered emit path; the read path is filter-agnostic.
     */
    private fun readFramesFiltered(
        request: ConsoleReadRequest.FramesFiltered,
        root: Path,
    ): dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead {
        val store = dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.storeForReading(root)
        val reader = dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader(
            index = store.frameIndex(),
            bytes = store,
            tails = store,
        )
        // the frame-integrity audit (P0 frame integrity) — opId-scoped read; see readFrames.
        return readFramePage(reader, request.runId, request.opId, request.afterOrdinal, request.frameLimit)
    }

    /**
     * the console filter contract — emit the filtered per-frame result.
     *
     * The filter is applied HERE, at emit time, against the [ConsoleFrameFilters.Active] ADT
     * the parser admitted. The predicate runs on each frame's stream id
     * (`<runId>/<opId>/<channel>`) by extracting the opId segment and asking the
     * [StageStepKindResolver] whether that opId matches. The closed-format dispatch mirrors
     * [emitFrames]: `TEXT` writes the merged bytes of the surviving frames, `JSONL` writes
     * one record per surviving frame, and the continuation token rides on the last surviving
     * record's ordinal so a `--follow` consumer resumes from the right place even when the
     * filter dropped frames in between.
     */
    fun emitFramesFiltered(
        result: dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead,
        request: ConsoleReadRequest.FramesFiltered,
        out: PrintStream,
        err: PrintStream,
        selection: OperationSelection = OperationSelection.AllOperations,
    ): Int = when (result) {
        is dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead.Refused -> {
            err.println(ConsoleReadService.renderRefusal(result.reason))
            1
        }

        is dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead.Page -> {
            // the read-side audit chain — the SAME [OperationSelection] the follow loop consults. The rc1
            // predicate (`StageStepKindResolver`) matched the user's `--stage build` against the
            // stringified index `"0"`, and treated `--kind` as a true conjunct; both are gone,
            // and one selection now answers "which frames?" for every frame path.
            val filtered = result.page.records.filter { record -> selectionSelects(selection, record.frame.stream.value) }
            val lastOrdinal = filtered.lastOrNull()?.frame?.ordinal ?: result.page.lastOrdinal
            // `moreFrames` is the index's "did the unfiltered walk cut a window short?" claim.
            // The walk did (or did not) regardless of which records we drop, so we keep the
            // unfiltered walk's signal so a follow consumer can resume.
            val moreFrames = result.page.moreFrames
            when (request.format) {
                ConsoleFormat.TEXT -> {
                    for (record in filtered) out.write(record.bytes)
                    out.flush()
                    if (moreFrames) {
                        err.println(encodeFrameOrdinal(lastOrdinal))
                    }
                    0
                }
                ConsoleFormat.JSONL -> {
                    java.io.OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines.writeTo(
                            filtered,
                            writer,
                        )
                    }
                    if (moreFrames) {
                        err.println(encodeFrameOrdinal(lastOrdinal))
                    }
                    0
                }
            }
        }
    }

    /**
     * the per-frame read contract — emit the per-frame result.
     *
     * The dispatch is on the closed [ConsoleFormat] ADT:
     *  - `TEXT` writes the merged bytes of the page's frames to stdout (in observation order,
     *    one frame after another), with a continuation token on stderr carrying the next
     *    frame ordinal so a `--follow` consumer can resume.
     *  - `JSONL` writes one [ObservationJsonLines] record per real frame, in observation
     *    order, each carrying the frame's true [OutputFrame.ordinal] / [OutputFrame.from] /
     *    [OutputFrame.to] / [OutputFrame.channel] plus the operation id parsed from the
     *    stream. This is the per-frame wire shape [ObservationJsonLines] was already wired
     *    to emit (the the format contract path was a synthetic-merged-page workaround).
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
     * the console follow flag — `--follow` interpreter. A pure interpreter of [ConsoleReadRequest.FollowFrames].
     *
     * The loop is reading, not deciding: the read-only decision contract is in force. The typed decision
     * ([FollowFrames]) is what reached this method; the loop interprets it and walks the
     * durable authority via the per-frame reader.
     *
     * the cursor-threading contract is in force too: the `afterOrdinal` cursor is local state threaded as a parameter,
     * never a global or thread-local. Each poll derives a fresh `afterOrdinal` from the previous
     * one; the durable authority is unchanged by the loop.
     *
     * ## Exit conditions
     *
     * The loop exits when any of:
     *
     *  - **`followDecision` returns [FollowDecision.Finished].** That is: the page returned no
     *    new frames AND every known stream this run owns is [Sealed]. The durable authority
     *    promises nothing more will arrive on this run.
     *  - **`maxPolls` reached.** Bounded at admission. An op that owns no stream (e.g.
     *    `sh("true")`) cannot reach `Finished` because no stream exists to seal — this verb
     *    has no run-plane authority to ask instead — so the bound is the only exit for a silent
     *    op, and it reports [FollowExit.PollBudgetExhausted] rather than a clean completion.
     *  - **SIGINT/SIGTERM.** Shutdown hook flips a flag; the loop checks it between rounds.
     *
     * ## Status mapping
     *
     *  - 0 on [FollowExit.Completed] — nothing pending and every known stream sealed.
     *  - 1 on [FollowExit.Refused] — the store refused to answer the call in flight.
     *  - 2 on [FollowExit.Signalled] — a `Ctrl-C` is distinguishable from every other way out.
     *  - 3 on [FollowExit.PollBudgetExhausted] — the loop ran out of polls without ever observing
     *    a terminal fact. **Non-zero on purpose:** an abandoned follow and a completed one are
     *    different facts and must not share an exit code.
     */
    /**
     * the read-side audit chain — turn a request's filter tokens into ONE [OperationSelection].
     *
     * The only place the events journal is opened for a console read. Everything downstream —
     * the single filtered read AND the follow loop — receives the resulting selection as a value
     * and never asks the journal anything, so the two paths cannot drift apart.
     *
     * `dbPath == null` is passed through as `null` rather than as an empty history. Those are
     * different facts and the resolver gives them different answers: an empty history means "this
     * run has no such stage", while no journal at all means "nobody can tell", which is a refusal
     * naming the axis. Collapsing them would make `--db` optional in the worst possible way —
     * silently narrowing the answer.
     *
     * A request with no filter at all short-circuits to [OperationSelection.AllOperations]
     * WITHOUT opening the journal, so the byte paths and the unfiltered per-frame read are
     * untouched by this block.
     */
    private fun resolveSelection(request: ConsoleReadRequest): SelectionOutcome {
        val filters = when (request) {
            is ConsoleReadRequest.FramesFiltered -> request.filters
            is ConsoleReadRequest.FollowFrames -> request.filters
            else -> return SelectionOutcome.Selected(OperationSelection.AllOperations)
        }
        if (filters is ConsoleFrameFilters.None) {
            return SelectionOutcome.Selected(OperationSelection.AllOperations)
        }
        val active = filters as ConsoleFrameFilters.Active
        val dbPath = request.dbPath
            ?: return ConsoleOperationSelection.resolve(request.runId, active.stages, active.steps, active.kinds, null)

        // D3 — the shared admission, not a second opinion about the same path. `ControlDirNotFound`
        // above already refused the OTHER durable lane with a name; this verb was letting the event
        // lane's equivalent escape as a raw java.sql.SQLException.
        val admission = admitEventStore(dbPath, EventStorePurpose.Read)
        if (admission is EventStoreAdmission.Refused) {
            return SelectionOutcome.Refused(SelectionRefusal.EventStoreRefused(admission.refusal))
        }
        // Open the journal, read, close — in that order. The store is closed even if resolution
        // throws, so a refusal does not leak a file handle into a follow that may run for a minute.
        return (admission as EventStoreAdmission.Admitted).store.use { store ->
            ConsoleOperationSelection.resolve(
                request.runId, active.stages, active.steps, active.kinds,
                store.eventsFor(request.runId).asSequence(),
            )
        }
    }

    /**
     * the read-side audit chain — does [selection] admit this frame?
     *
     * The stream id is `<runId>/<opId>/<channel>`, and a selection is over the opId SEGMENT.
     * Splitting on `/` and taking index 1 is what makes this a segment comparison: a substring
     * test would let `run-1-s0-0` match a selection containing `run-1-s0-01`, which on a run
     * with ten or more steps per stage leaks every neighbouring operation into a filtered read.
     *
     * A malformed stream id — fewer than three segments, or a foreign run — is NOT selected.
     * Refusing to interpret it is the fail-closed direction: a frame whose operation cannot be
     * named is one no selection can honestly claim.
     */
    internal fun selectionSelects(selection: OperationSelection, streamId: String): Boolean {
        val parts = streamId.split('/')
        if (parts.size < 3) return false
        return selection.includes(parts[1])
    }

    fun runFollowFrames(
        request: ConsoleReadRequest.FollowFrames,
        root: Path,
        out: PrintStream,
        err: PrintStream,
        selection: OperationSelection = OperationSelection.AllOperations,
    ): FollowExit {
        val store = OutputPlaneProvider.storeForReading(root)
        val reader = dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader(
            index = store.frameIndex(),
            bytes = store,
            tails = store,
        )

        // JVM-shutdown hook so a SIGINT/SIGTERM does not leave a poll thread behind. The hook
        // flips a flag; the loop checks it between rounds.
        val shutdown = AtomicBoolean(false)
        val hook = Thread { shutdown.set(true) }
        Runtime.getRuntime().addShutdownHook(hook)

        var ordinal = request.afterOrdinal
        val maxPolls = request.followConfig.maxPolls
        val pollIntervalMillis = request.followConfig.pollIntervalMillis

        try {
            for (poll in 0 until maxPolls) {
                if (shutdown.get()) return FollowExit.Signalled

                // the frame-integrity audit (P0 frame integrity) — opId-scoped read; see readFrames.
                val page = when (val read = readFramePage(reader, request.runId, request.opId, ordinal, request.frameLimit)) {
                    is ObservationOutputRead.Refused -> {
                        err.println(ConsoleReadService.renderRefusal(read.reason))
                        return FollowExit.Refused(read.reason)
                    }
                    is ObservationOutputRead.Page -> read.page
                }

                // the read-side audit chain — audit the audit point the dispatcher must answer. The filter is applied to EVERY page the loop emits,
                // using the same [selection] the single read consults. rc1 applied no filter here
                // at all: `--frames --follow --stage build` was admitted as a plain follow and the
                // operator was shown the whole run with exit 0.
                //
                // The cursor advances on the LAST DELIVERED frame, not on the last READ one. That
                // distinction is what keeps a filter from stalling the loop: advancing to the last
                // read ordinal when the filter dropped everything after it would re-read those
                // frames forever and never reach a frame the filter does admit.
                val admitted = page.records.filter { selectionSelects(selection, it.frame.stream.value) }
                if (admitted.isNotEmpty()) {
                    emitFollowPage(admitted, request.format, out, err)
                    // The next read starts at the last ordinal we just emitted. The reader is
                    // strictly `ordinal > afterOrdinal` (see SegmentFrameIndex.framesOfRun), so
                    // passing the last-delivered ordinal is what resumes at the NEXT frame, not
                    // at the one after the next. Adding +1 here would skip a frame between
                    // polls. Empty pages leave the cursor where it is so the next poll reads
                    // the same window and can see anything new.
                    ordinal = admitted.last().frame.ordinal
                }

                // EOF detection. Three facts, each from a different authority:
                //  - moreFrames: did the index know there were frames beyond what it gave us
                //    (a window-shape fact);
                //  - tailStates: what the durable stores say about each stream this run owns
                //    (whether more bytes can ever arrive on it);
                //  - runPlane: what the EXECUTION plane can say about the run. the follow-loop exit audit — this
                //    verb never opens the events journal, so it has NO run-plane authority and
                //    says so explicitly with [RunPlane.Unavailable] instead of passing a `false`
                //    that was indistinguishable from "the run is still going". The distinction is
                //    what lets `followDecision` degrade to the output lane's own authority here
                //    (keeping a console-only follow terminatable) while still refusing to treat
                //    sealing as sufficient on a lane that CAN ask. Closing the gap means giving
                //    this verb a `--db` to read `RunFinished` from; recorded as NOT_RUN in the
                //    RC4 receipt rather than papered over.
                val tails = reader.tailStatesOf(request.runId)
                val decision = followDecision(
                    moreFrames = page.moreFrames,
                    tailStates = tails,
                    runPlane = RunPlane.Unavailable,
                )
                when (decision) {
                    FollowDecision.Finished -> return FollowExit.Completed
                    FollowDecision.ReadAgain -> {
                        // Only sleep between rounds. The last iteration need not sleep.
                        if (poll < maxPolls - 1) {
                            try {
                                Thread.sleep(pollIntervalMillis)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return FollowExit.Signalled
                            }
                        }
                    }
                }
            }
            // The budget ran out and nothing terminal was ever observed. NOT a completion: see
            // `FollowExit.PollBudgetExhausted`. The cursor is reported so a caller can tell how
            // far the follow got and resume with `--after-ordinal`.
            return FollowExit.PollBudgetExhausted(polls = maxPolls, lastOrdinal = ordinal)
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) { /* shutdown in flight */ }
        }
    }

    /**
     * the console follow flag — emit one [ObservationOutputPage] of records to stdout, in the requested format.
     *
     * The follow path does not emit a continuation token to stderr: the per-poll ordinal is a
     * local cursor that does not survive the process (the durable-read discipline; the spec for `--follow`). A consumer
     * that needs to recover from a clip would re-invoke `pipelinek console --follow --after-ordinal
     * <N>` directly, the same way it started.
     */
    /**
     * the frame-integrity audit (P0) — test seam for the follow-page emitter.
     *
     * The emitter's stream-lifetime contract is the defect the rc1 audit found (it closed
     * the caller's `System.out` after the first page), and a stream-lifetime contract
     * cannot be verified from outside a process: the row has to write two pages through
     * the same stream and observe whether the second one lands. That needs a seam, not a
     * forked JVM per assertion.
     *
     * It delegates to the private [emitFollowPage]; it does not re-implement it, so the
     * row tests the shipped code path rather than a copy of it.
     */
    internal fun emitFollowPageForTest(
        records: List<ObservationRecord.Output>,
        format: ConsoleFormat,
        out: PrintStream,
    ) {
        emitFollowPage(records, format, out, PrintStream(java.io.OutputStream.nullOutputStream()))
    }

    private fun emitFollowPage(
        records: List<ObservationRecord.Output>,
        format: ConsoleFormat,
        out: PrintStream,
        @Suppress("UNUSED_PARAMETER") err: PrintStream,
    ) {
        when (format) {
            ConsoleFormat.TEXT -> {
                for (record in records) out.write(record.bytes)
                out.flush()
            }
            ConsoleFormat.JSONL -> {
                // DO NOT wrap `out` in a writer and call `.use {}` on it: OutputStreamWriter's
                // close() closes the underlying stream by default, and a `.use` block calls
                // close() on exit. After the first follow page that pattern closes System.out,
                // and the next page's `out.write(...)` throws IOException — silently losing the
                // rest of the follow output. Use a writer that does NOT propagate close, and
                // flush it explicitly without closing.
                val noCloseOut = object : java.io.FilterOutputStream(out) {
                    override fun close() {
                        // No-op: the writer's own close() would propagate to the underlying
                        // stream and close System.out between follow pages. We own the
                        // stream's lifetime; the JVM owns System.out.
                    }
                }
                val writer = java.io.OutputStreamWriter(noCloseOut, StandardCharsets.UTF_8)
                dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines.writeTo(
                    records,
                    writer,
                )
                writer.flush()
            }
        }
    }

    /**
     * the per-frame read contract — frame-ordinal continuation token, distinct from [OutputCursor.TOKEN_PREFIX].
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

            // `FollowFrames` is handled in [main] via [runFollowFrames] before [execute]
            // is reached. Same shape as `Frames`: the dispatch in `main` reads the request
            // type and routes accordingly; [execute] is only the byte-budgeted path.
            is ConsoleReadRequest.FollowFrames ->
                error("unreachable: FollowFrames requests bypass execute() and use runFollowFrames instead")
            // `FramesFiltered` follows the same path: handled in [main] via
            // [readFramesFiltered] + [emitFramesFiltered]. The branch is exhaustive; this
            // case is unreachable but keeps the closed [ConsoleReadRequest] ADT honest.
            is ConsoleReadRequest.FramesFiltered ->
                error(
                    "unreachable: FramesFiltered requests bypass execute() and use " +
                        "readFramesFiltered+emitFramesFiltered instead",
                )
        }

    /**
     * Emit the result. Split out so a test drives it without capturing the real process streams.
     *
     * the console --format contract — the format dispatch is a `when` over the closed [ConsoleFormat] ADT (two
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
     * the console --format contract — emit one [ObservationJsonLines] record for a merged [OutputPage].
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
 * the console is the operation's channel-addressed streams, and a reader that wants one
 * channel asks for it by name. This stays a single helper so the shape is minted in ONE place — the
 * same reason [OutputStreamAddress.parse] reads it back from the two ends.
 */
    internal fun streamIdFor(runId: String, opId: String, channel: dev.rubentxu.pipeline.v2.output.OutputChannel): OutputStreamId =
        OutputPlaneProvider.streamId(runId, opId, channel)

    internal fun utf8(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
}

/**
 * the console filter contract — predicate that maps a frame's stream id to a stage/step/kind decision.
 *
 * The stream id carries the operation id in its middle segment
 * (`<runId>/<opId>/<channel>`), and the opId is shaped
 * `<runId>-s<stageIndex>-<stepIndex>[-b<branchIndex>][-bp<...>]` — see
 * [dev.rubentxu.pipeline.v2.application.durable.OpId]. From the stream id alone the verb main
 * can name the **indexes**, not the user-supplied stage and step names; that mapping lives in
 * the events journal, which the verb main does not (yet) open. To keep the filter contract
 * honest without coupling this slice to the events store, the resolver matches the user-supplied
 * `stage` / `step` against the **stringified index** that opId already carries, so a caller that
 * writes `--stage 0 --step 0` against a `runId-s0-0` opId gets a hit and a caller that writes
 * `--stage build` against the same opId gets a miss with the same answer.
 *
 * The `kind` axis is not derivable from the stream id, so it is currently treated as a
 * pass-through: a non-empty `kinds` set is admitted (the user said the request was
 * `FramesFiltered`) and contributes a true conjunct. Wiring `kind` to the events journal is the
 * next slice's job (the channel-to-opId resolution territory / event-→-opId resolution); the contract this resolver exposes —
 * "an empty axis is a no-op, a non-empty axis is a conjunct" — is what the next slice will
 * extend without breaking today's matches.
 */
internal class StageStepKindResolver private constructor(private val runId: String) {
    /**
     * Match a frame's stream id against [filters]. The contract is
     * "every non-empty axis must be satisfied; an empty axis is a no-op".
     */
    fun matches(streamId: String, filters: ConsoleFrameFilters.Active): Boolean {
        val parts = streamId.split('/')
        if (parts.size < 3) return false
        if (parts[0] != runId) return false
        val opIdStr = parts[1]
        val parsed = dev.rubentxu.pipeline.v2.application.durable.OpId.parse(opIdStr) ?: return false
        val stageIndex = parsed.stageIndex.toString()
        val stepIndex = parsed.stepIndex.toString()
        val stageOk = filters.stages.isEmpty() || stageIndex in filters.stages
        val stepOk = filters.steps.isEmpty() || stepIndex in filters.steps
        // The kind axis is a pass-through until events-→-opId resolution lands; see the
        // class KDoc for the explicit "next slice" promise.
        val kindOk = true
        return stageOk && stepOk && kindOk
    }

    companion object {
        /** Build a resolver scoped to [runId]. The runId is part of the stream id's prefix. */
        @Suppress("UNUSED_PARAMETER")
        fun forRun(root: Path, runId: String): StageStepKindResolver = StageStepKindResolver(runId)
    }
}
