package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import java.util.concurrent.CopyOnWriteArrayList

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
    /**
     * The parent of the OUTERMOST spans this observer is asked to open.
     *
     * It is stated rather than defaulted by the port, and a row may change it to
     * pretend the observer was attached to a trace that already existed.
     */
    override val sessionParent: LiveSpanParent = LiveSpanParent.Root,
    /** What happens when the observer is asked to open a span. */
    private val onStart: (String, List<TelemetryAttribute>) -> Unit = { _, _ -> },
) : PipelineTelemetry {

    /**
     * Every span the production code opened, in the order it opened them.
     *
     * The list is a copy-on-write one because two runs may share a single
     * observer while they interleave on two coroutines: the writes come from
     * both, and a row that reads the list afterwards must see both without
     * the read racing the write. A plain list would lose a span here or throw
     * a concurrent modification, and the row would then be measuring the fake
     * instead of the product.
     */
    val opened: MutableList<RecordedLiveSpan> = CopyOnWriteArrayList()

    override fun startSpan(
        name: String,
        attributes: List<TelemetryAttribute>,
        parent: LiveSpanParent,
    ): PipelineSpan {
        onStart(name, attributes)
        val span = RecordedLiveSpan(name = name, attributes = attributes, parent = parent)
        opened += span
        return span
    }

    override fun close() = Unit

    /**
     * The recorded span that CAUSED [span], or null when nothing of ours did.
     *
     * A parent that is the session's own, or one that belongs to another
     * process, is not a span this observer opened — and saying so is what
     * lets a row tell "the span has no parent" from "the parent is not
     * mine to see".
     */
    fun recordedParentOf(span: RecordedLiveSpan): RecordedLiveSpan? = when (val parent = span.parent) {
        is LiveSpanParent.Local -> parent.handle.span as? RecordedLiveSpan
        LiveSpanParent.Root -> null
        is LiveSpanParent.Remote -> null
    }
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
    /** The parent this span was opened under, as the boundary declared it. */
    val parent: LiveSpanParent,
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
