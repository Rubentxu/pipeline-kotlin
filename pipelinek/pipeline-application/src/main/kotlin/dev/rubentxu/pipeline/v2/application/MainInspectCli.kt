package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.output.store.DiagnosticText
import dev.rubentxu.pipeline.v2.output.StreamingUtf8Decoder
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore

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
     *   * `ConsoleTail` — the last `logTail` bytes of the Output Plane for THIS row's operation.
     *     Resolved per operation, not once for the run: see [operationIdPerEvent] for the
     *     measurement that made the run-scoped value wrong.
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

        // R4 / WU-R4.1.2 — durable read seam.
        if (compiled.dbPath == null) {
            System.err.println("inspect: --db <path> is required to read the durable event store")
            return 2
        }
        if (compiled.controlRoot == null) {
            System.err.println("inspect: --control-root <path> is required to read the Output Plane")
            return 2
        }

        // D3 — the admission is the ONE decision on whether this store may be opened. It used to
        // be re-asked here, and this catch only knew about IOException, so a `--db` pointing at a
        // missing directory escaped as a raw java.sql.SQLException with a fourteen-frame stack
        // trace and exit 1 — the code this CLI reserves for a run that executed and failed.
        val admission = admitEventStore(compiled.dbPath, EventStorePurpose.Read)
        if (admission is EventStoreAdmission.Refused) {
            System.err.println(admission.refusal.render())
            return 2
        }
        val events: List<DomainEvent> = try {
            (admission as EventStoreAdmission.Admitted).store.use { store ->
                store.eventsFor(runId).toList()
            }
        } catch (e: java.io.IOException) {
            System.err.println("inspect: cannot read event store at ${compiled.dbPath}: ${e.message}")
            return 2
        }

        // D2 — resolve each event to its operation BEFORE any Output Plane read, because the
        // tail belongs to the operation and not to the run.
        val opIds: List<OpId?> = operationIdPerEvent(events, runId)

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
            return 2
        }

        val consoleTails: Map<String, InspectConsoleTail> = consoleTailsByOperation(
            opIds = opIds,
            runId = runId,
            controlRoot = compiled.controlRoot,
            tailBytes = compiled.logTail.toLong(),
            scopeOpId = compiled.opId,
        )

        val records: List<InspectionRecord> = events.mapIndexed { index, event ->
            toInspectionRecord(event, opIds[index], consoleTails)
        }

        val filtered = applyFilters(records, compiled)
        val withContext = if (compiled.context > 0) {
            applyContextWindow(records, filtered, compiled)
        } else {
            filtered
        }

        emitDocument(System.out, runId, compiled, withContext)
        return 0
    }

    /**
     * Resolve each event to the operation whose console tail it may report, in event order.
     *
     * ## The defect this removes
     *
     * `--log-tail` used to be answered ONCE for the whole run and the same value stamped onto
     * every row. Measured 2026-10-18 against distribution 0.62.0, a two-stage run whose first
     * stage printed `DESPUES` and whose second stage exited 7:
     *
     * ```text
     * STAGE          OUTCOME   CONSOLETAIL
     *  build/sh-0    StepStarted  DESPUES
     *  verify/sh-0   failure     DESPUES     <- a FAILURE carrying the other step's transcript
     * ```
     *
     * Thirteen rows, thirteen copies, and the failure's row quoted a step that SUCCEEDED. Same
     * defect class as the `ok` verdict, in the opposite direction: a projection rendering a fact
     * its event does not carry. [InspectField.ConsoleTail] had always promised "the Output Plane
     * for THE FAILED STEP'S OP"; this function is what makes the promise true.
     *
     * ## Why a fold, and what it cannot do
     *
     * `StepFailed` carries `stepIndex` but NOT `stageIndex`, and an [OpId] needs both. The fold
     * therefore keeps the op of the most recent [StepStarted] — a step runs inside its stage, so
     * the open step's op is the right one — while every event that carries its own indices
     * prefers them.
     *
     * Both stage boundaries CLEAR the open op: a stage-level row belongs to a stage, and a stage
     * is not an operation. `StageFinished` was measured inheriting the last step's op and printing
     * that step's transcript on the row that closes its stage — the same defect as the failure row,
     * one level up, and found by reading the rendered document rather than by a test.
     * `StageSkipped` needs no case: a skipped stage never opened a step, so `open` is already null.
     *
     * What the fold cannot decide: a `StepFailed` from a PARALLEL branch that reports after a
     * sibling started. The event carries nothing to resolve it with, so it inherits the sibling.
     * Stated rather than hidden, because wrong-but-present attribution is precisely what the
     * measurement above removes.
     *
     * Pure: no clock, no filesystem, no reader. [OpId] is the typed authority for the format —
     * this never re-templates the string.
     */
    internal fun operationIdPerEvent(events: List<DomainEvent>, runId: String): List<OpId?> {
        var open: OpId? = null
        return events.map { event ->
            open = when (event) {
                // A stage boundary closes every operation the stage opened. Both ends, not one:
                // closing only the opening end leaves the stage's own summary row holding the last
                // step's transcript.
                is StageStarted, is StageFinished -> null
                is StepStarted -> OpId(runId, event.stageIndex, event.stepIndex)
                is StepFinished -> OpId(runId, event.stageIndex, event.stepIndex)
                else -> open
            }
            open
        }
    }

    /**
     * Read each operation's tail ONCE and index it by opId.
     *
     * Bounded by the number of DISTINCT operations in the run, not by the number of rows: a
     * 40-step run reads 40 indexed tails and stamps them onto however many rows each owns.
     *
     * [scopeOpId] (`--log-tail-op`) narrows the eligible set to the operation the operator named,
     * so every other row projects silence rather than someone else's transcript. [main] has
     * already validated that a named operation belongs to the run, which is what lets
     * [OutputRefusal.UnknownStream] mean one thing here — "this operation has no output stream" —
     * instead of two.
     */
    private fun consoleTailsByOperation(
        opIds: List<OpId?>,
        runId: String,
        controlRoot: String,
        tailBytes: Long,
        scopeOpId: String?,
    ): Map<String, InspectConsoleTail> {
        if (tailBytes <= 0) return emptyMap()
        val eligible = opIds.filterNotNull().map { it.format() }.toSet()
            .let { if (scopeOpId != null) it.intersect(setOf(scopeOpId)) else it }
        if (eligible.isEmpty()) return emptyMap()
        return try {
            val plane = OutputPlaneProvider.storeForReading(java.nio.file.Paths.get(controlRoot))
            val reader = FrameIndexedObservationOutputReader(plane.frameIndex(), plane, plane)
            eligible.associateWith { opId ->
                // Every case of the read ADT is carried through. `ObservationOutputRead` already
                // separates "the page" from "the store could not answer", and collapsing the
                // second into an empty transcript made a dangling commit indistinguishable from a
                // step that printed nothing.
                when (val r = reader.readTail(runId, opId, tailBytes)) {
                    is ObservationOutputRead.Page ->
                        InspectConsoleTail.Bytes(decodePageBytesAsUtf8Impl(r.page.records))
                    is ObservationOutputRead.Refused -> when (r.reason) {
                        // The op is known to belong to this run (see [scopeOpId]), so a missing
                        // stream is a FACT about it: it printed nothing. `Bytes("")` states that;
                        // `NotRequested` would state a different fact, and `Unreadable` would
                        // claim the bytes are lost when the plane is simply empty.
                        is OutputRefusal.UnknownStream -> InspectConsoleTail.Bytes("")
                        else -> InspectConsoleTail.Unreadable(r.reason)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Previously this became `NotRequested`, which rendered as silence on every row —
            // the same "printed nothing" lie the class above was built to remove. An unreadable
            // plane is unreadable on every row, and each row now says so.
            eligible.associateWith { InspectConsoleTail.Unreadable(OutputRefusal.StorageError(DiagnosticText.from(e))) }
        }
    }

    /**
     * Translate one [DomainEvent] into the row shape that [InspectField] extractors read from.
     * The mapping is intentionally narrow: the five [InspectField] cases are the contract,
     * not the union of every DomainEvent subtype.
     *
     * [opId] is this row's operation, or null when the event belongs to no step. A row with no
     * operation has no transcript, and says nothing rather than borrowing one.
     */
    private fun toInspectionRecord(
        event: DomainEvent,
        opId: OpId?,
        consoleTails: Map<String, InspectConsoleTail>,
    ): InspectionRecord {
        val stepName: String? = (event as? StepStarted)?.stepName
            ?: (event as? StepFinished)?.stepName
            ?: (event as? StepFailed)?.stepName
        val stepOrdinal: Long? = (event as? StepStarted)?.stepIndex?.toLong()
            ?: (event as? StepFinished)?.stepIndex?.toLong()
            ?: (event as? StepFailed)?.stepIndex?.toLong()
        val outcome: String = when (event) {
            is RunFinished -> when (event.outcome) {
                "success" -> "success"
                "unstable" -> "unstable"
                "failure", "aborted" -> "failure"
                else -> event.outcome
            }
            is StepFailed -> "failure"
            else -> event.kind
        }
        val message: String? = (event as? StepFailed)?.message
        return InspectionRecord(
            stage = stepName,
            stepOrdinal = stepOrdinal,
            outcome = outcome,
            message = message,
            consoleTail = opId?.let { consoleTails[it.format()] } ?: InspectConsoleTail.NotRequested,
        )
    }

    private fun applyFilters(
        records: List<InspectionRecord>,
        q: CompiledInspectionQuery,
    ): List<InspectionRecord> {
        if (!q.failed && q.kind == null && q.stage == null && q.step == null) return records
        return records.filter { rec ->
            (q.failed && rec.outcome == "failure") ||
                (q.kind != null && kindOf(rec) == q.kind) ||
                (q.stage != null && rec.stage == q.stage) ||
                (q.step != null && rec.stepOrdinal == q.step)
        }
    }

    private fun kindOf(rec: InspectionRecord): String = rec.outcome

    private fun applyContextWindow(
        all: List<InspectionRecord>,
        matched: List<InspectionRecord>,
        q: CompiledInspectionQuery,
    ): List<InspectionRecord> {
        if (matched.isEmpty() || q.context == 0) return matched
        val selected = mutableSetOf<Int>()
        val matchedIndices = matched.mapNotNull { rec ->
            all.indexOfFirst { it === rec }
        }.toSet()
        matchedIndices.forEach { idx ->
            val from = (idx - q.context).coerceAtLeast(0)
            val to = (idx + q.context).coerceAtMost(all.size - 1)
            for (i in from..to) selected.add(i)
        }
        return selected.sorted().map { all[it] }
    }

    private fun emitDocument(
        out: java.io.PrintStream,
        runId: String,
        q: CompiledInspectionQuery,
        records: List<InspectionRecord>,
    ) {
        val cols = q.fields ?: defaultColumns()
        when (q.format) {
            InspectFormat.TEXT -> {
                out.println("inspect run=$runId")
                out.println("  ${cols.joinToString(separator = " ") { it.cli }}")
                records.forEach { rec ->
                    out.println("  " + cols.joinToString(separator = " ") { it.extract(rec) })
                }
            }
            InspectFormat.JSON -> {
                out.println("{")
                out.println("  \"runId\": \"$runId\",")
                out.println("  \"records\": [")
                records.forEachIndexed { i, rec ->
                    val fields = cols.joinToString(separator = ",") { f ->
                        "\"${f.cli.lowercase()}\": \"${escapeJson(f.extract(rec))}\""
                    }
                    val sep = if (i < records.size - 1) "," else ""
                    out.println("    {$fields}$sep")
                }
                out.println("  ]")
                out.println("}")
            }
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun defaultColumns(): List<InspectField> = listOf(
        InspectField.Stage, InspectField.Step, InspectField.Outcome, InspectField.Message,
    )

    /**
     * Decode a stream of per-frame byte buffers as one continuous UTF-8 transcript.
     *
     * `ObservationOutputReader.decodeWindow` decodes one frame at a time on purpose: the contract
     * is per-frame determinism, and a carry-over buffer would re-decode the same bytes differently
     * depending on where a reader resumed. `inspect --log-tail`, however, presents ONE transcript
     * to a human; a multi-byte codepoint straddling a frame boundary must be reassembled or the
     * tail shows two U+FFFD characters instead of the original character. PC-08 names that defect
     * for the human-facing surface; this helper is the fix.
     *
     * The decoder is the same [StreamingUtf8Decoder] the [StreamingUtf8DecoderTest] exercises; the
     * bytes are not re-decoded character-by-character — the full byte array per frame is fed
     * through the incremental decoder in [r.page.records] order, oldest first, and the final
     * `String` is the assembled transcript.
     */
    private fun decodePageBytesAsUtf8(records: List<dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output>): String =
        decodePageBytesAsUtf8Impl(records)

    /**
     * Internal-visible entry for the byte-stream decode so an HF0 unit test can pin the
     * contract (a multi-byte codepoint straddling two records reassembles into one character)
     * without paying for the heavier MainInspectCli fixture.
     */
    internal fun decodePageBytesAsUtf8Impl(records: List<dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output>): String {
        val decoder = StreamingUtf8Decoder()
        val out = StringBuilder(records.sumOf { it.bytes.size })
        for (r in records) {
            out.append(decoder.decode(r.bytes))
        }
        return out.toString()
    }

    /**
     * Parse the option args (run id consumed upstream) into a [CompiledInspectionQuery].
     *
     * CC-1 / R4 / WU-R4.7 — delegates to [CliParser.parseInspect]; the body that used to live here
     * moved into [CliParser] as part of the four-verb parser unification. This signature stays so the
     * existing test surface (`MainInspectCli.parseInspect`) keeps compiling without changes.
     * `MainInspectCli.parseInspect` is the test seam; `CliParser.parseInspect` is the production
     * entry point. Both return the same typed [InspectParseResult].
     */
    @Suppress("CyclomaticComplexMethod")
    fun parseInspect(args: Array<String>): InspectParseResult = CliParser.parseInspect(args)
}

/** Parsed inspection query. Immutable data class. */
data class CompiledInspectionQuery(
    val failed: Boolean = false,
    val kind: String? = null,
    val stage: String? = null,
    val step: Long? = null,
    val context: Int = 0,
    val logTail: Int = 0,
    /**
     * R4 / WU-R4.1.3 — when set, the `--log-tail` scope is one operation (this opId),
     * not the whole run. The per-op read filters frames whose stream id carries this
     * opId as a middle segment. When null, the tail is the run-wide last-N-bytes path.
     */
    val opId: String? = null,
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
 * What the console tail of an inspected record actually IS.
 *
 * Three states, because the Output Plane's own contract keeps them apart and a nullable `String`
 * did not. [ObservationOutputRead] already distinguishes "the page" from "the store could not
 * answer", and [dev.rubentxu.pipeline.v2.output.OutputRefusal] spells out what a refusal means:
 * the bytes are NOT absent, they are unread. Rendering that as an empty transcript told a consumer
 * that a step printed nothing, which is the one fact it would act on.
 *
 * The empty [Bytes] case is deliberately still a value rather than folded into [NotRequested]: "this
 * step ran and printed nothing" and "nothing was asked for" are different answers to different
 * questions, and only the first one is evidence about the step.
 */
sealed interface InspectConsoleTail {

    /** `--log-tail` was not requested, so there is nothing to report either way. */
    data object NotRequested : InspectConsoleTail

    /** The bytes the Output Plane served, decoded. Empty is a FACT: the step printed nothing. */
    data class Bytes(val text: String) : InspectConsoleTail

    /** A tail was requested and the plane could not answer. Never render this as empty output. */
    data class Unreadable(val reason: OutputRefusal) : InspectConsoleTail
}

/**
 * One inspected record — the row shape that the projection fields read from.
 *
 * Kept as a data class, not a sealed ADT: every record carries all five columns, with `null` for
 * the ones that do not apply, because the consumer chooses which fields to project and a row that
 * has `null` for un-requested fields is not a different SHAPE of record.
 *
 * [consoleTail] is the exception, and the exception is the point: its states are not "which columns
 * this row fills in" but "what the Output Plane answered". A `null` there would have to mean both
 * "un-requested" and "refused", which are different facts about a run, so it is a closed ADT
 * instead. Every other field is still a nullable projection.
 */
data class InspectionRecord(
    val stage: String? = null,
    val stepOrdinal: Long? = null,
    val outcome: String = "",
    val message: String? = null,
    val consoleTail: InspectConsoleTail = InspectConsoleTail.NotRequested,
)
