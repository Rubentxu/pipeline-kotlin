package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.profile.summarize
import java.nio.file.Paths

/**
 * `pipelinek inspect <run>` — bounded diagnostic surface for a single run.
 *
 * ## What this verb is
 *
 * The primary bounded diagnostic surface. Five typed dimensions:
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
 * the diagnostic surface used to leave: before them a caller could not ask which stage
 * failed, nor how much of a failed step's console output it wanted to read.
 * `--fields` projects to one of the [InspectField] cases; the ADT is closed so adding a
 * sixth field forces every caller to be revisited.
 *
 * ## Whole-run reports are MODES
 *
 * `--state`, `--profile`, `--explain` and `--telemetry` each answer a different question
 * about the whole run, so a query carries at most one of them; `--otlp` is another
 * rendering of the telemetry export and requires `--telemetry`.
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
 * ## What it reads
 *
 * The verb parses and validates the request, then reads the durable history: the event store
 * (`--db`) and the Output Plane (`--control-root`). Each event is resolved to the operation
 * it belongs to BEFORE any Output Plane read, because the console tail belongs to the
 * operation and not to the run. The whole-run reports read the event plane only, so they do
 * not demand a control root.
 *
 * ## The division of labour: this verb coordinates, it does not do the work
 *
 * The verb used to hold all three responsibilities in one object: it read the durable lanes,
 * it decided which rows the document shows, and it wrote the document. It is now a
 * coordinator that owns the request's SHAPE (the parse, the modes, the exit codes) and
 * delegates the work to three owners:
 *
 * | Responsibility | Owner | What it decides |
 * |---|---|---|
 * | Reading the facts | [InspectionReader] | whether the durable lanes may be opened, and what they answered |
 * | Selecting the rows | [InspectionSelection] | which operation each event belongs to, and which rows the query keeps |
 * | Presenting them | [InspectionPresentation] | the exact bytes the operator reads |
 *
 * The split is by REACH, not by file: the selection is pure and can be pinned by a unit
 * test; the reads touch the filesystem and cannot; the presentation is the surface a consumer
 * of the CLI actually sees. Nothing here decides which rows are shown — that decision has
 * exactly one owner, [InspectionSelection], and it was MOVED there rather than copied, so
 * there is no second place free to disagree with it.
 *
 * Exit codes follow the canonical contract:
 *   0 — the document (or the whole-run report) was emitted;
 *   2 — usage error or refused read (missing run id, unknown option, refused value,
 *       unreadable event store, or an `--log-tail-op` that is not an operation of the run).
*/
object MainInspectCli {

    const val USAGE: String =
        "Usage: pipelinek inspect <runId> [--control-root <dir>] [--db <path>] " +
            "[--failed] [--kind K] [--stage S] [--step N] " +
            "[--context N] [--log-tail N] " +
            "[--fields STAGE,STEP,OUTCOME,MESSAGE,CONSOLETAIL] " +
            "[--format text|json] " +
            "[--state] [--profile] [--explain] [--telemetry [--otlp]]"

