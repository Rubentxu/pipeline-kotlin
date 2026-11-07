package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A durable row exists. The store has already identified it, so the codec owes it an answer --
 * either the event, or a refusal carrying the reason. `List<DomainEvent>` cannot express that: an
 * empty list for a row that failed to decode is indistinguishable from a row that legitimately
 * holds nothing, so every caller would be pushed into writing `firstOrNull()` and deciding. That
 * discarding is exactly what the read-side law forbids.
 *
 * [JsonEventLog.decodeStoredRow] is therefore a closed outcome, and the ORDER of its rules is the
 * design:
 *
 *  1. a null payload column refuses -- the row exists and the one column that could have held it
 *     held nothing, which is a refusal and not an absence;
 *  2. a null or empty `kind` COLUMN refuses, even when the payload is perfectly decodable;
 *  3. an unknown `kind` COLUMN refuses as version skew, which an operator can act on;
 *  4. the payload has to be the SHAPE of a row -- a JSON array of EXACTLY ONE object -- and a
 *     payload of another shape refuses naming the shape, not a field it never had;
 *  5. otherwise the payload is decoded once and its own cause is carried through;
 *  6. a payload that decodes into an event of ANOTHER kind refuses, naming both words.
 *
 * Rule 4 is the one this decoder used to be missing. It CUT the brackets off the payload with
 * `substring(1, length - 1)` and never looked at what was inside them, so a payload that was
 * not an array reached the event decoder as residue and was refused for a field it never had
 * ("eventId is unreadable"), and a payload holding two events kept the first one in silence.
 * A row is refused because of what it HOLDS, and the refusal has to name that.
 *
 * Rules 2 and 3 are the ones that look redundant and are not. The first version of this read the
 * kind out of the PAYLOAD, which makes the classification wrong exactly when it matters: a row can
 * be unreadable *because* of its payload, and then the payload is the one thing that cannot be
 * trusted to name the row. The `kind` COLUMN is written by `bindInsert` from the event and is NOT
 * NULL, so it is the authority that survives.
 *
 * Rule 6 is what makes rule 2 worth its cost. A column and a payload are two statements about the
 * same row, and when they disagree the row is not what the column said it was -- accepting it
 * under the payload's own name would answer a question nobody asked.
 *
 * Pure and total: no store, no database, no clock. `Instant.now()` is never consulted, and one case
 * below says why that matters.
 */
class JsonEventLogDecodeStoredRowTest {

    /**
     * One `RunStarted`, the smallest kind whose required fields are all readable.
     *
     * The payload is the shape the WRITER produces: every field carries its own leading comma,
     * so the row ends with a value and not with a comma. A trailing comma is not part of the
     * wire format -- the old reader tolerated one because it scanned the text, and a fixture
     * leaning on that tolerance would be testing the leniency of a reader this one replaced
     * with a parser.
     */
    private fun validPayload(
        kind: String = "RunStarted",
        occurredAt: String = "2026-01-01T00:00:00Z",
    ) = """[{"eventId":"e-1","runId":"run-42","sequence":7,"kind":"$kind",""" +
        """"occurredAt":"$occurredAt","scriptPath":"/pipeline.kts"}]"""

    /**
     * One `RunStarted`, the event the fixtures that need a SHAPE the writer never produces are
     * built from.
     *
     * Those fixtures are encoded by the product's own encoder instead of being written by hand:
     * a hand-written payload would be a second authority for the wire, and it could drift from
     * the wire it is supposed to describe.
     */
    private fun runStarted(eventId: String) = RunStarted(
        eventId = eventId,
        runId = "run-42",
        sequence = 7L,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        scriptPath = "/pipeline.kts",
    )

    private fun refusedReason(outcome: StoredRowDecode): UndecodableReason {
        val refused = outcome as StoredRowDecode.Refused
        return refused.reason
    }

    // --- the ordering law: the COLUMN names the row ------------------------------

