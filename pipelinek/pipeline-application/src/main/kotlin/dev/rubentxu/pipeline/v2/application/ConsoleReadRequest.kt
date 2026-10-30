package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.output.OutputCursor

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
     * The operation to read, or `null` to read the WHOLE RUN and let the
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
     * The events journal this request may consult, or `null` when none was supplied.
     *
     * It is a PATH, not an opened store: the parser admits a request without performing an
     * effect (STRICT TYPED FUNCTIONAL DESIGN — a builder describes, it does not interpret),
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
     * Per-frame walk.
     *
     * Continue after [afterOrdinal] (`-1` starts at the first frame), emitting at most
     * [frameLimit] frames in observation order. Unlike [Paged] (byte-budgeted, cursor-resumable)
     * and [Range] (byte span, no continuation), this path answers the question only the frame
     * index can: "what did this operation publish, frame by frame, in the order PipelineK
     * observed it?" The JSONL emitter walks this path and writes one record per real frame;
     * the per-record [dev.rubentxu.pipeline.v2.output.OutputFrame.ordinal] /
     * [dev.rubentxu.pipeline.v2.output.OutputFrame.from] /
     * [dev.rubentxu.pipeline.v2.output.OutputFrame.to] ride on the wire.
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
     * `--follow` over the per-frame path.
     *
     * A thin loop over [Frames] reads. Each poll re-reads with `afterOrdinal` set to the last
     * frame the loop EXAMINED (or `-1` for the first poll), emits the frames the selection
     * admits, and continues until either:
     *
     * - the read returns 0 new frames AND every known output stream is
     * [dev.rubentxu.pipeline.v2.output.OutputTailState.Sealed]
     * (the durable authority for "the writer closed this stream") — EOF, exit 0;
     * - the [followConfig] has been polled [FollowConfig.maxPolls] times — exit 3, because a
     * follow that ran out of budget never observed a terminal fact;
     * - the JVM receives a SIGINT/SIGTERM — exit 2.
     *
     * The cursor between polls is local state, fully reconstructible from the durable
     * authority ([afterOrdinal] + [frameLimit]); it is NOT journaled (in AGENTS.md).
     * A fresh process invocation starts over from [afterOrdinal]; a follow does NOT resume
     * across runs.
     *
     * The follow is reading, not deciding: that separation is in force. The typed decision
     * ([ConsoleReadRequest.FollowFrames]) is what reaches the loop; the loop is a pure interpreter of that
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
         * The same content filter a single [FramesFiltered] read carries.
         * This field is the fix for that defect. The console parser's `consoleValidate`
         * dispatched `--follow`
         * BEFORE the filter branch, so `console --frames --follow --stage build` admitted a
         * filterless [ConsoleReadRequest.FollowFrames] and dropped the filter without a word: the operator asked to
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
     * Per-frame walk with content filters.
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
 * The bounded knobs that turn a [ConsoleReadRequest.Frames] read into a loop.
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
 * Closed ADT of content filters on the per-frame `console` path.
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
