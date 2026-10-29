package dev.rubentxu.pipeline.v2.application

// The `inspect` verb.
//
// The run id is the ONE positional argument this verb owns, and it is read
// wherever the operator wrote it: the parser is the only place that knows
// which options carry a value, so the run id is whichever argument is not an
// option and not an option's value.

internal object CliInspectParser {
    /**
     * `pipelinek inspect <runId> [opts]`.
     *
     * The option table moved here from the verb, so the verb's main now calls this and
     * translates the typed result into the `inspect: ...` stderr line + the code the taxonomy
     * declares. The `Error:` prefix is not the inspect prefix — inspect prefixes with `inspect:`,
     * and this separation is intentional (events and console share `Error:` as their
     * CLI-admission prefix). [MainInspectCli.parseInspect] stays as the verb's own entry point,
     * and the option-table layout is why this function carries the same
     * `@Suppress("CyclomaticComplexMethod")`.
     *
     * ## The rejections are VALUES, not sentences
     *
     * Every refusal below is a [CliError] and carries no words of its own. The sentence the
     * operator reads is written once, in [toCliExit], and every verb that refuses a request says
     * it the same way. This parser used to hand the verb a sentence it had written itself
     * ("unknown option '--x'"), and the verb printed it with its own prefix: a second rendering
     * of the same fact, free to disagree with the first.
    */
    @Suppress("CyclomaticComplexMethod")
    fun parse(args: Array<String>): InspectParseResult {
        var failed = false
        var kind: String? = null
        var stage: String? = null
        var step: Long? = null
        var context = 0
        var logTail = 0
        var opId: String? = null
        var fields: List<MainInspectCli.InspectField>? = null
        var format: MainInspectCli.InspectFormat = MainInspectCli.InspectFormat.TEXT
        var controlRoot: String? = null
        var dbPath: String? = null
        var state = false
        var profile = false
        var explain = false
        var telemetry = false
        var otlp = false
        // The run id is the ONE positional argument this verb owns, and it is read
        // wherever the operator wrote it: the parser is the only place that knows
        // which options carry a value, so the run id is whichever argument is not an
        // option and not an option's value.
        var runId: String? = null

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when (a) {
                "--failed" -> failed = true
                "--state" -> state = true
                "--profile" -> profile = true
                "--explain" -> explain = true
                "--telemetry" -> telemetry = true
                "--otlp" -> otlp = true
                "--kind" -> {
                    kind = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--kind"))
                }
                "--stage" -> {
                    stage = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--stage"))
                }
                "--step" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--step"))
                    step = raw.toLongOrNull()
                        ?: return rejectInspect(CliError.InvalidOptionValue("--step", "must be a Long, got '$raw'"))
                }
                "--context" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--context"))
                    context = raw.toIntOrNull()
                        ?: return rejectInspect(CliError.InvalidOptionValue("--context", "must be an Int, got '$raw'"))
                    if (context < 0) {
                        return rejectInspect(CliError.InvalidOptionValue("--context", "must be >= 0, got $context"))
                    }
                }
                "--log-tail" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--log-tail"))
                    logTail = raw.toIntOrNull()
                        ?: return rejectInspect(CliError.InvalidOptionValue("--log-tail", "must be an Int, got '$raw'"))
                    if (logTail < 0) {
                        return rejectInspect(CliError.InvalidOptionValue("--log-tail", "must be >= 0, got $logTail"))
                    }
                }
                "--log-tail-op" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--log-tail-op"))
                    if (raw.isBlank()) {
                        return rejectInspect(CliError.InvalidOptionValue("--log-tail-op", "must not be blank"))
                    }
                    opId = raw
                }
                "--fields" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--fields"))
                    fields = raw.split(',').map { token ->
                        val upper = token.trim().uppercase()
                        MainInspectCli.InspectField.parse(upper)
                            ?: return rejectInspect(CliError.InvalidOptionValue("--fields", "unknown token '$token'"))
                    }
                }
                "--format" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--format"))
                    format = MainInspectCli.InspectFormat.parse(raw) ?: return rejectInspect(
                        CliError.InvalidOptionValue(
                            "--format",
                            "must be one of ${MainInspectCli.InspectFormat.values().joinToString { it.cli }}",
                        ),
                    )
                }
                "--control-root" -> {
                    controlRoot = args.getOrNull(++i)
                        ?: return rejectInspect(CliError.MissingOptionValue("--control-root"))
                }
                "--db" -> {
                    dbPath = args.getOrNull(++i) ?: return rejectInspect(CliError.MissingOptionValue("--db"))
                }
                else -> if (a.startsWith("--")) {
                    return rejectInspect(CliError.UnknownOption(a))
                } else if (runId != null) {
                    // The FIRST positional is the run id; a SECOND one is not a request of
                    // this verb, and it is refused naming the token to remove.
                    return rejectInspect(CliError.TooManyPositionals(a))
                } else {
                    runId = a
                }
            }
            i++
        }

        // Whole-run reports are modes, and a query carries one. What they may
        // be combined into is decided in its own function, so that this one is
        // about READING the options and not about what they may mean.
        val conflict = rejectConflictingModes(state, profile, explain, telemetry, otlp)
        if (conflict != null) return conflict

        return InspectParseResult.Parsed(
            CompiledInspectionQuery(
                failed = failed,
                kind = kind,
                stage = stage,
                step = step,
                context = context,
                logTail = logTail,
                opId = opId,
                fields = fields,
                format = format,
                controlRoot = controlRoot,
                dbPath = dbPath,
                state = state,
                profile = profile,
                explain = explain,
                telemetry = telemetry,
                otlp = otlp,
                runId = runId,
            ),
        )
    }
}

private fun rejectInspect(error: CliError): InspectParseResult.Rejected =
    InspectParseResult.Rejected(error)

/**
 * The refusal when a query carries more than one whole-run report.
 *
 * The four modes answer four different questions, and a query that asks
 * two of them at once would have to order them in an order the caller
 * never chose.
 *
 * `--otlp` is not a report of its own: it is another rendering of the
 * telemetry report, so asked for alone it would have no run to render.
 *
 * Null when the query carries at most one report.
 */
private fun rejectConflictingModes(
    state: Boolean,
    profile: Boolean,
    explain: Boolean,
    telemetry: Boolean,
    otlp: Boolean,
): InspectParseResult.Rejected? {
    val modes = buildList {
        if (state) add("--state")
        if (profile) add("--profile")
        if (explain) add("--explain")
        if (telemetry) add("--telemetry")
    }
    if (modes.size > 1) {
        return rejectInspect(
            CliError.InvalidCombination(
                "a query carries one whole-run report; got ${modes.joinToString(" + ")}",
            ),
        )
    }
    if (otlp && !telemetry) {
        return rejectInspect(
            CliError.InvalidCombination("--otlp renders the telemetry export; it requires --telemetry"),
        )
    }
    return null
}
