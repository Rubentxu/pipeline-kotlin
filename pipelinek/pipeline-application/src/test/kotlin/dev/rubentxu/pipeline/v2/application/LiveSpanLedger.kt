package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The ledger the live-span rows read.
 *
 * ## THE GUARANTEE IT MAKES OBSERVABLE
 *
 * A boundary that opens a span must be able to say, afterwards, WHICH span it
 * opened, what it carried, and HOW it ended -- and a boundary that skipped the
 * work must leave NO trace at all. This ledger is that memory: it records
 * every span the production code opens, so a row can assert the ABSENCE of one
 * as precisely as the presence of one. The absence is the half that matters:
 * a replay that skipped an effect has to be able to prove it emitted nothing.
 *
 * ## WHY IT IS A LEDGER AND NOT A LIST
 *
 * A list of spans is a list of events. A ledger is a list of events AND the
 * relations between them: who caused whom, in which order, and whether the
 * nesting was well formed. Those relations are the questions every row keeps
 * asking, so they live here as questions rather than as code copied from row
 * to row.
 *
 * ## WHY THE ORDER IS A SEQUENCE AND NOT A CLOCK
 *
 * The order of two spans is a fact about causality, and causality does not
 * need a timestamp to be checked. The ledger hands out a monotonic sequence
 * number, so a row can ask "did the child close before its parent" without a
 * clock, without sleeping and without a value that differs from one run of the
 * row to the next. A clock would make the row slower and would make it
 * measure the machine instead of the product.
 *
 * ## WHY IT IS A FAKE AND NOT THE SDK
 *
 * The SDK's span is a real object a real collector would receive, so asserting
 * on it would make every row depend on the exporter, on the SDK version and on
 * a collector being reachable. This ledger is the SEAM the product declares --
 * the port's own shape, with the export replaced by a log. A green row says
 * the boundary opened and closed the span it was supposed to; how that span
 * travels is the exporter's business, and it is exercised by the rows that
 * export through the real adapter.
 */
internal class LiveSpanLedger(
    override val enabled: Boolean = true,
    /**
     * The parent of the OUTERMOST spans this ledger is asked to open.
     *
     * It is stated rather than defaulted by the port, and a row may change it to
     * pretend the ledger was attached to a trace that already existed.
     */
    override val sessionParent: LiveSpanParent = LiveSpanParent.Root,
    /** What happens when the ledger is asked to open a span. */
    private val onStart: (String, List<TelemetryAttribute>) -> Unit = { _, _ -> },
) : PipelineTelemetry {

    /** Hands out the order in which spans opened and closed. */
    private val sequence = AtomicLong(0)

    /** Every span the production code opened, in the order it opened them. */
    private val log = CopyOnWriteArrayList<RecordedLiveSpan>()

    /**
     * Every span the production code opened, in the order it opened them.
     *
     * The list is a copy-on-write one because two runs may share a single
     * ledger while they interleave on two coroutines: the writes come from
     * both, and a row that reads the list afterwards must see both without
     * the read racing the write. A plain list would lose a span here or throw
     * a concurrent modification, and the row would then be measuring the fake
     * instead of the product.
     */
    val opened: List<RecordedLiveSpan> get() = log.toList()

    override fun startSpan(
        name: String,
        attributes: List<TelemetryAttribute>,
        parent: LiveSpanParent,
    ): PipelineSpan {
        onStart(name, attributes)
        val span = RecordedLiveSpan(
            name = name,
            attributes = attributes,
            parent = parent,
            openedAt = sequence.incrementAndGet(),
            stamp = { sequence.incrementAndGet() },
        )
        log += span
        return span
    }

    override fun close() = Unit

    /**
     * The one span recorded with this name for this run.
     *
     * A run identity is the only thing that tells two runs' spans apart, so it
     * is what the lookup uses. A name alone is not enough: a run that retries
     * opens more than one attempt span under the same name.
     */
    fun spanNamed(name: String, runId: String): RecordedLiveSpan =
        log.single { it.name == name && it.runId == runId }

    /** Every span that carries this run identity, in the order they opened. */
    fun spansOf(runId: String): List<RecordedLiveSpan> = log.filter { it.runId == runId }

    /**
     * The recorded span that CAUSED [span], or null when nothing of ours did.
     *
     * A parent that is the session's own, or one that belongs to another
     * process, is not a span this ledger opened -- and saying so is what lets a
     * row tell "the span has no parent" from "the parent is not mine to see".
     */
    fun parentOf(span: RecordedLiveSpan): RecordedLiveSpan? = when (val parent = span.parent) {
        is LiveSpanParent.Local -> parent.handle.span as? RecordedLiveSpan
        LiveSpanParent.Root -> null
        is LiveSpanParent.Remote -> null
    }

    /**
     * The recorded span that caused the work opened under [handle].
     *
     * The handle is what the boundary holds; the recorded span is what the
     * ledger saw, and the two are the same object here because the ledger
     * hands its own span back.
     */
    fun parentOf(handle: LiveSpanHandle): RecordedLiveSpan? =
        (handle.span as? RecordedLiveSpan)?.let { parentOf(it) }

    /** The spans that were opened under [span], in the order they opened. */
    fun childrenOf(span: RecordedLiveSpan): List<RecordedLiveSpan> =
        log.filter { parentOf(it) === span }

    /**
     * Whether [child] closed before [parent] closed.
     *
     * A well formed trace nests: the work of a span finishes before the span
     * that owns it finishes. A child that outlives its parent is a trace that
     * says the cause happened after its effect, and no collector can detect
     * that on its own.
     */
    fun endedBefore(child: RecordedLiveSpan, parent: RecordedLiveSpan): Boolean {
        val childClosed = child.closedAt ?: return false
        val parentClosed = parent.closedAt ?: return false
        return childClosed < parentClosed
    }

    /** Every span carrying at least one attribute that [predicate] accepts. */
    fun carryingAny(predicate: (TelemetryAttribute) -> Boolean): List<RecordedLiveSpan> =
        log.filter { span -> span.attributes.any(predicate) }

    /**
     * The spans that were neither ended nor discarded.
     *
     * A span still open when the run is over is a leak: it says nothing about
     * what happened, and it never reaches a collector.
     */
    fun leaked(): List<RecordedLiveSpan> = log.filter { it.endedWith == null && !it.discarded }
}

/**
 * One span the ledger was asked to open, and how it ended.
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
    /** The order in which this span opened, among all the ledger's spans. */
    val openedAt: Long,
    /** The order in which this span stops being live. */
    private val stamp: () -> Long,
) : PipelineSpan {

    /** The status the span was ENDED with, or null when it was never ended. */
    @Volatile
    var endedWith: LiveSpanStatus? = null

    /** Whether the span was DISCARDED. A discarded span is never exported. */
    @Volatile
    var discarded: Boolean = false

    /** The order in which this span closed, or null while it is still live. */
    @Volatile
    var closedAt: Long? = null

    /** Whether ending this span throws, the way a broken exporter does. */
    @Volatile
    var failOnEnd: Boolean = false

    /** The durable run identity the span carries, when it carries one. */
    val runId: String?
        get() = (attributes.firstOrNull { it.key == LiveSpanAttributes.RUN_ID } as? TelemetryAttribute.Text)?.value

    override fun end(status: LiveSpanStatus) {
        if (failOnEnd) error("the collector is gone")
        endedWith = status
        closedAt = stamp()
    }

    override fun discard() {
        discarded = true
        closedAt = stamp()
    }
}
