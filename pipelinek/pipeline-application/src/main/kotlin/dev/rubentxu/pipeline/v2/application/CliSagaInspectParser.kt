package dev.rubentxu.pipeline.v2.application

// The `saga inspect` verb.
//
// WHICH argument a valued option may consume is [readOptionValue]'s decision,
// and it is the same decision everywhere in the CLI. The refusals are VALUES:
// the words the operator reads are written once, in [toCliExit], and this
// parser hands the error over unchanged.

internal object CliSagaInspectParser {
    /**
     * `pipelinek saga inspect <runId> --db <path> [--format text|json]`.
     *
     * The parser reads the OPTIONS. The two decisions that belong to the
     * REQUEST rather than to the options — that the run id is required, and
     * that the durable store is required — stay with the verb, which refuses
     * them in that order: a command line that names neither is told about the
     * run id first.
     */
    fun parse(args: Array<String>): SagaInspectParseResult = scanOptions(args)
}

/** The options of a saga inspection, as the operator wrote them. */
internal data class SagaInspectOptions(
    val dbPath: String?,
    val format: SagaInspectFormat,
    /** The run this request is about; null means the operator wrote none. */
    val runId: String?,
)

/** The formats this verb renders. The spelling is derived, so the refusal can name them. */
internal enum class SagaInspectFormat(val cli: String) {
    TEXT("text"),
    JSON("json"),
}

/** The outcome of reading the saga verb's options. */
internal sealed interface SagaInspectParseResult {
    data class Parsed(val value: SagaInspectOptions) : SagaInspectParseResult

    /**
     * The refusal, as the typed error the renderer turns into a sentence.
     *
     * Not a string: the words belong to [toCliExit], so every verb that
     * refuses a request says it the same way.
     */
    data class Rejected(val error: CliError) : SagaInspectParseResult
}

/**
 * Read the options of the saga verb.
 *
 * The run id is the ONE positional argument this verb owns, and it is read
 * wherever the operator wrote it: the parser is the only place that knows
 * which options carry a value, so the run id is whichever argument is not an
 * option and not an option's value.
 */
private fun scanOptions(args: Array<String>): SagaInspectParseResult {
    val scan = SagaInspectScan()
    var index = 0
    while (index < args.size) {
        when (val token = args[index]) {
            "--db" -> {
                when (val read = readSagaValue(token, args, index)) {
                    is SagaValue.Refused -> return SagaInspectParseResult.Rejected(read.error)
                    is SagaValue.Value -> scan.dbPath = read.value
                }
                index += 1
            }

            "--format" -> {
                when (val read = readSagaValue(token, args, index)) {
                    is SagaValue.Refused -> return SagaInspectParseResult.Rejected(read.error)
                    is SagaValue.Value -> {
                        val parsed = SagaInspectFormat.entries.firstOrNull { it.cli == read.value }
                            ?: return SagaInspectParseResult.Rejected(
                                CliError.InvalidOptionValue(
                                    token,
                                    "must be one of ${SagaInspectFormat.entries.joinToString { it.cli }}, " +
                                        "got '${read.value}'",
                                ),
                            )
                        scan.format = parsed
                    }
                }
                index += 1
            }

            else -> if (token.startsWith("--")) {
                return SagaInspectParseResult.Rejected(CliError.UnknownOption(token))
            } else if (scan.runId != null) {
                // The FIRST positional is the run id; a SECOND one is not a request of
                // this verb, and it is refused naming the token to remove.
                return SagaInspectParseResult.Rejected(CliError.TooManyPositionals(token))
            } else {
                scan.runId = token
            }
        }
        index++
    }
    return SagaInspectParseResult.Parsed(scan.compile())
}

/**
 * The value of an option, or the refusal that there is none.
 *
 * The shared owner decides whether the argument after the token MAY be
 * consumed: a value spelled like an option of this build is refused instead of
 * swallowed, and the refusal is a [CliError] the verb hands over unchanged.
 */
private sealed interface SagaValue {
    data class Value(val value: String) : SagaValue
    data class Refused(val error: CliError) : SagaValue
}

/** The value of the option [token], read at the argument after it. */
private fun readSagaValue(token: String, args: Array<String>, index: Int): SagaValue =
    when (val outcome = readOptionValue(token, args, index + 1)) {
        is OptionValueOutcome.Present -> SagaValue.Value(outcome.value)
        is OptionValueOutcome.Refused -> SagaValue.Refused(outcome.error)
    }

/** The options as the operator wrote them, before they are interpreted. */
private class SagaInspectScan {
    var dbPath: String? = null
    var format: SagaInspectFormat = SagaInspectFormat.TEXT

    /** The run this request is about; null means the operator wrote none. */
    var runId: String? = null

    /** The options these values describe. */
    fun compile(): SagaInspectOptions = SagaInspectOptions(dbPath, format, runId)
}
