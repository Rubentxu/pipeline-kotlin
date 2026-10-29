package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.output.OutputCursor

// The `console` verb.
//
// The verb owns TWO positional arguments — the run id and the operation id —
// and refuses a third. Body moved here from the admission object the console verb used to own;
// the verb main calls this and renders the typed [ConsoleCliRefusal] through
// [renderConsoleCliRefusal], which produces the `Error: ...` wire line for
// argument refusals. The data-plane refusals
// (`console-refused: foreign-stream ...`, etc.) are produced by [ConsoleReadService.renderRefusal]
// at the read boundary and are NOT touched here — they are independent observable contracts.
//
// Every axis of the request travels in ONE holder, [ConsoleCliOptions]. The
// console verb has more axes than any other one, and passing them as
// positional parameters made every helper in the chain carry twelve to eighteen
// of them: a call site that cannot be read without counting. The holder keeps
// the shape of the request in one place and lets each validator read the axes
// it owns.

/**
 * The raw tokens of one console invocation, exactly as the operator typed them.
 *
 * One property per axis, none of them a constructor parameter: the holder is
 * filled while the arguments are scanned and read afterwards, so a constructor
 * would have to carry the same eighteen values anyway.
 */
internal class ConsoleCliOptions {
    var controlDir: String? = null
    var runId: String? = null
    var opId: String? = null
    var maxBytes: String? = null
    var afterCursor: String? = null
    var range: String? = null
    var formatToken: String? = null
    var frames: Boolean = false
    var frameLimit: String? = null
    var afterOrdinal: String? = null

    /** `--follow` over the per-frame path. */
    var follow: Boolean = false
    var followPollInterval: String? = null
    var followMaxPolls: String? = null

    /** Content filters on the per-frame path, one set per axis. */
    var stages: Set<String> = emptySet()
    var steps: Set<String> = emptySet()
    var kinds: Set<String> = emptySet()

    /** The alternative to the positional `<runId>`, mutually exclusive with it. */
    var runOverride: String? = null

    /** The events journal: the only authority that knows a stage/step NAME. */
    var dbPath: String? = null
}

/**
 * `pipelinek console --control-dir <path> <runId> <opId> [opts]`.
 *
 * `--range`, `--frames`, `--after-cursor`, `--max-bytes`, `--after-ordinal` and `--frame-limit`
 * remain exclusive in the combinations [consoleValidate] documents. The body moved here
 * without changing a decision, so this is a relocation, not a relaxation.
 */
internal object CliConsoleParser {
    @Suppress("CyclomaticComplexMethod")
    fun parse(args: Array<String>): ConsoleCliParseResult {
        val options = ConsoleCliOptions()
        // The three filter axes are collected here and lowered into the closed
        // [ConsoleFrameFilters.Active] ADT in [consoleValidate]. Repeated flags OR within an
        // axis; the three axes AND together (a frame must satisfy every non-empty axis).
        val stages = LinkedHashSet<String>()
        val steps = LinkedHashSet<String>()
        val kinds = LinkedHashSet<String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when (arg) {
                "--control-dir", "--max-bytes", "--after-cursor", "--range",
                "--frame-limit", "--after-ordinal", "--format",
                "--follow-poll-interval", "--follow-max-polls",
                "--stage", "--step", "--kind", "--run", "--db" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return ConsoleCliParseResult.Rejected(ConsoleCliRefusal.MissingOptionValue(arg))
                    when (arg) {
                        "--control-dir" -> options.controlDir = value
                        "--max-bytes" -> options.maxBytes = value
                        "--after-cursor" -> options.afterCursor = value
                        "--range" -> options.range = value
                        "--frame-limit" -> options.frameLimit = value
                        "--after-ordinal" -> options.afterOrdinal = value
                        "--format" -> options.formatToken = value
                        "--follow-poll-interval" -> options.followPollInterval = value
                        "--follow-max-polls" -> options.followMaxPolls = value
                        "--stage" -> stages += value
                        "--step" -> steps += value
                        "--kind" -> kinds += value
                        "--run" -> options.runOverride = value
                        "--db" -> options.dbPath = value
                    }
                    index++
                }
                "--frames" -> options.frames = true
                "--follow" -> options.follow = true
                else -> {
                    // `--run` and a positional `<runId>` are mutually
                    // exclusive, and a positional that follows `--run` is interpreted as
                    // the opId (the runId comes from the flag). The second positional,
                    // if any, IS a positional runId: the user typed it after the opId
                    // they meant, and the two would collide on the runId slot. Refuse
                    // before [consoleValidate] so the typed `MutuallyExclusiveRunId`
                    // surfaces with both tokens named.
                    val refusal = consolePositional(arg, options.runId, options.opId, options.runOverride != null)
                    if (refusal != null) return ConsoleCliParseResult.Rejected(refusal)
                    val runOverride = options.runOverride
                    if (runOverride != null) {
                        if (options.opId == null) {
                            options.opId = arg
                        } else {
                            // opId is already set, so this second positional is a
                            // positional runId; `--run` and the positional are
                            // mutually exclusive.
                            return ConsoleCliParseResult.Rejected(
                                ConsoleCliRefusal.MutuallyExclusiveRunId(runId = arg, runOverride = runOverride),
                            )
                        }
                    } else if (options.runId == null) {
                        options.runId = arg
                    } else {
                        options.opId = arg
                    }
                }
            }
            index++
        }
        options.stages = stages
        options.steps = steps
        options.kinds = kinds
        return consoleValidate(options)
    }
}

