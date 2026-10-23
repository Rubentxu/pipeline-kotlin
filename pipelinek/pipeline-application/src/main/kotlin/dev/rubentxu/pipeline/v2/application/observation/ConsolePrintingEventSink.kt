package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink

/**
 * An [EventSink] that mirrors the run to the console AS IT HAPPENS.
 * ## Why a decorator and not a poller
 * `pipeline run` used to collect the whole run and print it once, at the end,
 * as a JSON array. Reading the store while it is being written would be a second
 * reader racing the writer for the same rows. A decorator on the sink chain is
 * the same object the engine already calls, so live output costs no extra
 * authority and cannot observe a half-committed page.
 * ## Where it MUST sit
 * BELOW [dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink], never above
 * it. Events travel DOWN the chain, so the redaction has to come first: this sink
 * is handed events that have already been scrubbed, and that is what makes
 * printing them safe. A console above the redaction would render the event
 * before it has been scrubbed, and that is how a registered secret reaches the
 * terminal: the run refuses to keep it and prints it anyway. That is the
 * "redact before durable persistence or public presentation" rule — and stating
 * it here also states the limit: **this class is not a security boundary**.
 * Redaction happens upstream; this only presents what it is given.
 * ## The record comes before the drawing
 * Drawing is a presentation of a fact, and a presentation is allowed to fail: the renderer
 * stops on purpose when the routing table and a family handler disagree, because a console
 * that draws a plausible wrong line is worse than a console that stops. What must NOT happen
 * is the console taking the fact with it. So the durable write happens FIRST and the drawing
 * happens after: a rendering fault leaves the run's record intact and the failure visible,
 * instead of deleting the event from the store and leaving a run that looks like it never
 * happened. The drawing is a collaborator, so this order is a property of the class and not
 * of the day the drawing happened to be written.
 * ## Scope of "live"
 * This streams the EVENT spine: stage and step lifecycle, `echo` messages,
 * failures, directives. It does NOT stream process stdout/stderr — the bytes a
 * process writes are not events, they live in the Output Plane, and they reach
 * the terminal through the observer that follows that plane while the run is in
 * flight. The transcript of a step reaches the plane after the step finishes, by
 * design — `cleanup` deliberately preserves `console.log` so the post-step
 * consumer does not race it (`DurableShellExecutor.cleanup`).
 * ## Interaction with the machine formats
 * Installed only when the format is [ObservationFormat.TEXT]. A JSON consumer
 * requires stdout to carry nothing but its document, so a human line here would
 * Corrupt it. That is the same separation that draws between `view` and
 * `format`.
 * ## Selection is presentation, never storage
 * [query] is a READ filter, and the trap this class exists to avoid is treating it as a write
 * one. Every event is delegated to [delegate] unconditionally; the query only decides whether the
 * rendered line reaches the terminal. A `--grep` that also dropped events from the store would
 * make the run unreplayable and would quietly turn a display preference into a durability
 * decision — the CLI would be destroying the record of what happened.
 * It exists at all because this is the DEFAULT path: with `--format text` the end-of-run dump is
 * suppressed, so a filter applied only there would never be applied to anything.
 * `--grep`, `--stage`, `--step` and `--kind` were therefore silently dead parameters on the very
 * path they are advertised on. That is the "dead semantic parameter" the Semantic Constitution
 * forbids: the CLI accepts a flag that changes no observable output.
*/
class ConsolePrintingEventSink(
    private val delegate: EventSink,
    private val view: ObservationView,
    private val query: CompiledObservationQuery,
    private val emit: (String) -> Unit,
    private val render: (DomainEvent) -> String? = HumanConsoleRenderer.stream(view)::accept,
) : EventSink {

    override fun append(event: DomainEvent) {
        // Fold the event into the stream UNCONDITIONALLY, then decide whether to print it. The
        // stream carries the stage-name scope that later lines resolve against, so skipping the
        // fold for a filtered-out `StageStarted` would degrade the rendering of events that ARE
        // selected — the filter would leak into the presentation of the survivors.
        // The durable fact goes FIRST. The drawing is a presentation of that fact, and a
        // presentation that fails must not take the fact with it: the renderer is allowed to
        // stop — a console that draws a plausible wrong line is worse than a console that
        // stops — and when it stops, the run's record of what happened stays intact.
        delegate.append(event)
        val line = render(event)
        if (line != null && query.accepts(ObservationRecord.Event(event))) emit(line)
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> = delegate.eventsFor(runId)
}