    /**
     * The five dimensional columns a caller can request from the inspection document.
     * Closed ADT (distinct from `EventField`):
     *
     *   * `Stage`    — the stage name the record was inspected for (--stage).
     *   * `Step`     — the step ordinal within the stage (--step).
     *   * `Outcome`  — the outcome value the record carried (success / failure / unstable / aborted).
     *   * `Message`  — the typed failure message, when the record has one.
     *   * `ConsoleTail` - the last `logTail` bytes of the Output Plane for THIS row's operation.
     *     Resolved per operation, not once for the run: see
     *     [InspectionSelection.operationIdPerEvent] for the measurement that made the run-scoped
     *     value wrong.
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
            override fun extract(record: InspectionRecord): String = when (val tail = record.consoleTail) {
                is InspectConsoleTail.Bytes -> tail.text
                // Nothing was asked for, so there is nothing to say. Same empty projection the
                // un-requested `stage` and `message` fields produce — and, unlike Unreadable, it
                // is not a claim about the output.
                InspectConsoleTail.NotRequested -> ""
                // A refusal is "the bytes are NOT absent, they are unread". Rendering it as ""
                // would tell the consumer the step printed nothing, which is a different fact and
                // the one a reader acts on. The marker names the refusal so an unreadable tail is
                // visible instead of silent.
                is InspectConsoleTail.Unreadable -> "<unreadable: ${tail.reason}>"
            }
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
     * The parser is the contract: it turns the arguments into a typed
     * [CompiledInspectionQuery], and every refusal it raises is a [CliError] rendered by the
     * shared mapping. The query then drives the read — the event store for the run's events,
     * the Output Plane for the console tail each event's operation printed, and the filters
     * and the context window over the records that survive them.
    */
    fun main(args: Array<String>): Int {
        // The parser is the only place that knows which options carry a value, so it is the
        // only place that can tell the run id from an option's value. The verb reads the run
        // id from the parsed query instead of from `args[0]`, and the request is therefore
        // the same one whether the operator wrote the run id before the options or after.
        val compiled = when (val r = parseInspect(args)) {
            is InspectParseResult.Parsed -> r.value
            is InspectParseResult.Rejected -> {
                val code = refuse(r.error, "inspect")
                System.err.println(USAGE)
                return code
            }
        }

        val runId = compiled.runId
        if (runId == null) {
            val code = refuse(
                CliError.MissingRequiredArgument("run id", "name the run to inspect"),
                "inspect",
            )
            System.err.println(USAGE)
            return code
        }

        // Durable read seam.
        if (compiled.dbPath == null) {
            return refuse(
                CliError.MissingRequiredArgument(
                    "--db <path>",
                    "to read the durable event store",
                ),
                "inspect",
            )
        }
        // The cost profile is a MODE, like --state, and answers a different
        // question than the record table: where did this run's time go. It
        // reads only the event plane, so it does not demand a control root.
        if (compiled.profile) {
            return reportRunProfile(runId, compiled)
        }
        // The diagnosis is a MODE too: what did this run come to, with each
        // claim tied to the record that supports it. Also event-plane only.
        if (compiled.explain) {
            return reportRunExplain(runId, compiled)
        }
        // The telemetry export is a MODE as well: the run's facts as
        // redacted, versioned signals, one JSON object per line for a
        // collector or a file. Nothing about the run changes with or without it.
        if (compiled.telemetry) {
            return reportRunTelemetry(runId, compiled)
        }
        if (compiled.controlRoot == null) {
            return refuse(
                CliError.MissingRequiredArgument(
                    "--control-root <path>",
                    "to read the Output Plane",
                ),
                "inspect",
            )
        }

        // The run's state is a MODE, not a filter.
        //
        // The filters above select RECORDS; a record is not a run. Asking for
        // the state of a filtered view of a run has no honest answer, so the
        // two are not combined: `--state` answers about the run and prints no
        // records. The answer comes from the published introspection port,
        // whose terminal fact is the event lane's `RunFinished` bookend.
        if (compiled.state) {
            return reportRunState(runId, compiled)
        }

        // The admission is the ONE decision on whether this store may be opened. It used to
        // be re-asked here, and this catch only knew about IOException, so a `--db` pointing at a
        // missing directory escaped as a raw java.sql.SQLException with a fourteen-frame stack
        // trace and exit 1 — the code this CLI reserves for a run that executed and failed.
        val dbPath = compiled.dbPath
        val controlRoot = compiled.controlRoot
        val events = readRunEvents(runId, dbPath, wholeRunReport = false) ?: return CliExit.INVALID_ARGUMENT.code

        // Resolve each event to its operation BEFORE any Output Plane read, because the
        // tail belongs to the operation and not to the run.
        val opIds: List<OpId?> = InspectionSelection.operationIdPerEvent(events, runId)

        if (compiled.logTail > 0 && compiled.opId != null &&
            opIds.none { it?.format() == compiled.opId }
        ) {
            // Fail closed rather than render an empty transcript for every row. Without this an
            // operator who mistypes an opId cannot tell "no such operation" from "this step
            // printed nothing", which is the one distinction the field exists to make.
            val owned = opIds.filterNotNull().map { it.format() }.distinct()
            System.err.println(
                "inspect: --log-tail-op ${compiled.opId} is not an operation of run $runId. " +
                    if (owned.isEmpty()) {
                        "This run declares no step operations, so it has nothing to attach a tail to."
                    } else {
                        "This run's step operations: ${owned.joinToString(", ")}"
                    },
            )
            return CliExit.INVALID_ARGUMENT.code
        }

        val consoleTails: Map<String, InspectConsoleTail> = InspectionReader.readConsoleTails(
            opIds = opIds,
            runId = runId,
            controlRoot = controlRoot,
            tailBytes = compiled.logTail.toLong(),
            scopeOpId = compiled.opId,
        )

        val records: List<InspectionRecord> = events.mapIndexed { index, event ->
            InspectionSelection.toRecord(event, opIds[index], consoleTails)
        }

        val filtered = InspectionSelection.applyFilters(records, compiled)
        val withContext = if (compiled.context > 0) {
            InspectionSelection.applyContextWindow(records, filtered, compiled)
        } else {
            filtered
        }

        InspectionPresentation.emitDocument(System.out, runId, compiled, withContext)
        return CliExit.SUCCESS.code
    }