private fun consolePositional(arg: String, runId: String?, opId: String?, hasRunOverride: Boolean): ConsoleCliRefusal? = when {
    arg.startsWith("--") -> ConsoleCliRefusal.UnknownOption(arg)
    // Without `--run`, the slots are runId then opId. Both filled → unexpected.
    !hasRunOverride && runId != null && opId != null -> ConsoleCliRefusal.UnexpectedArgument(arg)
    // With `--run`, the slot is opId only. A second positional is the runId conflict
    // and the main loop in [CliConsoleParser.parse] handles it as [MutuallyExclusiveRunId] rather
    // than as a generic unexpected argument, so the refusal names both tokens. The
    // positional-pivot here only needs to admit the FIRST positional under `--run`.
    hasRunOverride && opId != null -> null
    else -> null
}

private fun consoleValidate(options: ConsoleCliOptions): ConsoleCliParseResult {
    // The shape: four orthogonal validators, each a single `if refused return`, then a final
    // "admit" dispatcher that knows every read verb. Splitting them keeps each method under
    // detekt's complexity ceiling and makes the refusal surface of every axis visible at a
    // glance — which is what an operator trying to debug a typed rejection needs.
    val cd = options.controlDir ?: return consoleReject(ConsoleCliRefusal.MissingArgument)
    val hasFilter = options.stages.isNotEmpty() || options.steps.isNotEmpty() || options.kinds.isNotEmpty()

    val runAxis = resolveRunAxis(options.runId, options.runOverride)
    if (runAxis is ResolvedRunAxis.Refused) return consoleReject(runAxis.refusal)
    val effectiveRunId = (runAxis as ResolvedRunAxis.Admitted).effectiveRunId
    val oid = options.opId ?: if (hasFilter) null else return consoleReject(ConsoleCliRefusal.MissingArgument)

    validateRangeIncompatibilities(options)?.let { return it }
    validateFramesIncompatibilities(options)?.let { return it }
    validateFollowShape(options.follow, options.frames)?.let { return it }
    validateFilterShape(options, hasFilter)?.let { return it }

    val format = options.formatToken?.let { token ->
        MainConsoleCli.ConsoleFormat.parse(token)
            ?: return consoleReject(
                ConsoleCliRefusal.UnknownFormat(
                    value = token,
                    accepted = MainConsoleCli.ConsoleFormat.values().map { it.token },
                ),
            )
    } ?: MainConsoleCli.ConsoleFormat.TEXT

    return consoleDecideVerb(options, cd, effectiveRunId, oid, format, hasFilter)
}

