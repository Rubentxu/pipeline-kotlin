package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute

/**
 * The observer the live-span rows look at.
 *
 * ## The guarantee it makes observable
 *
 * A boundary that opens a span must be able to say, afterwards, WHICH span it
 * opened, what it carried, and HOW it ended — and a boundary that skipped the
 * work must leave NO trace at all. This fake is that memory: it records every
 * span the production code opens, so a row can assert the ABSENCE of one as
 * precisely as the presence of one. The absence is the half that matters: a
 * replay that skipped an effect has to be able to prove it emitted nothing.
 *
 * ## WHY IT IS A FAKE AND NOT THE SDK
 *
 * The SDK's span is a real object a real collector would receive, so asserting
 * on it would make every row depend on the exporter, on the SDK version and on
 * a collector being reachable. This fake is the SEAM the product declares — the
 * port's own shape, with the export replaced by a list. A green row says the
 * boundary opened and closed the span it was supposed to; how that span travels
 * is the exporter's business, and it is exercised by the integration scenario
 * instead.
 */
internal class RecordingLiveSpanObserver(
    override val enabled: Boolean = true,
    /** What happens when the observer is asked to open a span. */
    private val onStart: (String, List<TelemetryAttribute>) -> Unit = { _, _ -> },
) : PipelineTelemetry {

    /** Every span the production code opened, in the order it opened them. */
    val opened: MutableList<RecordedLiveSpan> = mutableListOf()

    override fun startSpan(name: String, attributes: List<TelemetryAttribute>): PipelineSpan {
        onStart(name, attributes)
        val span = RecordedLiveSpan(name = name, attributes = attributes)
        opened += span
        return span
    }

    override fun close() = Unit
}

/**
 * One span the observer was asked to open, and how it ended.
 *
 * The three endings are kept apart because they are three different facts: a
 * span ENDED with a status was exported and says the work ran; a span
 * DISCARDED was never exported and says the work did NOT run; a span that was
 * neither is a leak, and a row that asserts on an unended span is asserting
 * that the leak did not happen.
 */
internal class RecordedLiveSpan(
    val name: String,
    val attributes: List<TelemetryAttribute>,
) : PipelineSpan {

    /** The status the span was ENDED with, or null when it was never ended. */
    var endedWith: LiveSpanStatus? = null

    /** Whether the span was DISCARDED. A discarded span is never exported. */
    var discarded: Boolean = false

    /** Whether ending this span throws, the way a broken exporter does. */
    var failOnEnd: Boolean = false

    override fun end(status: LiveSpanStatus) {
        if (failOnEnd) error("the collector is gone")
        endedWith = status
    }

    override fun discard() {
        discarded = true
    }
}
