package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import org.junit.jupiter.api.Assertions.assertEquals
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
        //
        // The pairing replaces an assertion this test used to make and had to drop: that
        // `MalformedPayload.detail` names the offending field. `decodeStoredRow` builds that
        // detail by echoing the payload, and the payload contains the field name and the bad
        // token either way — so `detail.contains("purpose")` was true for every possible
        // rejection, including one caused by a field it never mentioned. Asserting it would have
        // certified the echo, not the cause.
        //
        // Naming the field in the reason needs a per-field decode failure reason, which does not
        // exist: `decodeEvent` returns `DomainEvent?`, so a refusal and its cause cannot travel
        // together. That is a real gap in this codec's diagnosability, recorded as backlog rather
        // than silently papered over here.
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
}