/**
 * The result of resolving the run id axis: either an effective run id, or a refusal.
 * `Either<Refusal, String>` would say the same thing; this is a `when`-friendly sealed pair
 * with a `refusal()` accessor so the call site can `?: return refusal`.
 */
private sealed interface ResolvedRunAxis {
    fun refusal(): ConsoleCliRefusal
    data class Admitted(val effectiveRunId: String) : ResolvedRunAxis {
        override fun refusal(): ConsoleCliRefusal = error("admitted")
    }
    data class Refused(val refusal: ConsoleCliRefusal) : ResolvedRunAxis {
        override fun refusal(): ConsoleCliRefusal = refusal
    }
}

private fun resolveRunAxis(runId: String?, runOverride: String?): ResolvedRunAxis? {
    // `--run` replaces the positional <runId>; both is a category error
    // that the verb main has no honest answer to, so it is refused at admission.
    if (runOverride != null && runId != null) {
        return ResolvedRunAxis.Refused(ConsoleCliRefusal.MutuallyExclusiveRunId(runId, runOverride))
    }
    // Either a positional runId or a `--run` override is required; the existing
    // MissingArgument refusal covers "neither" and is reused unchanged.
    val effective = runId ?: runOverride
        ?: return ResolvedRunAxis.Refused(ConsoleCliRefusal.MissingArgument)
    return ResolvedRunAxis.Admitted(effective)
}

private fun validateRangeIncompatibilities(options: ConsoleCliOptions): ConsoleCliParseResult? {
    val range = options.range
    val incompatible = when {
        range == null -> null
        options.afterCursor != null -> "--after-cursor"
        else -> "--max-bytes".takeIf { options.maxBytes != null }
    }
    if (range != null && incompatible != null) {
        return consoleReject(ConsoleCliRefusal.IncompatibleWithRange(incompatible))
    }
    return null
}

private fun validateFramesIncompatibilities(options: ConsoleCliOptions): ConsoleCliParseResult? {
    if (!options.frames) return null
    val framesIncompatible = when {
        options.range != null -> "--range"
        options.maxBytes != null -> "--max-bytes"
        options.afterCursor != null -> "--after-cursor"
        else -> null
    }
    return framesIncompatible?.let { consoleReject(ConsoleCliRefusal.IncompatibleWithFrames(it)) }
}

private fun validateFollowShape(follow: Boolean, frames: Boolean): ConsoleCliParseResult? {
    // `--follow` requires `--frames`. Refused rather than defaulted: a
    // `--follow` against the byte path would silently emit the merged page once and then
    // poll forever on a byte cursor, which is a different contract from what the operator
    // asked for.
    return if (follow && !frames) consoleReject(ConsoleCliRefusal.FollowRequiresFrames) else null
}

private fun validateFilterShape(options: ConsoleCliOptions, hasFilter: Boolean): ConsoleCliParseResult? {
    if (!hasFilter || options.frames) return null
    // A content filter without `--frames` is a category error: filters
    // operate on per-frame records, and the byte-budgeted (Paged) and byte-span (Range)
    // paths produce no per-frame records. The refusal names every offending flag so the
    // caller adds `--frames` rather than guessing why their filter did not narrow the
    // answer.
    val offending = buildList {
        if (options.stages.isNotEmpty()) add("--stage")
        if (options.steps.isNotEmpty()) add("--step")
        if (options.kinds.isNotEmpty()) add("--kind")
    }
    return consoleReject(ConsoleCliRefusal.FilterRequiresFrames(offending))
}

/**
 * Selects which [ConsoleReadRequest] shape the call admits, after every axis validator has
 * accepted the input.
 * Audit. `--follow` used to be dispatched FIRST, so
 * `--frames --follow --stage build` admitted a filterless FollowFrames and dropped the
 * filter in silence. Filters and following are INDEPENDENT axes — following is how long you
 * read, filtering is what you read — so there is no reason for one to shadow the other.
 */
