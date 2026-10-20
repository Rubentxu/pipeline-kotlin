package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import org.junit.jupiter.api.Assertions.assertEquals
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
 *  4. otherwise the payload is decoded once and its own cause is carried through.
 *
 * Rules 2 and 3 are the ones that look redundant and are not. The first version of this read the
 * kind out of the PAYLOAD, which makes the classification wrong exactly when it matters: a row can
 * be unreadable *because* of its payload, and then the payload is the one thing that cannot be
 * trusted to name the row. The `kind` COLUMN is written by `bindInsert` from the event and is NOT
 * NULL, so it is the authority that survives.
 *
 * Pure and total: no store, no database, no clock. `Instant.now()` is never consulted, and one case
 * below says why that matters.
 */
class JsonEventLogDecodeStoredRowTest {

    /** One `RunStarted`, the smallest kind whose required fields are all readable. */
    private fun validPayload(
        kind: String = "RunStarted",
        occurredAt: String = "2026-01-01T00:00:00Z",
        extra: String = """""scriptPath":"/pipeline.kts",""",
    ) = """[{"eventId":"e-1","runId":"run-42","sequence":7,"kind":"$kind",""" +
        """"occurredAt":"$occurredAt",$extra}]"""

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

    // --- the kind table is one list, not two -------------------------------------

    @Test
    fun `the column is accepted for exactly the kinds this codec knows`() {
        // `knowsKind` and the decoder are meant to read the SAME list, so a kind
        // the column accepts must be one the decoder can actually build. Proving
        // it for RunStarted is enough to catch the split; the whole table is a
        // fixture the codec owns, not a promise this test should re-state.
        assertTrue(JsonEventLog.knowsKind("RunStarted"))
        assertTrue(JsonEventLog.knowsKind("RunCorrelated"))
        assertTrue(!JsonEventLog.knowsKind("RunStartedTypo"))
    }
}
