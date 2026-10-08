package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.3 follow-up — a credential `purpose` outside the vocabulary must NOT be reported as
 * `API_KEY`.
 *
 * `BoundPurpose` has seven variants and **no `UNKNOWN` member**, so
 * `try { BoundPurpose.valueOf(raw) } catch { BoundPurpose.API_KEY }` is not a carrier the way
 * `FailureKind -> UNKNOWN` is: it invents a binding kind. It lands on `CredentialBound` /
 * `CredentialUsed` — the surface AGENTS.md forbids putting wrong information on — so an observer
 * reading the durable stream cannot tell "bound as an API key" from "the purpose was unreadable".
 *
 * The refusal already has a home. `EventRecordRead`'s own KDoc forbids "a default semantic value"
 * by name and `UndecodableReason.MalformedPayload` is the closed reason for a known kind whose
 * payload is unreadable. So the fix converges onto that authority instead of inventing an
 * exception type.
 *
 * These rows cross the real decoder (`JsonEventLog.decodeStoredRow`), not a reimplementation.
 */
@DisplayName("B1.3 — a BoundPurpose outside the vocabulary is refused, not defaulted")
@Timeout(30)
class CredentialPurposeRefusalTest {

    /**
     * Builds the row payload the way the store writes it — `SqliteEventStore.bindInsert` calls
     * `JsonEventLog.encode(listOf(event))`, so the durable payload is an ARRAY, and
     * `decodeStoredRow` feeds it straight back into `decode`. Building a bare object here would
     * make every row refuse for a reason unrelated to what these rows assert.
     */
    private fun decodeOneRow(kind: String, purposeToken: String?): StoredRowDecode {
        val purposeField = if (purposeToken == null) "" else ""","purpose":"${purposeToken}""""
        val json = "{" +
            """"eventId":"e1","runId":"r1","sequence":1,""" +
            """"occurredAt":"2026-01-01T00:00:00Z","kind":"${kind}",""" +
            """"credentialsId":"cred-1"${purposeField}}"""
        return JsonEventLog.decodeStoredRow("[" + json + "]", kind)
    }

    @Test
    fun `BoundPurpose parse returns null outside the vocabulary and never defaults`() {
        assertNull(
            BoundPurpose.parse("NOT_A_BINDING_KIND"),
            "an unknown purpose token must parse to null, NOT to API_KEY: BoundPurpose has no " +
                "UNKNOWN member, so any default is an invented binding kind",
        )
        assertNull(BoundPurpose.parse("api_key"), "lookup must be exact-token, not case-folded")
        assertEquals(BoundPurpose.API_KEY, BoundPurpose.parse("API_KEY"))
        assertEquals(BoundPurpose.USERNAME_PASSWORD, BoundPurpose.parse("USERNAME_PASSWORD"))
    }

    @Test
    fun `BoundPurpose supportedTokens is derived from entries so the two cannot drift`() {
        assertEquals(
            BoundPurpose.entries.size,
            BoundPurpose.supportedTokens.size,
            "supportedTokens comes from entries; if this fails a variant was added without the " +
                "token map noticing",
        )
    }

    @Test
    fun `CredentialBound refuses an unknown purpose instead of claiming API_KEY`() {
        val result = decodeOneRow("CredentialBound", "TOTALLY_MADE_UP")
        assertTrue(
            result is StoredRowDecode.Refused,
            "purpose='TOTALLY_MADE_UP' must be a refusal; got $result — if this silently produced " +
                "CredentialBound(purpose=API_KEY) the durable stream would state a binding kind " +
                "that was never recorded",
        )
        val reason = (result as StoredRowDecode.Refused).reason
        assertTrue(
            reason is UndecodableReason.MalformedPayload,
            "a known kind with an unreadable field is MalformedPayload, not UnknownKind; got $reason",
        )
    }

    @Test
    fun `CredentialUsed refuses an unknown purpose the same way`() {
        val result = decodeOneRow("CredentialUsed", "TOTALLY_MADE_UP")
        assertTrue(result is StoredRowDecode.Refused, "must refuse; got $result")
        val reason = (result as StoredRowDecode.Refused).reason
        assertTrue(
            reason is UndecodableReason.MalformedPayload,
            "must be MalformedPayload; got $reason",
        )
    }

