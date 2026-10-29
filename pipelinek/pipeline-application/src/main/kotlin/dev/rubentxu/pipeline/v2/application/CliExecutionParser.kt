package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.application.observation.RecordBudget

// The `run` / `validate` verb.
//
// Both verbs read through here: they own exactly ONE positional argument (the
// script path) and the same option set. The option set itself is NOT decided
// here — the meaning of every flag lives in `CliOptionVocabulary`, so a verb
// that reads the same option cannot drift from the one that acts on it.

internal object CliExecutionParser {
    /**
     * The request of a verb that runs or validates a script.
     *
     * ## What this entry point owns
     *
     * Two verbs read through HERE — `run` and `validate` — and both of them
     * own exactly ONE positional argument: the script path. A request that
     * carries another one is not a request of these verbs, and it is REFUSED,
     * by name, before any effect. Measured on the built distribution, `run
     * s.kts extra-arg` ran the pipeline and said NOTHING about `extra-arg`,
     * and `validate s.kts extra-arg` answered "VALIDATION SUCCESSFUL" with
     * the byte-identical output of the request that carries no extra
     * argument: the operator read an answer about a request nobody made.
     *
     * The refusal has nowhere else to go. The DSL opens no argument list for
     * a script — `pipeline { }` takes no arguments, and `ScriptDefinition.file`
     * takes none either — so a trailing positional is not an argument the
     * pipeline could receive later.
     *
     * ## What this entry point does NOT own
     *
     * `console` owns TWO positional arguments — the run id and the operation
     * id — and reads them through its own entry point, which already refuses
     * a third. How many positional arguments a verb owns is
     * that verb's business; this refusal is about the verbs that come through
     * HERE.
     */
    fun parse(args: Array<String>): CliParseResult {
        if (args.isEmpty()) return CliParseResult.Rejected(CliError.MissingCommand)

        val command = CLI_PARSER_COMMANDS[args[0]]?.command
            ?: return CliParseResult.Rejected(CliError.InvalidCommand(args[0]))

        val state = ParseState()
        var index = 1

        while (index < args.size && args[index].startsWith("--")) {
            when (val outcome = applyOption(args[index], args, index, state)) {
                is ApplyOutcome.Applied -> index = outcome.nextIndex
                is ApplyOutcome.Rejected -> return CliParseResult.Rejected(outcome.error)
            }
        }

        val scriptPath = args.getOrNull(index)
            ?: return CliParseResult.Rejected(CliError.MissingScriptPath)

        // Fail closed on trailing options. The scan above stops at the first
        // non-`--` argument, so before this check `run s.kts --format text`
        // discarded everything after the script and still ran, producing the
        // default output. The operator believed a flag was applied; it was not.
        // Options must precede the script path, and an unknown one anywhere is
        // an error rather than a silent no-op.
        val tails = args.drop(index + 1)
        val trailingOption = tails.firstOrNull { it.startsWith("--") }
        if (trailingOption != null) {
            return CliParseResult.Rejected(CliError.TrailingOption(trailingOption))
        }

        // Fail closed on a trailing positional too, which is the same failure
        // with a different shape: the verbs that come through here own ONE
        // positional argument, and the request carried another one. The token
        // is named because it is what the operator has to remove.
        //
        // The option is named first when a request carries both: an operator
        // who wrote `--format` after the script path believed in a flag, and
        // that belief is the one to correct before anything else.
        val extraPositional = tails.firstOrNull { !it.startsWith("--") }
        if (extraPositional != null) {
            return CliParseResult.Rejected(CliError.TooManyPositionals(extraPositional))
        }

        // Fail closed on the incompatible pair.
        state.workspace?.let { explicit ->
            if (state.isolated) {
                return CliParseResult.Rejected(CliError.ConflictingWorkspaceModes(explicit))
            }
        }

        // `applyOption` is shared with `CliObservationParser.parse`, so the reader's options land here too —
        // into a `CliFlags` that has no field for them. Refused by name rather than dropped: a
        // truncated live transcript that cannot say it was truncated is the one output shape this
        // repository refuses to produce.
        if (state.follow) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--follow"))
        }
        if (state.budget !is RecordBudget.All) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--limit"))
        }
        if (state.tailBytes != null) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--tail-bytes"))
        }
        if (state.outcomes.isNotEmpty()) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--outcome"))
        }

        // `--grep-invert` negates a group; with no group there is nothing to
        // negate. Rejected rather than defaulted to "exclude everything".
        if (state.grepInvert && state.grepSelectors.isEmpty()) {
            return CliParseResult.Rejected(CliError.InvertWithoutGrep)
        }

        val assembledQuery = buildObservationQuery(
            grepSelectors = state.grepSelectors,
            grepInvert = state.grepInvert,
            stageNames = state.stageNames,
            stepNames = state.stepNames,
            eventKinds = state.eventKinds,
            channels = state.channels,
            outcomes = state.outcomes,
        )

        // Compile here, before any effect: an uncompilable regex is refused
        // rather than discovered at the moment output is being produced.
        val compiledQuery = compileQuery(assembledQuery)
        if (compiledQuery is SelectorCompileResult.Invalid) {
            return CliParseResult.Rejected(CliError.InvalidQuery(compiledQuery.reason))
        }

        return CliParseResult.Parsed(
            CliFlags(
                command = command,
                dbPath = state.dbPath,
                durableRunPolicy = state.durableRunPolicy,
                scriptPath = scriptPath,
                controlRoot = state.controlRoot,
                workspace = state.workspace,
                isolated = state.isolated,
                sandboxProfile = state.sandboxProfile,
                allowNetwork = state.allowNetwork,
                pluginJars = state.pluginJars.toList(),
                pluginTrust = state.pluginTrust,
                correlationId = state.correlationId,
                view = state.view,
                format = state.format,
                outputProtocolTokens = state.outputProtocolTokens,
                query = assembledQuery,
            ),
        )
    }
}
