package dev.rubentxu.pipeline.v2.application.observation

/**
 * Observation contract — the read-side vocabulary for the CLI.
 *
 * ## Why this exists
 *
 * `pipeline run` historically printed a raw JSON event array to stdout
 * ([Main.kt:436] and [Main.kt:854]). That is a machine wire format presented as
 * if it were a human interface. Users got a wall of JSON instead of a console.
 *
 * The fix is NOT to swap one default for another. It is to separate four
 * orthogonal concepts that were previously conflated in a single `println`:
 *
 * - [ObservationView] — WHICH families of observation reach the reader;
 * - [ObservationFormat] — HOW that selection is encoded on the wire;
 * - [ObservationQuery] (declared in the design docs, not yet implemented) —
 *   WHICH records are selected;
 * - presentation (see `docs/proposals/CLI-CONSOLE-OBSERVABILITY-DRAFT.md`) —
 *   HOW each selected record is decorated.
 *
 * `view` and `format` never change execution semantics or persistence. This is
 * the law of ADR-0088.
 *
 * ## Fail-closed
 *
 * A view that is understood but not yet deliverable is REJECTED with a typed
 * error. It is never silently downgraded to a different view: a user who asked
 * for `full` and received `normal` has been lied to about what they observed,
 * which is strictly worse than an error.
 *
 * `FULL` is currently in that state. Its two source planes (event `sequence`
 * and console byte `offset`) have no common order, and
 * `CLI_OBSERVABILITY_SPEC.md` §11 forbids claiming one. The interleaving rule
 * is an open product decision (draft §7.1), so `full` is refused rather than
 * approximated.
 *
 * `CONSOLE` is also refused here because process output is owned by the Output
 * Plane, which `pipeline console` already reads as bytes. Reading it twice from
 * two verbs would be a second source of output truth, so `run` points at the
 * dedicated verb instead.
 *
 * [Main.kt:436]: dev.rubentxu.pipeline.v2.application.Main
 */
enum class ObservationView {
    /** Lifecycle, structure, script messages and failures. The default. */
    NORMAL,

    /** One readable line per event, including observational families. */
    EVENTS,

    /** Process transcript. Refused by `run`; owned by `pipeline console`. */
    CONSOLE,

    /** Final outcome only. */
    QUIET,

    /** Events + live console. Refused: interleaving rule is undecided. */
    FULL,
    ;

    /**
     * Views this build can actually deliver.
     *
     * Kept as data rather than a comment so the refusal in [parseView] and the
     * availability list cannot drift apart.
     */
    val available: Boolean
        get() = this in EVENT_LANE_VIEWS

    companion object {
        /**
         * The views a reader of the EVENT lane alone can deliver.
         *
         * A set rather than a per-view constant because availability is a question ABOUT A READER,
         * not a property OF a view. [CONSOLE] is unreadable to `run` and deliverable to `observe`
         * from the same build, so hard-coding either answer would have made one of those two true
         * by fiat.
         */
        val EVENT_LANE_VIEWS: Set<ObservationView> = setOf(NORMAL, EVENTS, QUIET)

        fun parseView(raw: String): ViewParseResult {
            val match = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            return if (match == null) ViewParseResult.Invalid(raw) else ViewParseResult.Parsed(match)
        }
    }
}

/**
 * Wire encoding of the selected observation. Independent of [ObservationView]:
 * a `normal` view is human text, and `events`/`jsonl` are the machine
 * continuations of what `pipeline events` already emits.
 *
 * `JSON` is a bounded, post-run document. `JSON_LINES` is the natural format
 * for following a run, because it does not require buffering the whole
 * transcript in memory.
 */
enum class ObservationFormat(val wire: String) {
    TEXT("text"),
    JSON_LINES("jsonl"),
    JSON("json"),
    ;

    companion object {
        fun parseFormat(raw: String): FormatParseResult {
            val match = entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
            return if (match == null) FormatParseResult.Invalid(raw) else FormatParseResult.Parsed(match)
        }
    }
}

/** Typed parse outcomes — never an exception, never a silent fallback. */
sealed interface ViewParseResult {
    data class Parsed(val view: ObservationView) : ViewParseResult

    /** The spelling is not a view at all. Reject before any effect. */
    data class Invalid(val value: String) : ViewParseResult

    /** The view exists but this build cannot deliver it. */
    data class Unavailable(val view: ObservationView) : ViewParseResult
}

sealed interface FormatParseResult {
    data class Parsed(val format: ObservationFormat) : FormatParseResult
    data class Invalid(val value: String) : FormatParseResult
}

/**
 * Resolves [raw] to a deliverable view, collapsing "unknown" and "not yet
 * deliverable" into distinct typed results.
 *
 * [deliverable] is what the CALLER can read, which is why it is a parameter: `run` streams the
 * event lane and refuses `console`, while `observe` reads both lanes and delivers it.
 *
 * Pure: no I/O, no globals. Called by the parser before any effect is launched,
 * so an unusable view never reaches execution.
 */
fun resolveView(
    raw: String,
    deliverable: Set<ObservationView> = ObservationView.EVENT_LANE_VIEWS,
): ViewParseResult {
    return when (val parsed = ObservationView.parseView(raw)) {
        is ViewParseResult.Parsed ->
            if (parsed.view in deliverable) ViewParseResult.Parsed(parsed.view)
            else ViewParseResult.Unavailable(parsed.view)

        is ViewParseResult.Invalid -> parsed
        is ViewParseResult.Unavailable -> parsed
    }
}
