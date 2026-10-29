package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.application.observation.ObservationView

// The `observe` verb.
//
// A reader, so it selects with the same dimensions `run` does. The meaning of
// `--channel` is decided in `CliOptionVocabulary` and nowhere else: a second
// option parser would be a second authority for what a flag means, and the two
// would drift the moment one of them gained a case.

/**
 * What a reader that reads BOTH lanes can deliver, which is what `observe` parses against.
 * Still excluding [ObservationView.FULL]: events and output have no total order, so a view
 * claiming to be both would be claiming an interleaving this build cannot produce.
*/
private val READS_BOTH_LANES: Set<ObservationView> =
    ObservationView.EVENT_LANE_VIEWS + ObservationView.CONSOLE

internal object CliObservationParser {
    /**
     * The OBSERVATION subset of the CLI surface, for a verb that reads rather than executes.
     *
     * ## Why this lives here and not in the reader
     *
     * `observe` selects with the same dimensions `run` does, so the meaning of `--channel` has to
     * be decided in exactly one place. A second option parser would be a second authority for what a
     * flag means, and the two would drift the moment one of them gains a case. So this runs the same
     * [applyOption] over the same [ParseState] and hands back the same assembled query — a verb that
     * reads cannot quietly accept a different `--grep` than the verb that runs.
     *
     * ## Why execution options are refused rather than ignored
     *
     * `--resume` and `--rerun` decide which run exists; `--sandbox-profile` and `--allow-network`
     * decide what a run may do. None of them means anything to a reader, and an option that is
     * silently dropped is an option the user believed in. They are rejected by name, so
     * `observe --resume RUN` says so instead of replaying a different run than the one asked for.
    */
    fun parse(args: Array<String>): ObservationParseResult {
        val state = ParseState(deliverableViews = READS_BOTH_LANES)
        var runId: String? = null
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg in EXECUTION_ONLY_OPTIONS) {
                return ObservationParseResult.Rejected(CliError.OptionNotReadable(arg))
            }
            if (!arg.startsWith("--")) {
                // The run id is the ONE positional argument this verb owns, and it is read
                // wherever the operator wrote it: `observe run-1 --view console` and
                // `observe --view console run-1` are the same request.
                //
                // This used to refuse ANY positional argument with [CliError.TrailingOption],
                // whose hint talks about "the script path" — a noun this verb does not have —
                // and the verb read the run id from the FIRST argument only, so a request
                // that wrote the run id after an option was told the run id was missing
                // while it was written right there. `events`, `console` and `operations`
                // already read it in either position.
                if (runId != null) {
                    return ObservationParseResult.Rejected(CliError.TooManyPositionals(arg))
                }
                runId = arg
                index += 1
                continue
            }
            when (val outcome = applyOption(arg, args, index, state)) {
                is ApplyOutcome.Applied -> index = outcome.nextIndex
                is ApplyOutcome.Rejected -> return ObservationParseResult.Rejected(outcome.error)
            }
        }
        if (state.grepInvert && state.grepSelectors.isEmpty()) {
            return ObservationParseResult.Rejected(CliError.InvertWithoutGrep)
        }
        // Before any store is opened. `RunObservationOutput.isIncremental` is the decision; this is
        // only its first of two refusals, so that the follow itself does not have to trust it.
        // After admission, not before: `--view` is parsed in the same pass, and a caller who writes
        // the flag before the view is not making a mistake worth a diagnostic.
        if (state.tailBytes != null && state.view != ObservationView.CONSOLE) {
            return ObservationParseResult.Rejected(
                CliError.TailNeedsTheOutputLane(state.view.name.lowercase()),
            )
        }
        if (state.follow && !RunObservationOutput.isIncremental(state.format)) {
            return ObservationParseResult.Rejected(
                CliError.FollowNeedsAnIncrementalFormat(state.format.wire),
            )
        }
        val query = buildObservationQuery(
            grepSelectors = state.grepSelectors,
            grepInvert = state.grepInvert,
            stageNames = state.stageNames,
            stepNames = state.stepNames,
            eventKinds = state.eventKinds,
            channels = state.channels,
            outcomes = state.outcomes,
        )
        return when (val compiled = compileQuery(query)) {
            is SelectorCompileResult.Ok -> ObservationParseResult.Parsed(
                query = query,
                compiled = compiled.value,
                view = state.view,
                format = state.format,
                follow = state.follow,
                budget = state.budget,
                tailBytes = state.tailBytes,
                dbPath = state.dbPath,
                controlRoot = state.controlRoot,
                runId = runId,
            )

            is SelectorCompileResult.Invalid ->
                ObservationParseResult.Rejected(CliError.InvalidQuery(compiled.reason))
        }
    }
}
