package dev.rubentxu.pipeline.fabric.consumer

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventTail
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.charset.StandardCharsets

/**
 * Consumer-side implementations of the two published ports.
 *
 * These exist to certify two properties, and neither is "the product works":
 *
 *  1. A port is implementable from an independent build. If `OutputReadPort` or `EventTail` had any
 *     `internal` type in a signature, or any parameter the consumer cannot name, this file would not
 *     compile — and the product would have published a contract that only the product can satisfy.
 *  2. The contracts are precise enough to satisfy correctly. Each method below implements the law its
 *     own KDoc states (a cursor from another stream is refused, not clamped; a page's `next` names
 *     the byte after the last one delivered), so the consumer's behaviour is determined by the
 *     published text rather than by anything it learned from reading the store's source.
 *
 * They are deliberately NOT a second product store. Nothing here persists, recovers or decides
 * anything; it holds a map of bytes and a list of envelopes for the duration of one test.
 */

/** An in-memory [OutputReadPort] that honours the published paging and refusal laws. */
class InMemoryConsole(private val streams: Map<OutputStreamId, ByteArray>) : OutputReadPort {

    /** Set to refuse every read with [reason], standing in for a store that has not recovered. */
    var recoveryGate: OutputRefusal? = null

    override fun committedExtent(stream: OutputStreamId): Long? = streams[stream]?.size?.toLong()

    override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult {
        recoveryGate?.let { return OutputReadResult.Refused(it) }
        val bytes = streams[stream] ?: return OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))

        // The contract says a cursor from another stream is refused, never clamped. Clamping would
        // hand a consumer position 0 of a different transcript and let it read that instead.
        if (cursor.stream != stream) {
            return OutputReadResult.Refused(OutputRefusal.ForeignStream(expected = stream, actual = cursor.stream))
        }
        if (cursor.committedOffset > bytes.size) {
            return OutputReadResult.Refused(
                OutputRefusal.OffsetBeyondCommitted(requested = cursor.committedOffset, committed = bytes.size.toLong()),
            )
        }

        val from = cursor.committedOffset.toInt()
        val to = minOf(from + maxBytes, bytes.size)
        val slice = bytes.copyOfRange(from, to)
        val end = from + slice.size
        return OutputReadResult.Page(
            OutputPage(
                bytes = slice,
                stream = stream,
                from = cursor.committedOffset,
                // `null` exactly at the committed end: the cursor cannot say "nothing more will
                // ever arrive", so the page says it, and the consumer's loop is what stops.
                next = if (end == bytes.size) null else OutputCursor(stream, end.toLong()),
                committedEnd = bytes.size.toLong(),
            ),
        )
    }

    override fun readRange(stream: OutputStreamId, from: Long, to: Long): OutputReadResult {
        val bytes = streams[stream] ?: return OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))
        // The two rules are the published ones, and getting them backwards is the mistake this
        // fixture had: it delegated straight to `read`, which truncates a range to whatever is
        // there. A caller that asked for [0, 9) of a six-byte stream and received six bytes has
        // been told a lie it cannot detect — the page looks complete and the caller never learns
        // two bytes were missing. A real store refuses with `OffsetBeyondCommitted`, naming both
        // the offset asked for and the extent actually committed.
        if (from < 0 || to <= from) return OutputReadResult.Refused(OutputRefusal.InvalidRange(from, to))
        if (to > bytes.size) {
            return OutputReadResult.Refused(
                OutputRefusal.OffsetBeyondCommitted(requested = to, committed = bytes.size.toLong()),
            )
        }
        return OutputReadResult.Page(
            OutputPage(
                bytes = bytes.copyOfRange(from.toInt(), to.toInt()),
                stream = stream,
                from = from,
                next = null,
                committedEnd = bytes.size.toLong(),
            ),
        )
    }
}

/** An in-memory [EventTail] that pages a fixed history in store-assigned sequence order. */
class InMemoryHistory(private val envelopes: List<PipelineEventEnvelope>) : EventTail {

    override fun readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage {
        val after = cursor?.lastSequence ?: Long.MIN_VALUE
        val page = envelopes.filter { it.sequence > after }.take(limit)
        val lastRead = page.lastOrNull()?.sequence
        // `hasMore` is about what is LEFT, not about how full this page was. The first version
        // compared the page size with the total history, so a full last page reported `hasMore =
        // true` and the consumer's loop spent one more round trip learning that there was nothing.
        // Worse, a history that fitted exactly in one page reported `hasMore = false` correctly only
        // by accident of arithmetic; an empty history reported `hasMore = false` for the wrong
        // reason. The contract's `hasMore` and `nextCursor` are independent on purpose, and this
        // adapter has to honour that independence rather than derive one from the other.
        val hasMore = lastRead != null && envelopes.any { it.sequence > lastRead }
        return EventPage(
            envelopes = page,
            // Null only when the page reached the end of history. A short page that is not the last
            // one still offers a cursor, or a reconnecting observer would stop early.
            nextCursor = lastRead?.let { EventCursor(run.canonicalText(), it) },
            hasMore = hasMore,
        )
    }
}

/** Builds a transcript of [text] for [stream], as a store would have after a step wrote it. */
fun transcript(stream: OutputStreamId, text: String): Map<OutputStreamId, ByteArray> =
    mapOf(stream to text.toByteArray(StandardCharsets.UTF_8))

/** The run reference every fixture uses. */
val demoRun: ResourceRef = ResourceRef(
    dev.rubentxu.pipeline.v2.domain.identity.ResourceKind.RUN,
    listOf("pipeline", "run-42"),
)

/** A fixed history: three envelopes in sequence order, one of them a retry. */
fun demoHistory(): List<PipelineEventEnvelope> = listOf(
    envelope(kind = "RunStarted", sequence = 1),
    envelope(kind = "StepStarted", sequence = 2),
    envelope(kind = "StepFinished", sequence = 3),
)

/** One envelope with a stable identity, so the golden file can pin its wire form. */
fun envelope(kind: String, sequence: Long): PipelineEventEnvelope = PipelineEventEnvelope(
    version = PipelineEventEnvelope.VERSION,
    eventRef = dev.rubentxu.pipeline.v2.events.identity.EventRef(
        source = demoRun,
        id = dev.rubentxu.pipeline.v2.events.identity.EventId("evt-$sequence"),
    ),
    kind = kind,
    occurredAt = java.time.Instant.parse("2026-10-04T00:00:00Z"),
    sequence = sequence,
    subject = demoRun,
    // Causation and correlation are part of the published envelope, and BLOCK 5 owes Fabric the
    // semantics. Exercising them here means the field is reachable from outside, not only inside.
    causation = if (sequence == 1L) null else envelopeRef(sequence - 1),
    correlation = envelopeRef(1),
)

private fun envelopeRef(sequence: Long): dev.rubentxu.pipeline.v2.events.identity.EventRef =
    dev.rubentxu.pipeline.v2.events.identity.EventRef(
        source = demoRun,
        id = dev.rubentxu.pipeline.v2.events.identity.EventId("evt-$sequence"),
    )