    /**
     * The ONE read of a run's events, with the two policies the verb has over it.
     *
     * The read itself belongs to [InspectionReader]; what this adds is the verb's exit-code
     * contract over its answer, and it is written ONCE so the record table and the three
     * whole-run reports cannot drift apart on it:
     *
     *  - a refused lane is a refusal: the sentence goes to stderr and the verb exits 2;
     *  - an EMPTY run is a refusal only for a whole-run report (`--profile`, `--explain`,
     *    `--telemetry`): those have nothing to say about a run that published no events, and
     *    saying so is the honest answer. For the record table an empty run is an empty
     *    document, which is what the operator asked for.
     *
     * Null means the verb must exit 2; the reason is already on stderr.
    */
    private fun readRunEvents(
        runId: String,
        dbPath: String,
        wholeRunReport: Boolean,
    ): List<DomainEvent>? = foldRunEvents(runId, dbPath, wholeRunReport) { it.toList() }

    private fun <T> foldRunEvents(
        runId: String,
        dbPath: String,
        wholeRunReport: Boolean,
        fold: (Sequence<DomainEvent>) -> T,
    ): T? {
        return when (val read = InspectionReader.foldRunEvents(runId, dbPath, wholeRunReport, fold)) {
            is InspectionEventRead.Refused -> {
                System.err.println(read.reason)
                null
            }
            is InspectionEventRead.Admitted -> read.value
        }
    }

    /**
     * Parse the option args (run id consumed upstream) into a [CompiledInspectionQuery].
     *
     * Delegates to [CliInspectParser.parse]; the body that used to live here
     * moved into [CliInspectParser] as part of the four-verb parser unification. This signature stays so the
     * existing test surface (`MainInspectCli.parseInspect`) keeps compiling without changes.
     * `MainInspectCli.parseInspect` is the test seam; `CliInspectParser.parse` is the production
     * entry point. Both return the same typed [InspectParseResult].
    */
    @Suppress("CyclomaticComplexMethod")
    fun parseInspect(args: Array<String>): InspectParseResult = CliInspectParser.parse(args)

    /**
     * Report the RUN's lifecycle state, read through the published
     * introspection port.
     *
     * ## What this answers
     *
     * Whether the run is still going, and — if it is over — how it ended and
     * when. The answer comes from the run's own terminal fact: the event
     * lane's `RunFinished` bookend. A terminal OPERATION row does not end a
     * run, and this surface says so.
     *
     * ## The line it prints
     *
     * One line, one shape, so a caller can read it without parsing prose:
     *
     * ```
     * run-state running
     * run-state terminal outcome=succeeded at=1792575000000
     * run-state empty
     * ```
     *
     * `empty` is not a refusal: it says the substrate was read and holds no
     * live evidence of this run. A refusal is a refusal, it goes to stderr,
     * and it exits 2.
     */
    private fun reportRunState(runId: String, query: CompiledInspectionQuery): Int {
        val dbPath = query.dbPath
        val controlRoot = query.controlRoot
        if (dbPath == null || controlRoot == null) {
            // The caller checked both before reaching this point. Kept as a
            // refusal rather than a crash: a verb that cannot name its own
            // missing input is a verb that fails with a stack trace.
            System.err.println("inspect: --db and --control-root are required to read the run's state")
            return CliExit.INVALID_ARGUMENT.code
        }

        val admission = admitEventStore(dbPath, EventStorePurpose.Read)
        if (admission is EventStoreAdmission.Refused) {
            System.err.println(admission.refusal.render())
            return CliExit.INVALID_ARGUMENT.code
        }
        val store = (admission as EventStoreAdmission.Admitted).store

        val result = try {
            store.use { eventStore ->
                composeRuntimeIntrospectionPort(
                    eventStore = eventStore,
                    controlRoot = Paths.get(controlRoot),
                    clock = SystemClock(),
                ).inspect(runId)
            }
        } catch (failure: Exception) {
            System.err.println("inspect: ${failure.shortDiagnostic()}")
            return CliExit.INVALID_ARGUMENT.code
        }

        return when (result) {
            is RuntimeIntrospectionResult.Observation -> {
                println(InspectionPresentation.renderRunState(result.observation))
                CliExit.SUCCESS.code
            }

            is RuntimeIntrospectionResult.Refused -> {
                System.err.println(
                    "inspect: ${InspectionPresentation.renderIntrospectionRefusal(result.refusal, runId)}",
                )
                CliExit.INVALID_ARGUMENT.code
            }
        }
    }

