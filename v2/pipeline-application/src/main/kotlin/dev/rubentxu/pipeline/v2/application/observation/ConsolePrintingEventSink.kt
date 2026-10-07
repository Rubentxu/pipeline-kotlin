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
 */
class ConsolePrintingEventSink(
    private val delegate: EventSink,
    private val view: ObservationView,
    private val emit: (String) -> Unit,
) : EventSink {

    private val stream = HumanConsoleRenderer.stream(view)

    override fun append(event: DomainEvent) {
        // Render BEFORE delegating so a rendering fault cannot swallow the event
        // on its way to the durable store.
        val line = stream.accept(event)
        delegate.append(event)
        if (line != null) emit(line)
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> = delegate.eventsFor(runId)
}