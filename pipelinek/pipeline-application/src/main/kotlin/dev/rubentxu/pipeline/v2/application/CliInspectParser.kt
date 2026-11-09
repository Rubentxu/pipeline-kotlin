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
     * The option table lives here, so the verb's main calls this and
     * translates the typed result into the `inspect: ...` stderr line + the code the taxonomy
     * declares. The `Error:` prefix is not the inspect prefix — inspect prefixes with `inspect:`,
     * and this separation is intentional (events and console share `Error:` as their
     * CLI-admission prefix). [MainInspectCli.parseInspect] stays as the verb's own entry point.
     *
     * ## The rejections are VALUES, not sentences
     *
     * Every refusal below is a [CliError] and carries no words of its own. The sentence the
     * operator reads is written once, in [toCliExit], and every verb that refuses a request says
     * it the same way. A parser that handed the verb a sentence of its own would be a second
     * rendering of the same fact, free to disagree with the first.
    */
    fun parse(args: Array<String>): InspectParseResult = scanOptions(args)
}

/**
 * The options as the operator wrote them, read once.
 *
 * WHICH argument a valued option may consume is [readOptionValue]'s decision,
 * and it is the same decision everywhere in the CLI: a MISSING value is refused,
 * and a value spelled like another option of this build is refused too, so
 * `inspect --db --control-root /tmp/ctl run-1` names the option that would have
 * swallowed the other one instead of answering about a run nobody asked about.
 *
 * The refusals are VALUES, not sentences: the words are written once, in
 * [toCliExit], and this parser hands the error over unchanged.
 *
 * ## Two seams, one per responsibility
 *
 * [readInspectValue] decides WHICH argument an option may consume, and every
 * `apply*` function decides WHAT the option means. The scan does neither of
 * them twice: it reads the argument, and the meaning of the option is applied
 * by the one function that owns that option.
 */
private fun scanOptions(args: Array<String>): InspectParseResult {
    val scan = InspectScan()
    var index = 0
    while (index < args.size) {
        val token = args[index]
        when (token) {
            "--failed", "--state", "--profile", "--explain", "--telemetry", "--otlp" ->
                scan.flags += token

            "--kind", "--stage", "--control-root", "--db", "--endpoint" -> {
                val refusal = readInspectValue(scan, token, args, index, ::applyPlain)
                if (refusal != null) return InspectParseResult.Rejected(refusal)
                index += 1
            }

            "--step" -> {
                val refusal = readInspectValue(scan, token, args, index, ::applyStep)
                if (refusal != null) return InspectParseResult.Rejected(refusal)
                index += 1
            }

            "--context", "--log-tail" -> {
                val refusal = readInspectValue(scan, token, args, index, ::applyCount)
                if (refusal != null) return InspectParseResult.Rejected(refusal)
                index += 1
            }

            "--log-tail-op" -> {
                val refusal = readInspectValue(scan, token, args, index, ::applyOpId)
                if (refusal != null) return InspectParseResult.Rejected(refusal)
                index += 1
            }

            "--fields" -> {
                val refusal = readInspectValue(scan, token, args, index, ::applyFields)
                if (refusal != null) return InspectParseResult.Rejected(refusal)
                index += 1
            }

            "--format" -> {
                val refusal = readInspectValue(scan, token, args, index, ::applyFormat)
                if (refusal != null) return InspectParseResult.Rejected(refusal)
                index += 1
            }

            else -> if (token.startsWith("--")) {
                return InspectParseResult.Rejected(CliError.UnknownOption(token))
            } else if (scan.runId != null) {
                // The FIRST positional is the run id; a SECOND one is not a request of
                // this verb, and it is refused naming the token to remove.
                return InspectParseResult.Rejected(CliError.TooManyPositionals(token))
            } else {
                scan.runId = token
            }
        }
        index++
    }

    // Whole-run reports are modes, and a query carries one. What they may
    // be combined into is decided in its own function, so that this one is
    // about READING the options and not about what they may mean.
    val conflict = rejectConflictingModes(scan.flags)
    if (conflict != null) return conflict

    return InspectParseResult.Parsed(scan.compile())
}

/**
 * Read the value of one option and apply what the option means.
 *
 * The value is read at the argument after the token, and the shared owner
 * decides whether that argument MAY be consumed: a value spelled like an
 * option of this build is refused instead of swallowed. The meaning is then
 * applied by [apply], the one function that owns that option.
 *
 * Null when the value was read and applied; the refusal otherwise.
 */
private fun readInspectValue(
    scan: InspectScan,
    token: String,
    args: Array<String>,
    index: Int,
    apply: (InspectScan, String, String) -> CliError?,
): CliError? = when (val outcome = readOptionValue(token, args, index + 1, INSPECT_OWNED_TOKENS)) {
    is OptionValueOutcome.Present -> apply(scan, token, outcome.value)
    is OptionValueOutcome.Refused -> outcome.error
}

/** A plain value: the option names a path or a name, and the words are kept as they were written. */
private fun applyPlain(scan: InspectScan, token: String, raw: String): CliError? {
    scan.plain[token] = raw
    return null
}

