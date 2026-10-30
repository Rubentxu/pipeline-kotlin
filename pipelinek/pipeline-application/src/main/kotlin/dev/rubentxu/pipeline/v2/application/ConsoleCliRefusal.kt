package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.console.SelectionRefusal

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
     * A byte-budgeted or cursor-resume flag offered to a per-frame read, or vice
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
     * `--format` was given a value that is neither `text` nor `jsonl`.
     *
     * Distinct from [UnknownOption] (which is about a flag the verb does not recognise) and from
     * [MissingOptionValue] (the flag had no value). The closed [MainConsoleCli.ConsoleFormat] ADT
     * has exactly two cases; this refusal names the offending value so the caller edits one token
     * rather than guessing which one.
*/
    data class UnknownFormat(val value: String, val accepted: List<String>) : ConsoleCliRefusal

    /**
     * `--follow` was supplied without `--frames`.
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
     * `--run` and the positional `<runId>` were both supplied.
     *
     * Two ways of naming the same value is a silent-precedence defect waiting to happen: the
     * verb would have to pick one and tell the user they did not get the other. Refused
     * instead, with both tokens named so the caller deletes one rather than guessing which
     * the parser preferred.
*/
    data class MutuallyExclusiveRunId(val runId: String, val runOverride: String) : ConsoleCliRefusal

    /**
     * A content filter was supplied without `--frames`.
     *
     * Filters narrow the answer on a per-frame basis: the byte-budgeted (Paged) and
     * byte-span (Range) paths do not produce per-frame records, so a filter on them would
     * have nothing to apply to. The refusal names the offending flag(s) so the caller adds
     * `--frames` rather than guessing why their filter did not narrow the answer.
*/
    data class FilterRequiresFrames(val flags: List<String>) : ConsoleCliRefusal

    /**
     * The filter could not be turned into a selection.
     *
     * Carries the whole [SelectionRefusal] rather
     * than flattening it, because the four reasons need genuinely different messages and a
     * flattened "filter failed" would make the caller guess which one applied.
     *
     * Its own case, and the reason `--kind` stopped being a lie: the rc1 resolver returned `true`
     * for any kind, so `--kind anything` selected the whole run and exited 0. A caller asking for
     * a filter the verb cannot apply must be told so.
*/
    data class FilterNotResolvable(
        val reason: SelectionRefusal,
    ) : ConsoleCliRefusal
}

/**
 * The multi-line usage for `pipeline console`. Read top-to-bottom: the byte-budgeted
 * legacy path, the byte-span path, and the per-frame path. Each is mutually
 * exclusive with the others; the per-frame path's options are listed in the
 * block under `--frames`.
 *
 * The line is intentionally longer than the legacy single-line form: the
 * mandate's "UX observable" requires the operator to see every verb on
 * `pipelinek console` with no args, not just the byte-budgeted affordance that
 * Shipped in. `--frame-limit`, `--after-ordinal`, `--follow`, `--stage`
 * `--step`, `--kind` are the per-frame options the console work added; their
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
        "    ls <path>/output-plane/streams/   # <runId>_<opId>_<channel>, one per stream\n" +
        "Measured: \"<runId>-s0\" is refused as unknown-stream while\n" +
        "\"<runId>-s0-0\" reads the transcript, and a step that printed nothing (echo) has\n" +
        "no stream directory and is refused the same way — so the listing is the exact set.\n" +
        "Both channels are listed: a step that wrote only to stderr has a _stderr\n" +
        "directory and no _stdout one, and it is served the same way."

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
        is SelectionRefusal.UnknownStage ->
            "Error: no stage named '${r.requested}' in this run " +
                "(known: ${r.known.joinToString(", ").ifEmpty { "none" }})"
        is SelectionRefusal.UnknownStep ->
            "Error: no step named '${r.requested}' in this run " +
                "(known: ${r.known.joinToString(", ").ifEmpty { "none" }})"
        is SelectionRefusal.UnknownKind ->
            "Error: no step of kind '${r.requested}' in this run " +
                "(known kinds: ${r.known.joinToString(", ").ifEmpty { "none" }})"
        is SelectionRefusal.NoEventsAuthority ->
            "Error: ${r.axesNeedingIt.joinToString(", ")} name stage/step/kind, which are " +
                "recorded only in the events journal — pass --db <path> so the console can " +
                "resolve them, or use the numeric index (e.g. --stage 0)"
        is SelectionRefusal.EventStoreRefused ->
            r.refusal.render()
    }
}