private fun consoleDecideVerb(
    options: ConsoleCliOptions,
    cd: String,
    effectiveRunId: String,
    oid: String?,
    format: MainConsoleCli.ConsoleFormat,
    hasFilter: Boolean,
): ConsoleCliParseResult {
    if (options.follow) {
        return consoleAdmitFollowFrames(options, cd, effectiveRunId, oid, format)
    }
    if (hasFilter) {
        return consoleAdmitFramesFiltered(options, cd, effectiveRunId, oid, format)
    }
    if (options.frames) return consoleAdmitFrames(options, cd, effectiveRunId, oid, format)
    // The byte paths address ONE operation by construction — a cursor, a range and a page are
    // all positions WITHIN an operation's stream, so "which operation" is not a question they
    // can answer differently. `oid` is null only when a filter was admitted, and a filter
    // without `--frames` was already refused above, so this cannot fire; it is written out
    // rather than assumed so the invariant is visible where it is relied on.
    val byteScopedOpId = oid ?: return consoleReject(ConsoleCliRefusal.MissingArgument)
    val range = options.range
    if (range != null) return consoleAdmitRange(cd, effectiveRunId, byteScopedOpId, range, format)
    return consoleAdmitPage(options, cd, effectiveRunId, byteScopedOpId, format)
}

private fun consoleReject(refusal: ConsoleCliRefusal): ConsoleCliParseResult =
    ConsoleCliParseResult.Rejected(refusal)

private fun consoleAdmitRange(
    controlDir: String,
    runId: String,
    opId: String,
    range: String,
    format: MainConsoleCli.ConsoleFormat,
): ConsoleCliParseResult {
    val bounds = range.split(':')
    val from = bounds.getOrNull(0)?.toLongOrNull()
    val to = bounds.getOrNull(1)?.toLongOrNull()
    if (bounds.size != 2 || from == null || to == null) {
        return consoleReject(ConsoleCliRefusal.NotARange(range))
    }
    return ConsoleCliParseResult.Admitted(ConsoleReadRequest.Range(controlDir, runId, opId, from, to, format))
}

private fun consoleAdmitPage(
    options: ConsoleCliOptions,
    controlDir: String,
    runId: String,
    opId: String,
    format: MainConsoleCli.ConsoleFormat,
): ConsoleCliParseResult {
    val max = options.maxBytes?.let { value ->
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--max-bytes", value))
    } ?: ConsoleReadService.DEFAULT_PAGE_BYTES

    val after = options.afterCursor?.let { token ->
        OutputCursor.decode(token) ?: return consoleReject(ConsoleCliRefusal.NotACursorToken(token))
    }
    return ConsoleCliParseResult.Admitted(
        ConsoleReadRequest.Paged(controlDir, runId, opId, after, max, format),
    )
}

private fun consoleAdmitFrames(
    options: ConsoleCliOptions,
    controlDir: String,
    runId: String,
    opId: String?,
    format: MainConsoleCli.ConsoleFormat,
): ConsoleCliParseResult {
    val limit = options.frameLimit?.let { value ->
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--frame-limit", value))
    } ?: ConsoleReadService.DEFAULT_FRAME_LIMIT

    val ordinal = options.afterOrdinal?.let { value ->
        value.toLongOrNull()?.takeIf { it >= -1L }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--after-ordinal", value))
    } ?: -1L

    return ConsoleCliParseResult.Admitted(
        ConsoleReadRequest.Frames(controlDir, runId, opId, ordinal, limit, format),
    )
}

/**
 * Admit the [ConsoleReadRequest.FollowFrames] request.
 * The per-poll bound (`--frame-limit`) and the resume cursor (`--after-ordinal`) share their
 * parsing with [consoleAdmitFrames]; only the [FollowConfig] payload is new. Both new
 * valued options refuse rather than default to match the policy the rest of the console
 * parser applies: a malformed `--follow-*` is a typed refusal, not a silent degradation.
 * Closed payload: [FollowConfig] constructor refuses non-positive values, so a
 * `0` or negative `--follow-poll-interval` / `--follow-max-polls` is a typed refusal
 * BEFORE the constructor runs — the [NotAnInteger] refusal covers the "not an integer"
 * shape, and the constructor covers the "parsed but non-positive" shape.
 */
