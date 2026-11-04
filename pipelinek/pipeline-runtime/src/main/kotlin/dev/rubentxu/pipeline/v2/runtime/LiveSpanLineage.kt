package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.ExecutionContext

/**
 * Where a live span hangs in the causal chain of a run.
 *
 * ## WHY THE PARENT IS A VALUE AND NOT AN AMBIENT ONE
 *
 * A span that does not say what caused it is a list of events with timestamps,
 * not a trace: a reader can see that a step ran and that a run happened, and
 * nothing about which step belonged to which attempt. The parent is what turns
 * a list of spans into a tree.
 *
 * The obvious way to carry that parent is the ambient context of the tracing
 * library — a thread-local the tracer reads when a span is opened. That way
 * fails here for two reasons that are structural, not incidental:
 *
 *  - the engine dispatches branches on another dispatcher, so a thread-local
 *    set on the calling thread is GONE by the time the branch opens its span;
 *  - a durable run resumes in a NEW PROCESS, where no thread-local survives.
 *
 * Both are cases where the product must not lose the causal link, so the
 * parent travels EXPLICITLY, in the same value that carries the rest of the
 * dispatch's context. Nothing about it is ambient, and therefore nothing
 * about it can be mixed between two runs.
 *
 * ## THE THREE SHAPES
 *
 * A span can have no parent at all, a parent this process opened, or a parent
 * another process opened and named through a trace context. They are three
 * different facts and the type says which one is in force.
 */
sealed interface LiveSpanParent {

    /** No parent: the span opens a trace of its own. */
    data object Root : LiveSpanParent

    /** The parent is a span THIS process opened, and the handle is still in flight. */
    data class Local(val handle: LiveSpanHandle) : LiveSpanParent

    /** The parent is a span ANOTHER process opened, named by a trace context. */
    data class Remote(val context: RemoteTraceContext) : LiveSpanParent
}

/**
 * The identity of a span another process opened.
 *
 * It carries exactly the three fields a trace context names: the trace, the
 * span inside it, and whether that span was sampled. Baggage is deliberately
 * absent — this product never reads baggage, and a field it never reads is a
 * field it can only get wrong.
 */
data class RemoteTraceContext(
    val traceId: String,
    val spanId: String,
    val sampled: Boolean,
)

/**
 * The identity of a span THIS process opened.
 *
 * The same three fields, in the same shape, for the same reason: the adapter
 * that parents a child span needs the parent's identity, and it must not need
 * to know whether that identity came from this process or from another one.
 */
data class TraceIdentity(
    val traceId: String,
    val spanId: String,
    val sampled: Boolean,
)

/**
 * Reads a trace context out of the string an operator handed to the process.
 *
 * ## WHY A PURE FUNCTION AND NOT A READER
 *
 * The value is a claim about causality, and a claim that cannot be checked is
 * a claim nobody should act on. This function therefore answers ONE question
 * — is this string a well-formed trace context, and what does it name — and
 * answers it the same way every time: no reader, no clock, no environment,
 * no exception. A malformed value is NOT an error; it is the absence of a
 * parent, and the caller decides what to say about it.
 *
 * ## WHAT COUNTS AS VALID
 *
 * The format is four dash-separated fields: a version, a trace identifier of
 * thirty-two hexadecimal digits, a span identifier of sixteen, and two
 * hexadecimal flag digits. Version `00` carries exactly those four fields.
 * A later version may append fields, and those are ignored rather than
 * refused: the reader of a later version still understands the first four.
 * Version `ff` is reserved and never valid.
 *
 * Two identifiers are refused outright when every digit is zero, because the
 * all-zero trace and the all-zero span are the format's way of saying
 * "no trace" and "no span", and a parent that names nothing is not a parent.
 *
 * ## WHY THE VALUE IS NORMALISED AND NOT ECHOED
 *
 * The hexadecimal is lowercased on the way in. The identifiers are opaque
 * strings compared byte for byte, so a value written in upper case and the
 * same value written in lower case are the SAME trace, and normalising here
 * is what makes them compare equal downstream. Nothing else about the string
 * is kept: a reader that returns more than it needs is a reader that can
 * carry more than it should.
 *
 * ## WHY A LENGTH CEILING
 *
 * A trace context is a small, fixed-shape value. A very long string is not a
 * trace context, it is a payload, and refusing it keeps the cost of reading
 * one bounded no matter what the environment holds.
 */
