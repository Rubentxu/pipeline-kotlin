package dev.rubentxu.pipeline.v2.application

/**
 * The label that says WHICH producer published a telemetry document.
 *
 * ## WHY THE LABEL EXISTS
 *
 * This product has two producers of the same shape, and they are not the same
 * thing:
 *
 *  - the **live** producer, which publishes what an execution is doing right
 *    now, through the OpenTelemetry SDK;
 *  - the **historical** producer, which publishes what a finished run did,
 *    read back from the journal, as an OTLP document.
 *
 * A backend that receives both cannot tell them apart by looking: both carry
 * the same service name, the same scope and the same span names. So a
 * collector asked to correlate a run that was watched live with the same run
 * read back afterwards would see the same event twice and could not say
 * whether it saw one execution or two.
 *
 * The label is what makes the two answersable: the same run, published twice,
 * is published under two different labels, and a consumer that wants one of
 * them says which. The label does not deduplicate anything — this product
 * never re-exports a history on its own — it only makes the two producers
 * tellable apart.
 *
 * ## WHY IT IS NOT PART OF THE SIGNAL VOCABULARY
 *
 * The label lives on the **resource**, which describes the producer, and not
 * on the spans, which describe the run. The published identity table governs
 * what a signal may carry, and a key that says who published the document is
 * not a fact about the step that ran: adding it there would widen the
 * allowlist that every signal is measured against, for a fact that belongs to
 * the document as a whole.
 */
internal object TelemetrySource {

    /** The resource attribute that carries the producer's name. */
    const val KEY: String = "telemetry.source"

    /** The producer that publishes a finished run, read back from the journal. */
    const val HISTORICAL: String = "historical"

    /** The producer that publishes an execution while it happens. */
    const val LIVE: String = "live"
}