    @Test
    fun `every declared purpose still decodes — strictness is not the fix`() {
        for (purpose in BoundPurpose.entries) {
            val bound = decodeOneRow("CredentialBound", purpose.name)
            assertTrue(bound is StoredRowDecode.Accepted, "${purpose.name} must decode; got $bound")
            val boundEvent = (bound as StoredRowDecode.Accepted).event
            assertTrue(boundEvent is CredentialBound, "${purpose.name} must decode as CredentialBound")
            assertEquals(purpose, (boundEvent as CredentialBound).purpose)

            val used = decodeOneRow("CredentialUsed", purpose.name)
            assertTrue(used is StoredRowDecode.Accepted, "${purpose.name} must decode; got $used")
            val usedEvent = (used as StoredRowDecode.Accepted).event
            assertTrue(usedEvent is CredentialUsed, "${purpose.name} must decode as CredentialUsed")
            assertEquals(purpose, (usedEvent as CredentialUsed).purpose)
        }
    }

    @Test
    fun `an ABSENT purpose is a different defect from an UNREADABLE one`() {
        // This row is the CAUSAL half of the two refusal rows above, and it is what keeps them
        // from being vacuous. Both payloads differ in exactly one thing — the `purpose` field —
        // so refusal-with-the-token / accept-without-the-token shows the token is what decides.
        val absent = decodeOneRow("CredentialBound", null)
        assertTrue(
            absent is StoredRowDecode.Accepted,
            "a CredentialBound with no purpose field keeps its documented default; got $absent",
        )
        val unreadable = decodeOneRow("CredentialBound", "TOTALLY_MADE_UP")
        assertTrue(
            unreadable is StoredRowDecode.Refused,
            "the same payload WITH an unreadable purpose must refuse; got $unreadable",
        )
    }

    @Test
    fun `a refusal names the offending field even when the payload echo is truncated`() {
        // The RED that retires the "detail contains the field" assertion this class had to drop.
        //
        // `decodeStoredRow` builds `MalformedPayload.detail` by echoing the payload, capped at
        // `STORED_ROW_DETAIL_CHARS` (200). The payload therefore contains the field name and the
        // bad token for every possible rejection, which is what made `detail.contains("purpose")`
        // look like coverage while proving nothing — it could not have distinguished a purpose
        // refusal from one caused by `credentialsId`.
        //
        // It is worse than vacuous: it is UNSATISFIABLE in the case that matters. A realistic
        // payload — a diagnostics blob plus a purpose — runs past 200 characters, and the
        // truncation cuts the field off entirely. Measured here: the payload is 339 chars and
        // neither "purpose" nor the offending token survives the cap.
        //
        // So an operator reading a refused row of a real run cannot learn WHICH field failed. This
        // row asserts the cause travels with the refusal instead of being recoverable from an
        // echo of the very bytes that were unreadable.
        val filler = "x".repeat(170)
        val json = "{" +
            """"eventId":"e1","runId":"r1","sequence":1,""" +
            """"occurredAt":"2026-01-01T00:00:00Z","kind":"CredentialBound",""" +
            """"credentialsId":"cred-1","diagnostics":"${filler}","purpose":"TOTALLY_MADE_UP"}"""
        assertTrue(json.length > 200, "fixture must exceed the 200-char detail cap; got ${json.length}")

        val result = JsonEventLog.decodeStoredRow("[" + json + "]", "CredentialBound")
        assertTrue(result is StoredRowDecode.Refused, "must refuse; got $result")
        val detail = ((result as StoredRowDecode.Refused).reason as UndecodableReason.MalformedPayload).detail
        assertTrue(
            detail.contains("purpose"),
            "the refusal must name the offending field independently of the payload echo; " +
                "got: $detail",
        )
        assertTrue(
            detail.contains("TOTALLY_MADE_UP"),
            "the refusal must carry the offending value so an operator can act without " +
                "re-reading the raw row; got: $detail",
        )
        assertFalse(
            detail.contains(filler),
            "the cause is now named directly, so the refusal must NOT fall back to quoting the " +
                "payload it could not read — a 200-char echo of unreadable bytes is noise here; " +
                "got: $detail",
        )
    }
}