    @Test
    fun `a valid payload with no kind column is still refused`() {
        // THE discriminator. The payload names RunStarted and would decode
        // perfectly. If the kind were read from the payload, this would be
        // Accepted; it is refused because the COLUMN is the authority.
        val reason = refusedReason(JsonEventLog.decodeStoredRow(validPayload(), rowKind = null))

        assertEquals(
            UndecodableReason.MalformedPayload("row carries no kind"),
            reason,
            "a row with no kind column cannot be classified, and a decodable payload " +
                "does not make it classifiable",
        )
    }

    @Test
    fun `an empty kind column is treated the same as a missing one`() {
        val reason = refusedReason(JsonEventLog.decodeStoredRow(validPayload(), rowKind = ""))

        assertTrue(
            reason is UndecodableReason.MalformedPayload,
            "an empty column is absence, not an unknown name; got $reason",
        )
    }

    @Test
    fun `an unknown kind column refuses as version skew even when the payload decodes`() {
        val reason = refusedReason(
            JsonEventLog.decodeStoredRow(validPayload(kind = "RunStarted"), rowKind = "FromTheFuture"),
        )

        assertEquals(
            UndecodableReason.UnknownKind("FromTheFuture"),
            reason,
            "an unknown kind and an unreadable field are different facts; the operator " +
                "acts on one by upgrading and on the other by repairing",
        )
    }

    // --- a null payload column is a refusal, not an absence ----------------------

    @Test
    fun `a null payload column refuses`() {
        val reason = refusedReason(JsonEventLog.decodeStoredRow(null, rowKind = "RunStarted"))

        assertEquals(
            UndecodableReason.MalformedPayload("payload column is null"),
            reason,
            "the row exists; returning no events would report it as an empty run",
        )
    }

    // --- the happy path ----------------------------------------------------------

    @Test
    fun `a known kind with a decodable payload is accepted`() {
        val outcome = JsonEventLog.decodeStoredRow(validPayload(), rowKind = "RunStarted")

        val accepted = outcome as StoredRowDecode.Accepted
        assertEquals("run-42", accepted.event.runId)
        assertEquals(7L, accepted.event.sequence)
        assertEquals("/pipeline.kts", (accepted.event as RunStarted).scriptPath)
    }

    // --- the cause travels with the refusal --------------------------------------

    @Test
    fun `a missing required field refuses naming the field`() {
        val withoutScriptPath = """[{"eventId":"e-1","runId":"run-42","sequence":7,""" +
            """"kind":"RunStarted","occurredAt":"2026-01-01T00:00:00Z"}]"""

        val reason = refusedReason(JsonEventLog.decodeStoredRow(withoutScriptPath, rowKind = "RunStarted"))

        // The detail must NAME the field. These payloads can be GiB-scale, so an
        // older version quoted the payload and truncated the quote at 200 chars --
        // a measured 339-char payload produced a refusal that ended mid-filler
        // with neither the field name nor the value. The cause travels instead.
        val detail = (reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("scriptPath"),
            "the refusal must name the offending field so an operator can act on it; " +
                "got \"$detail\"",
        )
    }

    @Test
    fun `an unparseable timestamp refuses instead of being stamped with the read time`() {
        val reason = refusedReason(
            JsonEventLog.decodeStoredRow(
                validPayload(occurredAt = "yesterday"),
                rowKind = "RunStarted",
            ),
        )

        val detail = (reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("occurredAt"),
            "a present-but-unparseable occurredAt must be refused by name; falling back " +
                "to Instant.now() made a replayed event claim to have happened during " +
                "the replay, and two reads produced two different events",
        )
    }

    @Test
    fun `an empty event array refuses rather than decoding to nothing`() {
        val reason = refusedReason(JsonEventLog.decodeStoredRow("[]", rowKind = "RunStarted"))

        assertTrue(
            reason is UndecodableReason.MalformedPayload,
            "a row exists and its payload holds no event, which is a fact about the " +
                "row and not an empty result; got $reason",
        )
    }

