package dev.rubentxu.pipeline.v2.events.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * WU-LPR-050 — Read-side projection of an event history query.
 *
 * ## CORRECTION (M1-P3). This KDoc was false and said so at length.
 *
 * It claimed that [MainEventsCli][dev.rubentxu.pipeline.v2.application.MainEventsCli]
 * "accepts `--view <mode>` and `--format <fmt>` and delegates the projection" here. It accepts
 * neither flag: its parser is `--db / --runId / --kind / --subject / --limit / --after-cursor` and
 * nothing else. **This projection has no production caller at all** — only its own test reaches
 * it. That was found by RCE's promotion of the OUT-B item and confirmed here against the current
 * HEAD.
 *
 * The claim was not harmless documentation drift. It described an integration that a reader would
 * reasonably go looking for, and the way to find it was `--view console`.
 *
 * ## Why the `console` mode is deprecated rather than merely documented
 *
 * It filters envelopes by `EchoOutputCaptured`. Since M1-P2 that event no longer carries **process**
 * output — it carries the semantic `core.echo` event only. So a "console" view that selects on it
 * can only ever return a subset of console, which is the dangerous kind of wrong: it looks like it
 * works, and it silently drops whatever a process printed. Filtering events to reconstruct a
 * console is also exactly the event-sequence-for-output-cursor conflation that `ADR-M1 D3` forbids.
 *
 * The real console reader is [dev.rubentxu.pipeline.v2.application.ConsoleReadService], which
 * addresses the Output Plane by committed byte offset.
 *
 * ## Modes
 *
 *  - `events` — every envelope, full payload, one per line in the chosen
 *    format. Equivalent to the historical "show me everything" output.
 *  - `normal` — lifecycle-only envelope set: RunStarted, StageStarted,
 *    StageFinished, RunFinished, StepStarted, StepFinished. Drops
 *    EchoOutputCaptured / UnixDetected / PwdResolved / ParallelBranchStarted
 *    / ParallelBranchFinished / etc. The agent-friendly default.
 *  - `full` — alias for `events` (every envelope). Reserved for explicit
 *    parity with the `--view full` CLI surface.
 *  - `console` — DEPRECATED. Selects `EchoOutputCaptured` envelopes, which since M1-P2 carry the
 *    semantic `core.echo` event and NOT process output. It cannot show a process console. Use
 *    [dev.rubentxu.pipeline.v2.application.ConsoleReadService].
 *  - `quiet` — only `RunFinished` envelopes (the aggregate outcome).
 *
 * ## Formats
 *
 *  - `jsonl` — one JSON envelope per line (the canonical wire format).
 *  - `json` — a single JSON array containing every envelope.
 *  - `text` — a one-line-per-envelope human-readable summary
 *    (`<sequence> <kind> <key=value pairs...>`).
 *
 * Pure, total, no effects. The CLI is the effectful boundary that
 * materializes the projection lines onto stdout.
 */
object EventViewProjection {

    /**
     * Pure projection.
     *
     * @param envelopes The query result, in `sequence` order.
     * @param mode The view mode; defaults to `normal`.
     * @param format The output format; defaults to `jsonl`.
     * @return One string per envelope, ready to be emitted on stdout.
     */
    fun project(
        envelopes: List<PipelineEventEnvelope>,
        mode: ViewMode = ViewMode.NORMAL,
        format: OutputFormat = OutputFormat.JSONL,
    ): List<String> {
        val filtered = filter(envelopes, mode)
        return when (format) {
            OutputFormat.JSONL -> filtered.map { EnvelopeCodec.encode(it) }
            OutputFormat.JSON -> encodeArray(filtered)
            OutputFormat.TEXT -> filtered.map(::renderText)
        }
    }

    private fun filter(envelopes: List<PipelineEventEnvelope>, mode: ViewMode): List<PipelineEventEnvelope> =
        when (mode) {
            ViewMode.EVENTS -> envelopes
            ViewMode.FULL -> envelopes
            ViewMode.NORMAL -> envelopes.filter { it.kind in LIFECYCLE_KINDS }
            ViewMode.CONSOLE -> envelopes.filter { it.kind == "EchoOutputCaptured" }
            ViewMode.QUIET -> envelopes.filter { it.kind == "RunFinished" }
        }

    private fun encodeArray(envelopes: List<PipelineEventEnvelope>): List<String> {
        // Single-element list carrying the JSON array as one string the CLI
        // emits as a single line. The CLI does NOT pretty-print; downstream
        // tools that need pretty JSON can re-parse.
        val joined = envelopes.joinToString(separator = ",", prefix = "[", postfix = "]") {
            EnvelopeCodec.encode(it)
        }
        return listOf(joined)
    }

    private fun renderText(envelope: PipelineEventEnvelope): String {
        // Text format: "<sequence> <kind>" — agent-friendly, no payload
        // rendering (the payload types are sealed DomainEvent variants whose
        // text rendering would require per-kind handling; reserve per-kind
        // text rendering for a follow-up WU).
        return "${envelope.sequence} ${envelope.kind}"
    }

    /**
     * Closed set of lifecycle kinds promoted to the `normal` view.
     *
     * Source of truth = the canonical `events` package's event classes
     * (CompilationStarted/Finished, RunStarted, StageStarted/Finished,
     * StepStarted/Finished, RunFinished). EchoOutputCaptured and the
     * UnixDetected/PwdResolved/ParallelBranch* family are NOT lifecycle
     * events and are filtered out of `normal`.
     */
    private val LIFECYCLE_KINDS: Set<String> = setOf(
        "CompilationStarted",
        "CompilationFinished",
        "RunStarted",
        "StageStarted",
        "StageFinished",
        "StepStarted",
        "StepFinished",
        "RunFinished",
    )
}

/**
 * Closed view mode. Adding a case forces [EventViewProjection.filter] to be
 * revisited (compile-time exhaustiveness check).
 */
enum class ViewMode {
    /** Lifecycle-only envelope set (agent-friendly default). */
    NORMAL,
    /** Every envelope. */
    EVENTS,
    /** Alias for [EVENTS]. Reserved for CLI parity. */
    FULL,
    /**
     * DEPRECATED — semantic `core.echo` envelopes only, never a process console.
     *
     * Kept so the closed enum stays source-compatible for a caller that wants echo events, and so
     * the wrong thing is named rather than quietly available. A process console is read through
     * [dev.rubentxu.pipeline.v2.application.ConsoleReadService].
     */
    @Deprecated(
        message = "selects semantic core.echo events, not process output; use ConsoleReadService for a console",
        replaceWith = ReplaceWith("dev.rubentxu.pipeline.v2.application.ConsoleReadService.read(...)"),
    )
    CONSOLE,
    /** Only `RunFinished` envelopes (the aggregate outcome). */
    QUIET,
}

/**
 * Closed output format. Adding a case forces [EventViewProjection.project]
 * to be revisited (compile-time exhaustiveness check).
 */
enum class OutputFormat {
    /** One JSON envelope per line (canonical wire format). */
    JSONL,
    /** Single JSON array containing every envelope. */
    JSON,
    /** One-line-per-envelope human-readable summary. */
    TEXT,
}