private fun consoleAdmitFollowFrames(
    options: ConsoleCliOptions,
    controlDir: String,
    runId: String,
    opId: String?,
    format: MainConsoleCli.ConsoleFormat,
): ConsoleCliParseResult {
    val limit = options.frameLimit?.let { value ->
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--frame-limit", value))
    } ?: ConsoleReadService.DEFAULT_FRAME_LIMIT

    val ordinal = options.afterOrdinal?.let { value ->
        value.toLongOrNull()?.takeIf { it >= -1L }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--after-ordinal", value))
    } ?: -1L

    val pollIntervalMillis = options.followPollInterval?.let { value ->
        val n = value.toLongOrNull()
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", value))
        n.takeIf { it > 0L }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", value))
    } ?: FollowConfig.DEFAULT_POLL_INTERVAL_MILLIS

    val maxPolls = options.followMaxPolls?.let { value ->
        val n = value.toIntOrNull()
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-max-polls", value))
        n.takeIf { it > 0 }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-max-polls", value))
    } ?: FollowConfig.DEFAULT_MAX_POLLS

    return ConsoleCliParseResult.Admitted(
        ConsoleReadRequest.FollowFrames(
            controlDir,
            runId,
            opId,
            ordinal,
            limit,
            FollowConfig(pollIntervalMillis, maxPolls),
            format,
            filters = consoleFilters(options),
            dbPath = options.dbPath,
        ),
    )
}

/**
 * Lower the three raw token sets into the closed [ConsoleFrameFilters] ADT.
 * One function so `--follow` and a single filtered read cannot disagree about what a filter
 * IS. All-empty axes produce [ConsoleFrameFilters.None] rather than an `Active` with nothing
 * in it — the closed ADT refuses that, correctly, because "no axis constrains" and "a filter
 * is active" are different claims.
 */
private fun consoleFilters(options: ConsoleCliOptions): ConsoleFrameFilters =
    if (options.stages.isEmpty() && options.steps.isEmpty() && options.kinds.isEmpty()) {
        ConsoleFrameFilters.None
    } else {
        ConsoleFrameFilters.Active(options.stages, options.steps, options.kinds)
    }

/**
 * Admit a filtered per-frame read.
 * Reuses the per-frame integer rules from [consoleAdmitFrames] and lowers the three filter
 * sets into the closed [ConsoleFrameFilters.Active] ADT. The closed ADT
 * rejects an "active with no axis set" at construction; we gate the call here so the
 * failure shows up as a parser-level refusal rather than a runtime exception.
 */
private fun consoleAdmitFramesFiltered(
    options: ConsoleCliOptions,
    controlDir: String,
    runId: String,
    opId: String?,
    format: MainConsoleCli.ConsoleFormat,
): ConsoleCliParseResult {
    if (options.stages.isEmpty() && options.steps.isEmpty() && options.kinds.isEmpty()) {
        // The `hasFilter` check at the call site already guarantees a non-empty axis;
        // this branch is the typed "Active with all-empty axes" guard the closed ADT
        // itself enforces. Kept here for symmetry with the ClosedValueOptions
        // dispatch; the call-site check is the one that fires on the user's input.
        return consoleReject(ConsoleCliRefusal.MissingArgument)
    }
    val limit = options.frameLimit?.let { value ->
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--frame-limit", value))
    } ?: ConsoleReadService.DEFAULT_FRAME_LIMIT

    val ordinal = options.afterOrdinal?.let { value ->
        value.toLongOrNull()?.takeIf { it >= -1L }
            ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--after-ordinal", value))
    } ?: -1L

    val filters = ConsoleFrameFilters.Active(options.stages, options.steps, options.kinds)
    return ConsoleCliParseResult.Admitted(
        ConsoleReadRequest.FramesFiltered(
            controlDir, runId, opId, ordinal, limit, filters, format, dbPath = options.dbPath,
        ),
    )
}
