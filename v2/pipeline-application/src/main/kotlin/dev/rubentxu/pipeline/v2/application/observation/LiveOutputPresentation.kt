package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputRefusal
import java.io.PrintStream
import java.io.Writer

/**
 * OBS-E4: what `pipeline run` does with the bytes its own steps produced.
 *
 * ```text
 * EventSink   -> ConsolePrintingEventSink -> human lines   (the event spine)
 * Output Plane -> LiveOutputDrain        -> THIS          (the byte spine)
 * ```
 *
 * ## Why these are two sinks and not one
 *
 * Process output is not on the event spine and cannot be put on it. `DomainEvent` carries facts,
 * and a transcript is not a fact — `pipelinek-fabric` already forbids the two travelling together
 * in its own code. So the run keeps its event sink exactly as it was, and this type is the second,
 * separate reader of the second authority. Two sources, two sinks, no merge key.
 *
 * ## The bytes go out raw, and that is deliberate
 *
 * A human console prints the child's bytes verbatim: no `[PipelineK]` prefix, no re-indentation, no
 * per-line decoration. Prefixing would break ANSI colour, progress bars, partial lines, stack traces
 * and copy-paste, which are the reasons a person watches a build at all. PipelineK's own messages
 * are blocks, and they are already emitted by the event sink; the two never need to be told apart
 * because they look nothing alike.
 *
 * The machine format is the opposite, and gets the opposite treatment: one JSON object per record,
 * one line, flushed. `jsonl` reserves stdout for the protocol, so a line is never half-written when
 * a consumer reads it.
 *
 * ## It is a projection, and it is read-side
 *
 * Nothing here filters what was persisted. A query suppresses what is SHOWN, after the fact, from
 * bytes that were already committed — never what was stored. Two consumers can therefore ask for
 * two different views of the same run, minutes apart, and both are answered from the same plane.
 *
 * ## Failure is loud, and it is loud on the right stream
 *
 * A refusal cannot be reported on stdout in `jsonl` mode without corrupting the protocol, so it goes
 * to [diagnostics]. That asymmetry is deliberate: the protocol stream stays parseable, and the
 * person still finds out that the console is incomplete. Reporting it as a clean finish would show a
 * transcript with a silent hole and call it complete.
 */
class LiveOutputPresentation(
    private val format: ObservationFormat,
    private val out: PrintStream,
    private val diagnostics: PrintStream,
) {

    /**
     * Turns one committed record into what this format owes the consumer.
     *
     * Returns the text to write, or `null` when this record contributes nothing — which for `json`
     * is every record, because a JSON array is a document and a document cannot be streamed.
     */
    fun render(record: ObservationRecord.Output): String? = when (format) {
        ObservationFormat.TEXT -> record.text
        ObservationFormat.JSON_LINES -> ObservationJsonLines.encodeOne(record) + "\n"
        // `json` is a post-run document: an array is only complete when its `]` arrives, so a live
        // record has no honest place in it. Emitting one anyway would produce a stream that parses
        // as neither an array nor a sequence of records.
        ObservationFormat.JSON -> null
    }

    /**
     * Writes one record.
     *
     * The flush is per record and is not an optimisation. Without it the bytes sit in a buffer and a
     * consumer watching a build sees nothing until the buffer fills — which is the defect
     * `ObsAOutputStreamingCharacterisationTest` characterised as "a late document".
     */
    fun emit(record: ObservationRecord.Output) {
        val text = render(record) ?: return
        out.print(text)
        out.flush()
    }

    /** Reports that the console could not be completed. Never on the protocol stream. */
    fun reportRefusal(reason: OutputRefusal) {
        // The refusal is rendered, not classified: this is a diagnostic for a person, and the
        // store's own taxonomy already says what each case means.
        diagnostics.println("[PipelineK] output plane refused to answer: $reason")
        diagnostics.flush()
    }

    /**
     * The end-of-run document for the formats that owe one.
     *
     * Only `json` uses it, and only for a run whose output the live path deliberately did not
     * stream. `jsonl` is already complete per record, and `text` was already printed as it arrived.
     */
    fun documentFor(records: Iterable<ObservationRecord.Output>, writer: Writer) {
        if (format != ObservationFormat.JSON) return
        writer.write("[")
        var first = true
        for (record in records) {
            if (!first) writer.write(",")
            first = false
            writer.write(ObservationJsonLines.encodeOne(record).trim())
        }
        writer.write("]")
    }
}
