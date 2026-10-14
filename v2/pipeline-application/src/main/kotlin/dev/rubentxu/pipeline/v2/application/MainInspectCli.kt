package dev.rubentxu.pipeline.v2.application

/**
 * `pipelinek inspect <run>` — bounded diagnostic surface for a single run.
 *
 * ## What this verb is
 *
 * The primary bounded diagnostic surface named by ADR-0088 §Decision. Five typed dimensions:
 *
 * ```text
 * inputs   --control-root PATH, --db PATH, <runId>
 * state    --failed (boolean shorthand for outcome=failure), --kind K, --stage S, --step N
 * context  --context N (records around a match), --log-tail N (bytes per failed step)
 * shape    --fields STAGE,STEP,OUTCOME,MESSAGE,CONSOLETAIL
 * output   --format json|text
 * ```
 *
 * `--failed`, `--kind`, `--stage`, `--step`, `--context` and `--log-tail` close the gaps
 * in R4_CLI_GAP_ANALYSIS §1..§4. `--fields` projects to one of the [InspectField] cases; the
 * ADT is closed so adding a sixth field forces every caller to be revisited.
 *
 * ## What this verb is NOT
 *
 *  - NOT a second authority on run state. The event store and the Output Plane keep theirs.
 *    `inspect` reads both lanes; it does not claim they interleaved.
 *  - NOT a substitute for `observe`. `observe` returns the full envelopes + bytes; `inspect`
 *    returns a bounded diagnostic document. The two are the bounded and the exhaustive halves
 *    of the same read authority.
 *  - NOT a follow loop. A query that wants live events uses `observe --follow`; `inspect`
 *    is a static query against the durable history.
 *
 * ## R4 / WU-R4.1 — skeleton
 *
 * This slice ships the PARSER + the closed ADT + the typed query record + the dispatcher
 * entry point. The full read-side (event-store and Output Plane wiring) is implemented
 * incrementally under the same closed ADT; for now the verb parses, validates, and reports
 * the typed query on stdout so a caller can confirm the surface and the field selection
 * before the durable read is wired.
 *
 * Exit codes follow the canonical contract:
 *   0 — parsed successfully and emitted the typed query;
 *   2 — usage error (missing run id, unknown option, refused value).
 */
object MainInspectCli {

    const val USAGE: String =
        "Usage: pipelinek inspect <runId> [--control-root <dir>] [--db <path>] " +
            "[--failed] [--kind K] [--stage S] [--step N] " +
            "[--context N] [--log-tail N] " +
            "[--fields STAGE,STEP,OUTCOME,MESSAGE,CONSOLETAIL] " +
            "[--format text|json]"

    /**
     * The five dimensional columns a caller can request from the inspection document.
     *
     * Closed ADT (R4 / WU-R4.1, distinct from `EventField`):
     *
     *   * `Stage`    — the stage name the record was inspected for (--stage).
     *   * `Step`     — the step ordinal within the stage (--step).
     *   * `Outcome`  — the outcome value the record carried (success / failure / unstable / aborted).
     *   * `Message`  — the typed failure message, when the record has one.
     *   * `ConsoleTail` — the last `logTail` bytes of the Output Plane for the failed step's op.
     *
     * Adding a sixth case is a compile-time break for every call site that pattern-matches
     * over [InspectField]; that is the discipline that makes the projection closed.
     */
    sealed class InspectField(val cli: String) {
        abstract fun extract(record: InspectionRecord): String

        object Stage : InspectField("STAGE") {
            override fun extract(record: InspectionRecord): String = record.stage ?: ""
        }

        object Step : InspectField("STEP") {
            override fun extract(record: InspectionRecord): String =
                record.stepOrdinal?.toString() ?: ""
        }

        object Outcome : InspectField("OUTCOME") {
            override fun extract(record: InspectionRecord): String = record.outcome
        }

        object Message : InspectField("MESSAGE") {
            override fun extract(record: InspectionRecord): String = record.message ?: ""
        }