    /**
     * Report the RUN's cost profile: where its time went, measured from the
     * timestamps the run's own events already recorded. The report names what
     * was measured, what is only bounded, and what carries no evidence — a
     * run that failed or never finished yields a partial profile, never a
     * success-shaped silence.
     */
    private fun reportRunProfile(runId: String, query: CompiledInspectionQuery): Int {
        val dbPath = query.dbPath
            ?: return refuse(
                CliError.MissingRequiredArgument("--db <path>", "to profile the run"),
                "inspect",
            )

        // Read-only admission: a missing store is refused here and no file is
        // created, so profiling a mistyped run id cannot fabricate a database.
        val costInput = foldRunEvents(runId, dbPath, wholeRunReport = true, RunCostProjection::project) ?: return CliExit.INVALID_ARGUMENT.code

        val outcome = summarize(costInput)
        val lines = when (query.format) {
            MainInspectCli.InspectFormat.JSON -> RunCostReport.jsonLines(runId, outcome)
            MainInspectCli.InspectFormat.TEXT -> RunCostReport.textLines(runId, outcome)
        }
        lines.forEach(::println)
        return CliExit.SUCCESS.code
    }

    /**
     * Report the RUN's diagnosis: what it came to, which step broke first,
     * what is still open — each finding labeled as observed fact or inference
     * and carrying the event that grounds it. A run whose terminal event is
     * absent is explained as open; it is never explained into a success.
     */
    private fun reportRunExplain(runId: String, query: CompiledInspectionQuery): Int {
        val dbPath = query.dbPath
            ?: return refuse(
                CliError.MissingRequiredArgument("--db <path>", "to explain the run"),
                "inspect",
            )

        // Read-only admission: a missing store is refused and no file is
        // created, so explaining a mistyped run id cannot fabricate a database.
        val diagnosis = foldRunEvents(runId, dbPath, wholeRunReport = true, RunExplainProjection::project) ?: return CliExit.INVALID_ARGUMENT.code
        val lines = when (query.format) {
            MainInspectCli.InspectFormat.JSON -> RunExplainReport.jsonLines(runId, diagnosis)
            MainInspectCli.InspectFormat.TEXT -> RunExplainReport.textLines(runId, diagnosis)
        }
        lines.forEach(::println)
        return CliExit.SUCCESS.code
    }

    /**
     * Report the RUN's facts as telemetry signals: spans for the run, its
     * compilation and its steps, plus duration histograms and outcome
     * counters, all reduced to what the redaction policy allows. The output
     * is line-delimited JSON a collector can tail; reading it is optional
     * and the run that produced it neither knows nor cares.
     *
     * With `--otlp` the same export is rendered in the OpenTelemetry shape
     * instead. It is the same export, not another one — the policy ran
     * once, and the shape is the only thing that changed.
     *
     * The OTLP form is TWO documents, not one: a collector reads spans in a
     * trace request and metrics in a metrics request, and a document that
     * carries both is a document no collector accepts. The export stays
     * line-delimited, so the consumer still tails it: one request per line.
     */
    private fun reportRunTelemetry(runId: String, query: CompiledInspectionQuery): Int {
        val dbPath = query.dbPath
            ?: return refuse(
                CliError.MissingRequiredArgument("--db <path>", "to export the run's telemetry"),
                "inspect",
            )

        val trace = foldRunEvents(runId, dbPath, wholeRunReport = true) { events ->
            RunTelemetryProjection.fromEvents(runId, events)
        } ?: return CliExit.INVALID_ARGUMENT.code
        if (query.otlp) {
            val out = System.out
            RunTelemetryOtlp.writeSpans(trace, out)
            RunTelemetryOtlp.writeMetrics(trace, out)
            out.flush()
            return CliExit.SUCCESS.code
        }
        RunTelemetryReport.forEachJsonLine(trace, ::println)
        return CliExit.SUCCESS.code
    }

    /**
     * A short, one-line diagnostic for a failure that must not become a
     * stack trace on the operator's terminal.
     */
    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(160)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