fun parseTraceParent(raw: String): RemoteTraceContext? {
    if (raw.length > MAX_TRACE_CONTEXT_LENGTH) return null
    val fields = raw.trim().lowercase().split('-')
    if (fields.size < 4) return null
    val version = fields[0]
    if (!version.isLowerHex(2)) return null
    val versionNumber = version.toInt(16)
    if (versionNumber == RESERVED_TRACE_VERSION) return null
    if (versionNumber == 0 && fields.size != 4) return null
    val traceId = fields[1]
    val spanId = fields[2]
    val flags = fields[3]
    if (!traceId.isLowerHex(TRACE_ID_LENGTH) || traceId.all { it == '0' }) return null
    if (!spanId.isLowerHex(SPAN_ID_LENGTH) || spanId.all { it == '0' }) return null
    if (!flags.isLowerHex(FLAGS_LENGTH)) return null
    return RemoteTraceContext(
        traceId = traceId,
        spanId = spanId,
        sampled = (flags.toInt(16) and SAMPLED_FLAG) == SAMPLED_FLAG,
    )
}

/**
 * The context a dispatch carries while it works.
 *
 * ## WHY THE TWO FACTS TRAVEL TOGETHER
 *
 * A dispatch knows two things about itself: what the pipeline has told it so
 * far (the ambient overlays), and where it sits in the causal chain of the
 * run (the span parent). Both are answers to the same question — "in what
 * context is this work happening" — and both have to reach the same places.
 *
 * Carrying them in ONE value is what makes it impossible to hand a body the
 * overlays of one run and the parent of another. Two separate parameters
 * would be two independent chances to disagree, and the disagreement would be
 * invisible: the run would still produce the right answer, and only the
 * trace would be wrong.
 *
 * ## WHY IT IS IMMUTABLE
 *
 * Every descent into a body produces a NEW scope rather than mutating one.
 * A body that pushes an overlay, a body that re-parents its children and a
 * body that runs on another dispatcher all produce their own value, and the
 * value they inherited stays exactly as it was.
 */
data class SpanScope(
    /** What the pipeline has told this dispatch so far. */
    val ambient: ExecutionContext,

    /** The span this dispatch hangs under, when it opens one. */
    val parent: LiveSpanParent,
) {

    /** The same scope over a different ambient context. */
    fun withAmbient(ambient: ExecutionContext): SpanScope = copy(ambient = ambient)

    /** The same scope under a different span parent. */
    fun withParent(parent: LiveSpanParent): SpanScope = copy(parent = parent)

    companion object {
        /** A scope with no overlays and no parent: the outside of everything. */
        val EMPTY: SpanScope = SpanScope(ExecutionContext.EMPTY, LiveSpanParent.Root)
    }
}

/**
 * The parent a span opened under [handle] belongs to.
 *
 * A boundary that could not open a span has no parent to hand down, and the
 * children of a boundary that never existed are not children of anything:
 * they are the outside. Returning the root here is what keeps a failed
 * observation from inventing a link.
 */
internal fun liveSpanParentOf(handle: LiveSpanHandle?): LiveSpanParent =
    handle?.let { LiveSpanParent.Local(it) } ?: LiveSpanParent.Root

/** The hexadecimal shape a field of a trace context must have. */
private fun String.isLowerHex(length: Int): Boolean =
    this.length == length && all { it in '0'..'9' || it in 'a'..'f' }

/** The longest string a reader will even look at. */
private const val MAX_TRACE_CONTEXT_LENGTH: Int = 256

/** The version that is reserved and never valid. */
private const val RESERVED_TRACE_VERSION: Int = 0xFF

/** The hexadecimal length of a trace identifier. */
private const val TRACE_ID_LENGTH: Int = 32

/** The hexadecimal length of a span identifier. */
private const val SPAN_ID_LENGTH: Int = 16

/** The hexadecimal length of the flags field. */
private const val FLAGS_LENGTH: Int = 2

/** The flag digit that says the span was sampled. */
private const val SAMPLED_FLAG: Int = 0x01
