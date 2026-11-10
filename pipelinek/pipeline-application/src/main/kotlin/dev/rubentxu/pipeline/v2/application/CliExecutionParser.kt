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
    fun parse(
        args: Array<String>,
        provider: IsolationProvider = BwrapIsolationProvider,
    ): CliParseResult {
        if (args.isEmpty()) return CliParseResult.Rejected(CliError.MissingCommand)

        val command = CLI_PARSER_COMMANDS[args[0]]?.command
            ?: return CliParseResult.Rejected(CliError.InvalidCommand(args[0]))

        val state = ParseState(provider = provider)
        var index = 1

        while (index < args.size && args[index].startsWith("--")) {
            when (val outcome = applyOption(args[index], args, index, state)) {
                is ApplyOutcome.Applied -> index = outcome.nextIndex
                is ApplyOutcome.Rejected -> return CliParseResult.Rejected(outcome.error)
            }
        }

        val scriptPath = args.getOrNull(index)
            ?: return CliParseResult.Rejected(CliError.MissingScriptPath)

        // A script path that is BLANK is not "no script path": the filesystem
        // resolves an empty path to the working directory, and the refusal that
        // comes back names that directory — a path the operator never wrote.
        // Decided here, at the boundary that read the argument, it names the
        // argument instead.
        if (scriptPath.isBlank()) {
            return CliParseResult.Rejected(blankNamedValueRefusal("script path"))
        }

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

        // `--receipt` is the account a run keeps of itself: it names a file the RUN writes.
        // `validate` compiles a script and publishes nothing, so accepting the option there
        // would promise a file that never appears. Refused by naming the verb that owns it.
        //
        // `--compile-only` asks for a compilation WITHOUT running the author's body, and that
        // is a promise only `validate` can keep: `run` executes the script, so "compile it
        // without running it" is not a run the operator can ask for. Refused by naming the
        // verb that owns it, exactly as `--receipt` is refused on the other side.
        foreignVerbOptionRefusal(state, command)?.let { return CliParseResult.Rejected(it) }

        // `--require-plugin-trust` demands that every plugin the operator brought carries a
        // trust decision. Only a verb that LOADS plugins can keep that promise: `validate`
        // compiles the script against the plugin classpath and admits nothing, so no trust
        // decision is ever taken and the demand would be answered by silence. Refused by
        // naming the verb that owns it.
        if (state.requirePluginTrust && command == CliCommand.VALIDATE) {
            return CliParseResult.Rejected(
                CliError.OptionBelongsToVerb(
                    "--require-plugin-trust",
                    "run",
                    REQUIRE_PLUGIN_TRUST_BELONGS_TO_RUN,
                ),
            )
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
                requirePluginTrust = state.requirePluginTrust,
                correlationId = state.correlationId,
                receiptPath = state.receiptPath,
                runConfigPath = state.runConfigPath,
                runInputsPath = state.runInputsPath,
                parameters = state.parameterValues.toMap(),
                describeParameters = state.describeParameters,
                view = state.view,
                format = state.format,
                outputProtocolTokens = state.outputProtocolTokens,
                query = assembledQuery,
                compileOnly = state.compileOnly,
            ),
        )
    }

    /**
     * The refusal an option that belongs to the OTHER verb of this pair earns here, or nothing.
     *
     * The two verbs share one option vocabulary, and the options are promises only one of
     * them can keep: `--receipt` names a file the RUN writes, `--run-config` names a file the
     * RUN reads, and `--compile-only` asks for a compilation without running the author's body.
     * Each is refused by naming the verb that owns it.
     *
     * They are one fact with two faces, so they are asked together and the first one the
     * request carries is the one that is named.
     */
    private fun foreignVerbOptionRefusal(state: ParseState, command: CliCommand): CliError? = when {
        state.receiptPath != null && command == CliCommand.VALIDATE ->
            CliError.OptionBelongsToVerb("--receipt", "run", RECEIPT_BELONGS_TO_RUN)
        state.runConfigPath != null && command == CliCommand.VALIDATE ->
            CliError.OptionBelongsToVerb("--run-config", "run", RUN_CONFIG_BELONGS_TO_RUN)
        state.runInputsPath != null && command == CliCommand.VALIDATE ->
            CliError.OptionBelongsToVerb("--run-inputs", "run", RUN_INPUTS_BELONGS_TO_RUN)
        state.parameterValues.isNotEmpty() && command == CliCommand.VALIDATE ->
            CliError.OptionBelongsToVerb("--param", "run", PARAM_BELONGS_TO_RUN)
        state.compileOnly && command == CliCommand.RUN ->
            CliError.OptionBelongsToVerb("--compile-only", "validate", COMPILE_ONLY_BELONGS_TO_VALIDATE)
        else -> null
    }

    /** Why `--receipt` is refused by the verb that never runs anything. */
    private const val RECEIPT_BELONGS_TO_RUN =
        "'validate' compiles a script and publishes nothing, so it has no receipt to write."

    /**
     * Why `--run-config` is refused by the verb that never runs anything.
     *
     * The snapshot of the configuration is read by the RUN, and its values
     * become the environment of the steps of the run. A validation has no
     * steps, so accepting the option would promise an environment that
     * never exists.
     */
    private const val RUN_CONFIG_BELONGS_TO_RUN =
        "'validate' compiles a script and runs no step, so it has no configuration to apply."

    /**
     * Why `--run-inputs` is refused by the verb that never runs anything.
     *
     * The snapshot of the parameters is read by the RUN, and its values are the inputs of the
     * run. A validation has no steps, so accepting the option would promise values that
     * never exist.
     */
    private const val RUN_INPUTS_BELONGS_TO_RUN =
        "'validate' compiles a script and runs no step, so it has no parameters to apply."

    /**
     * Why `--param` is refused by the verb that never runs anything.
     *
     * The values of the parameters are delivered to a RUN, and the run is the thing that
     * checks them against the declaration the script carries.
     */
    private const val PARAM_BELONGS_TO_RUN =
        "'validate' compiles a script and runs no step, so it has no parameters to deliver."

    /** Why `--compile-only` is refused by the verb that runs the author's body. */
    private const val COMPILE_ONLY_BELONGS_TO_VALIDATE =
        "'run' executes the script, so compiling it without running it is 'validate --compile-only'."

    /** Why `--require-plugin-trust` is refused by the verb that loads no plugin. */
    private const val REQUIRE_PLUGIN_TRUST_BELONGS_TO_RUN =
        "'validate' admits no plugin, so it takes no trust decision: requiring one is " +
            "'run --require-plugin-trust'."
}