    @Test
    fun `a blank payload refuses`() {
        val reason = refusedReason(JsonEventLog.decodeStoredRow("   ", rowKind = "RunStarted"))

        assertTrue(reason is UndecodableReason.MalformedPayload, "got $reason")
    }

    // --- the column and the payload are two statements about the same row -------

    @Test
    fun `a payload that decodes into another kind refuses, naming both words`() {
        // The column says CompilationStarted and the payload is a perfectly decodable
        // RunStarted. Both words are known to this binary, so neither rule 2 nor rule 3
        // applies; the row is refused because the two statements disagree about what the
        // row IS.
        val reason = refusedReason(
            JsonEventLog.decodeStoredRow(validPayload(kind = "RunStarted"), rowKind = "CompilationStarted"),
        )

        val detail = (reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("CompilationStarted") && detail.contains("RunStarted"),
            "the refusal must name BOTH words, or an operator cannot tell which side of the " +
                "row drifted; got \"$detail\"",
        )
    }

    // --- the SHAPE of the row: a JSON array of EXACTLY ONE event ------------------

    @Test
    fun `a payload that is not an array refuses naming the shape of the payload`() {
        // THE discriminator of this shape law. The payload is a perfectly good event object
        // and it is NOT an array: it is the row written the way the CLI writes an event, with
        // the brackets left out. The reader used to cut the first and the last character off
        // it and hand the rest to the event decoder, which then refused it for a field it
        // never had.
        val bare = JsonEventLog.encodeOne(runStarted("e-1"))
        val reason = refusedReason(JsonEventLog.decodeStoredRow(bare, rowKind = "RunStarted"))

        val detail = (reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("not a JSON array"),
            "the refusal names the SHAPE of the payload, because that is what is wrong with it; " +
                "got \"$detail\"",
        )
        assertFalse(
            detail.contains("eventId"),
            "the refusal must not blame a FIELD of a row whose problem is its shape: an " +
                "operator would go looking for a damaged field that was never damaged; got " +
                "\"$detail\"",
        )
    }

    @Test
    fun `a payload that holds two events refuses, because a row holds one`() {
        // Two events in the payload is the shape a DOCUMENT has and a ROW does not. The reader
        // used to keep the first one and say nothing about the second, which is a history that
        // reads complete and is not.
        val payload = JsonEventLog.encode(listOf(runStarted("e-1"), runStarted("e-2")))
        val reason = refusedReason(JsonEventLog.decodeStoredRow(payload, rowKind = "RunStarted"))

        val detail = (reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("2 events") && detail.contains("one"),
            "the refusal says HOW MANY events the payload holds and what a row holds; got " +
                "\"$detail\"",
        )
    }

    @Test
    fun `a payload that is not JSON at all refuses naming the shape of the payload`() {
        val reason = refusedReason(
            JsonEventLog.decodeStoredRow("not json at all", rowKind = "RunStarted"),
        )

        val detail = (reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("not JSON"),
            "a payload that is not JSON says so, instead of blaming a field of a document that " +
                "never had fields; got \"$detail\"",
        )
    }

    @Test
    fun `a payload whose kind this binary does not know refuses as version skew`() {
        // The COLUMN says a kind this binary knows and the PAYLOAD names one it does not. The
        // refusal is version skew and not damage: the row was written by a newer binary, and
        // the kind that is missing is the payload's own.
        val reason = refusedReason(
            JsonEventLog.decodeStoredRow(validPayload(kind = "FromTheFuture"), rowKind = "RunStarted"),
        )

        assertEquals(
            UndecodableReason.UnknownKind("FromTheFuture"),
            reason,
            "an unknown kind and an unreadable field are different facts, whichever of the two " +
                "statements about the row carries the unknown word",
        )
    }
}
