package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink

/**
 * An [EventSink] that mirrors the run to the console AS IT HAPPENS.
 *
 * ## Why a decorator and not a poller
 *
 * `pipeline run` used to collect the whole run and print it once, at the end,
 * as a JSON array. Reading the store while it is being written would be a second
 * reader racing the writer for the same rows. A decorator on the sink chain is
 * the same object the engine already calls, so live output costs no extra
 * authority and cannot observe a half-committed page.
 *
 * ## Where it MUST sit
 *
 * Above [dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink], never
 * below it. Every event reaching this sink has already been redacted, which is
 * what makes printing it safe; a console observer below redaction would put
 * credentials on the terminal. This mirrors `CLI_OBSERVABILITY_SPEC.md` §"redact
 * before durable persistence or public presentation" — and, like it, states the
 * limit: **this class is not a security boundary**. Redaction happens upstream;
 * this only presents what it is given.
 *
 * ## Scope of "live"
 *
 * This streams the EVENT spine: stage and step lifecycle, `echo` messages,
 * failures, directives. It does NOT stream process stdout/stderr. The transcript
 * reaches the Output Plane after the step finishes, by design — `cleanup`
 * deliberately preserves `console.log` so the post-step consumer does not race
 * it (`DurableShellExecutor.cleanup`). Live process output is therefore a
 * separate, later change, not something this class pretends to deliver.
 *
 * ## Interaction with the machine formats
 *
 * Installed only when the format is [ObservationFormat.TEXT]. A JSON consumer
 * requires stdout to carry nothing but its document, so a human line here would
 * corrupt it. That is the same separation ADR-0088 draws between `view` and
 * `format`.
 *
 * ## Selection is presentation, never storage
 *
 * [query] is a READ filter, and the trap this class exists to avoid is treating it as a write
 * one. Every event is delegated to [delegate] unconditionally; the query only decides whether the
 * rendered line reaches the terminal. A `--grep` that also dropped events from the store would
 * make the run unreplayable and would quietly turn a display preference into a durability
 * decision — the CLI would be destroying the record of what happened.
 *
 * It exists at all because this is the DEFAULT path: with `--format text` the end-of-run dump is
 * suppressed (see `Main.kt`), so a filter applied only there would never be applied to anything.
 * `--grep`, `--stage`, `--step` and `--kind` were therefore silently dead parameters on the very
 * path they are advertised on. That is the "dead semantic parameter" the Semantic Constitution §2
 * forbids: the CLI accepts a flag that changes no observable output.
 */
class ConsolePrintingEventSink(
    private val delegate: EventSink,
    private val view: ObservationView,
    private val query: CompiledObservationQuery,
    private val emit: (String) -> Unit,
) : EventSink {

    private val stream = HumanConsoleRenderer.stream(view)

    override fun append(event: DomainEvent) {
        // Fold the event into the stream UNCONDITIONALLY, then decide whether to print it. The
        // stream carries the stage-name scope that later lines resolve against, so skipping the
        // fold for a filtered-out `StageStarted` would degrade the rendering of events that ARE
        // selected — the filter would leak into the presentation of the survivors.
        //
        // Render BEFORE delegating so a rendering fault cannot swallow the event on its way to
        // the durable store.
        val line = stream.accept(event)
        delegate.append(event)
        if (line != null && query.accepts(ObservationRecord.Event(event))) emit(line)
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> = delegate.eventsFor(runId)
}