/** `--step` names the step of the run to read. */
private fun applyStep(scan: InspectScan, token: String, raw: String): CliError? {
    val parsed = raw.toLongOrNull()
        ?: return CliError.InvalidOptionValue(token, "must be a Long, got '$raw'")
    scan.step = parsed
    return null
}

/** `--context` and `--log-tail` name how much of the run to read, and neither can be negative. */
private fun applyCount(scan: InspectScan, token: String, raw: String): CliError? {
    val parsed = raw.toIntOrNull()
        ?: return CliError.InvalidOptionValue(token, "must be an Int, got '$raw'")
    if (parsed < 0) {
        return CliError.InvalidOptionValue(token, "must be >= 0, got $parsed")
    }
    scan.counts[token] = parsed
    return null
}

/** `--log-tail-op` names ONE operation to read the tail of. */
private fun applyOpId(scan: InspectScan, token: String, raw: String): CliError? {
    if (raw.isBlank()) {
        return CliError.InvalidOptionValue(token, "must not be blank")
    }
    scan.opId = raw
    return null
}

/** `--fields` names the columns to render. */
private fun applyFields(scan: InspectScan, token: String, raw: String): CliError? {
    val fields = mutableListOf<MainInspectCli.InspectField>()
    for (part in raw.split(',')) {
        val parsed = MainInspectCli.InspectField.parse(part.trim().uppercase())
            ?: return CliError.InvalidOptionValue(token, "unknown token '$part'")
        fields += parsed
    }
    scan.fields = fields
    return null
}

/** `--format` names the rendering. */
private fun applyFormat(scan: InspectScan, token: String, raw: String): CliError? {
    val parsed = MainInspectCli.InspectFormat.parse(raw)
        ?: return CliError.InvalidOptionValue(
            token,
            "must be one of ${MainInspectCli.InspectFormat.values().joinToString { it.cli }}",
        )
    scan.format = parsed
    return null
}

/**
 * The options as the operator wrote them, before they are interpreted.
 *
 * One property per axis, none of them a constructor parameter: the holder is
 * filled while the arguments are scanned and read afterwards, so a constructor
 * would have to carry the same values anyway.
 */
private class InspectScan {
    /** The options whose value is kept as it was written. */
    val plain = mutableMapOf<String, String>()

    /** The options whose value is a count. */
    val counts = mutableMapOf<String, Int>()

    /** The options that carry no value. */
    val flags = mutableSetOf<String>()

    var step: Long? = null
    var opId: String? = null
    var fields: List<MainInspectCli.InspectField>? = null
    var format: MainInspectCli.InspectFormat = MainInspectCli.InspectFormat.TEXT

    /** The run this request is about; null means the operator wrote none. */
    var runId: String? = null

    /** The query these options describe. */
    fun compile(): CompiledInspectionQuery = CompiledInspectionQuery(
        failed = "--failed" in flags,
        kind = plain["--kind"],
        stage = plain["--stage"],
        step = step,
        context = counts["--context"] ?: 0,
        logTail = counts["--log-tail"] ?: 0,
        opId = opId,
        fields = fields,
        format = format,
        controlRoot = plain["--control-root"],
        dbPath = plain["--db"],
        endpoint = plain["--endpoint"],
        state = "--state" in flags,
        profile = "--profile" in flags,
        explain = "--explain" in flags,
        telemetry = "--telemetry" in flags,
        otlp = "--otlp" in flags,
        runId = runId,
    )
}

/**
 * The tokens the inspect verb owns that the shared vocabulary does not know.
 *
 * The verb's other tokens (`--db`, `--kind`, `--stage`, `--step`, `--context`,
 * `--format`, `--control-root`) are in the shared table already, and
 * [readOptionValue] refuses those on its own. These are the ones only this verb
 * knows, and a value spelled like one of them is refused the same way.
 */
private val INSPECT_OWNED_TOKENS: Set<String> = setOf(
    "--endpoint",
    "--failed",
    "--state",
    "--profile",
    "--explain",
    "--telemetry",
    "--otlp",
    "--context",
    "--log-tail",
    "--log-tail-op",
    "--fields",
)

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
     * The socket of a service to ask instead of the local substrate.
     *
     * It is admitted with `--state` and with nothing else, because the
     * service answers the run's state and no other surface this CLI
     * publishes. Null means the operator named no service, and the local
     * path is the only one there is.
     */
    val endpoint: String? = null,
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
private fun rejectConflictingModes(flags: Set<String>): InspectParseResult.Rejected? {
    val modes = listOf("--state", "--profile", "--explain", "--telemetry").filter { it in flags }
    if (modes.size > 1) {
        return InspectParseResult.Rejected(
            CliError.InvalidCombination("a query carries one whole-run report; got ${modes.joinToString(" + ")}"),
        )
    }
    if ("--otlp" in flags && "--telemetry" !in flags) {
        return InspectParseResult.Rejected(
            CliError.InvalidCombination("--otlp renders the telemetry export; it requires --telemetry"),
        )
    }
    return null
}