        object ConsoleTail : InspectField("CONSOLETAIL") {
            override fun extract(record: InspectionRecord): String = record.consoleTail ?: ""
        }

        companion object {
            fun parse(token: String): InspectField? = when (token) {
                "STAGE" -> Stage
                "STEP" -> Step
                "OUTCOME" -> Outcome
                "MESSAGE" -> Message
                "CONSOLETAIL" -> ConsoleTail
                else -> null
            }
        }
    }

    /** Output format. Closed ADT. */
    enum class InspectFormat(val cli: String) {
        TEXT("text"),
        JSON("json");

        companion object {
            fun parse(token: String): InspectFormat? = values().firstOrNull { it.cli == token }
        }
    }

    /**
     * Entry point for `pipelinek inspect <runId> [opts]`.
     *
     * R4 / WU-R4.1 — the parser + the typed query are the contract. The durable read seam
     * (SqliteEventStore + FrameIndexedObservationOutputReader.readTail) is the next slice; for
     * now the verb reports the typed query on stdout so the CLI dispatch + field selection
     * are exercisable today.
     */
    fun main(args: Array<String>): Int {
        val runId = args.firstOrNull()?.takeIf { !it.startsWith("--") }
        if (runId == null) {
            System.err.println("inspect: a run id is required.")
            System.err.println(USAGE)
            return 2
        }
        val optionArgs = args.drop(if (args.first() == runId) 1 else 0)

        val compiled = when (val r = parseInspect(optionArgs.toTypedArray())) {
            is InspectParseResult.Parsed -> r.value
            is InspectParseResult.Rejected -> {
                System.err.println("inspect: ${r.error}")
                System.err.println(USAGE)
                return 2
            }
        }

        when (compiled.format) {
            InspectFormat.TEXT -> {
                System.out.println("inspect run=$runId")
                System.out.println(
                    "  failed=${compiled.failed} kind=${compiled.kind ?: "*"} " +
                        "stage=${compiled.stage ?: "*"} step=${compiled.step ?: "*"} " +
                        "context=${compiled.context} logTail=${compiled.logTail}",
                )
                val cols = compiled.fields ?: defaultColumns()
                System.out.println("  fields=${cols.joinToString(",") { it.cli }}")
            }
            InspectFormat.JSON -> {
                System.out.println("{")
                System.out.println("  \"runId\": \"$runId\",")
                System.out.println("  \"failed\": ${compiled.failed},")
                System.out.println("  \"kind\": ${jsonOrNull(compiled.kind)},")
                System.out.println("  \"stage\": ${jsonOrNull(compiled.stage)},")
                System.out.println("  \"step\": ${compiled.step?.toString() ?: "null"},")
                System.out.println("  \"context\": ${compiled.context},")
                System.out.println("  \"logTail\": ${compiled.logTail},")
                val cols = compiled.fields ?: defaultColumns()
                System.out.println(
                    "  \"fields\": [${cols.joinToString(",") { "\"${it.cli.lowercase()}\"" }}]",
                )
                System.out.println("}")
            }
        }
        return 0
    }

    private fun defaultColumns(): List<InspectField> = listOf(
        InspectField.Stage, InspectField.Step, InspectField.Outcome, InspectField.Message,
    )

    private fun jsonOrNull(s: String?): String =
        if (s == null) "null" else "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /**
     * Parse the option args (run id consumed upstream) into a [CompiledInspectionQuery].
     *
     * The shared parser work (option meaning, error formatting) lives here, NOT in a third
     * bespoke private object: any future CLI verb that wants `--failed`, `--kind`, `--stage`,
     * `--step`, `--context`, `--log-tail`, `--fields` or `--format` joins `CliParser.parseObservation`
     * (R4 / WU-R4.2) instead of re-inventing the surface.
     */
    fun parseInspect(args: Array<String>): InspectParseResult {
        var failed = false
        var kind: String? = null
        var stage: String? = null
        var step: Long? = null
        var context = 0
        var logTail = 0
        var fields: List<InspectField>? = null
        var format: InspectFormat = InspectFormat.TEXT
        var controlRoot: String? = null
        var dbPath: String? = null

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when (a) {
                "--failed" -> failed = true
                "--kind" -> {
                    kind = args.getOrNull(++i) ?: return reject("--kind requires a value")
                }
                "--stage" -> {
                    stage = args.getOrNull(++i) ?: return reject("--stage requires a value")
                }
                "--step" -> {
                    val raw = args.getOrNull(++i) ?: return reject("--step requires a value")
                    step = raw.toLongOrNull() ?: return reject("--step must be a Long, got '$raw'")
                }
                "--context" -> {
                    val raw = args.getOrNull(++i) ?: return reject("--context requires a value")
                    context = raw.toIntOrNull() ?: return reject("--context must be an Int, got '$raw'")
                    if (context < 0) return reject("--context must be >= 0, got $context")
                }
                "--log-tail" -> {
                    val raw = args.getOrNull(++i) ?: return reject("--log-tail requires a value")
                    logTail = raw.toIntOrNull() ?: return reject("--log-tail must be an Int, got '$raw'")
                    if (logTail < 0) return reject("--log-tail must be >= 0, got $logTail")
                }
                "--fields" -> {
                    val raw = args.getOrNull(++i) ?: return reject("--fields requires a value")
                    fields = raw.split(',').map { token ->
                        val upper = token.trim().uppercase()
                        InspectField.parse(upper)
                            ?: return reject("--fields has unknown token '$token'")
                    }
                }
                "--format" -> {
                    val raw = args.getOrNull(++i) ?: return reject("--format requires a value")
                    format = InspectFormat.parse(raw) ?: return reject(
                        "--format must be one of ${InspectFormat.values().joinToString { it.cli }}",
                    )
                }
                "--control-root" -> {
                    controlRoot = args.getOrNull(++i) ?: return reject("--control-root requires a value")
                }
                "--db" -> {
                    dbPath = args.getOrNull(++i) ?: return reject("--db requires a value")
                }
                else -> if (a.startsWith("--")) {
                    return reject("unknown option '$a'")
                } else {
                    return reject("unexpected positional argument '$a'")
                }
            }
            i++
        }

        return InspectParseResult.Parsed(
            CompiledInspectionQuery(
                failed = failed,
                kind = kind,
                stage = stage,
                step = step,
                context = context,
                logTail = logTail,
                fields = fields,
                format = format,
                controlRoot = controlRoot,
                dbPath = dbPath,
            ),
        )
    }

    private fun reject(message: String): InspectParseResult.Rejected =
        InspectParseResult.Rejected(message)
}

/** Parsed inspection query. Immutable data class. */
data class CompiledInspectionQuery(
    val failed: Boolean = false,
    val kind: String? = null,
    val stage: String? = null,
    val step: Long? = null,
    val context: Int = 0,
    val logTail: Int = 0,
    val fields: List<MainInspectCli.InspectField>? = null,
    val format: MainInspectCli.InspectFormat = MainInspectCli.InspectFormat.TEXT,
    val controlRoot: String? = null,
    val dbPath: String? = null,
)

/** Result of [MainInspectCli.parseInspect]. */
sealed interface InspectParseResult {
    data class Parsed(val value: CompiledInspectionQuery) : InspectParseResult
    data class Rejected(val error: String) : InspectParseResult
}

/**
 * One inspected record — the row shape that the projection fields read from.
 *
 * Kept as a data class, not a sealed ADT: every record carries all five columns, with `null` for
 * the ones that do not apply, because the consumer chooses which fields to project and a row that
 * has `null` for un-requested fields is not a different SHAPE of record.
 */
data class InspectionRecord(
    val stage: String? = null,
    val stepOrdinal: Long? = null,
    val outcome: String = "",
    val message: String? = null,
    val consoleTail: String? = null,
)