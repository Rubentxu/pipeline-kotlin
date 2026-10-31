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
 * Parsed inspection query. Immutable data class.
 *
 * It lives here, next to the parser that builds it, because the parser is its only producer: the
 * verb receives it already compiled. The type keeps its FQN, so nothing that names it stops
 * compiling.
*/
data class CompiledInspectionQuery(
    val failed: Boolean = false,
    val kind: String? = null,
    val stage: String? = null,
    val step: Long? = null,
    val context: Int = 0,
    val logTail: Int = 0,
    /**
     * When set, the `--log-tail` scope is one operation (this opId),
     * not the whole run. The per-op read filters frames whose stream id carries this
     * opId as a middle segment. When null, the tail is the run-wide last-N-bytes path.
*/
    val opId: String? = null,
    val fields: List<MainInspectCli.InspectField>? = null,
    val format: MainInspectCli.InspectFormat = MainInspectCli.InspectFormat.TEXT,
    val controlRoot: String? = null,
    val dbPath: String? = null,
    /**
     * Report the RUN's lifecycle state instead of its records.
     *
     * The state is the run's own terminal fact — the event lane's `RunFinished`
     * bookend — read through the published introspection port. It is a MODE
     * and not another filter: the filters below select RECORDS, and a record
     * is not a run. Asking for the state of a filtered view of a run has no
     * honest answer, so the two are not combined.
     */
    val state: Boolean = false,
    /**
     * Report the RUN's cost profile instead of its records. A mode like
     * [state]: both answer about the run as a whole, so the parser refuses a
     * query that asks for both reports at once.
     */
    val profile: Boolean = false,
    /**
     * Report the RUN's diagnosis instead of its records: typed findings with
     * evidence references, one whole-run report like [state] and [profile].
     */
    val explain: Boolean = false,
    /**
     * Export the RUN's facts as redacted telemetry signals instead of its
     * records: the fourth whole-run report mode.
     */
    val telemetry: Boolean = false,
    /**
     * Render the telemetry export in the OpenTelemetry shape instead of the
     * line-delimited one. Not a mode of its own: it is another rendering of the
     * telemetry export, so the parser refuses it without `--telemetry`.
     */
    val otlp: Boolean = false,
    /**
     * The run this query is about, read from the ONE positional argument this
     * verb owns. Null means the operator wrote none.
     *
     * It travels HERE and not in the verb because the parser is the only place
     * that knows which options carry a value: the run id is whichever argument
     * is not an option and not an option's value, and a verb that looked for it
     * by position would have to guess.
     */
    val runId: String? = null,
)

/** Result of [MainInspectCli.parseInspect]. */
sealed interface InspectParseResult {
    data class Parsed(val value: CompiledInspectionQuery) : InspectParseResult

    /**
     * The refusal, as the typed error the renderer turns into a sentence.
     *
     * Not a string: the words the operator reads belong to [toCliExit], and a
     * parser that hands out its own sentences is a second rendering free to
     * disagree with the first.
     */
    data class Rejected(val error: CliError) : InspectParseResult
}

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
