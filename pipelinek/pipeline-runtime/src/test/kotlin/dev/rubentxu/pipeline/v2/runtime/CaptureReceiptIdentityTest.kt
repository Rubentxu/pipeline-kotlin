package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The identity law of the capture vocabulary.
 *
 * The guarantee every consumer of the runtime relies on: a capture is identified by its
 * minted [ArtifactId] and by nothing else; the bytes it secured are identified by their
 * digest ([BlobId]); the visible name describes and never identifies. The rows below
 * refuse every shape that would let one identity stand for another — a name-shaped
 * string as content identity, a blank capture identity, a receipt without its capture —
 * and hold the mint to its promise that no two captures share an identity.
 *
 * The non-interchangeability of the types is a compile-time fact (a [BlobId] does not
 * compile where an [ArtifactId] is asked); what a runtime row CAN measure is that each
 * type enforces its own meaning, which is what keeps the distinction from eroding.
 */
class CaptureReceiptIdentityTest {

    private fun receipt(captureId: ArtifactId = ArtifactId("capture-1")) = CaptureReceipt(
        captureId = captureId,
        runId = "run-1",
        stageName = "stage-a",
        name = ArtifactName("out/report.xml"),
        blobId = BlobId(Sha256.ofText("bytes")),
        size = 5L,
    )

    @Test
    fun `every minted capture identity is a new capture`() {
        assertNotEquals(ArtifactId.mint(), ArtifactId.mint())
        assertNotEquals(ArtifactId.mint(), ArtifactId.mint())
    }

    @Test
    fun `a capture identity is opaque and refuses nothing but blankness`() {
        assertEquals("capture-7", ArtifactId("capture-7").value)
        assertThrows(IllegalArgumentException::class.java) { ArtifactId("") }
        assertThrows(IllegalArgumentException::class.java) { ArtifactId("   ") }
    }

    @Test
    fun `a visible name never validates as content identity`() {
        // The same spelling is a fine name and no digest at all: a name describes,
        // a digest identifies content, and only the second type carries the rule.
        assertEquals("out/report.xml", ArtifactName("out/report.xml").value)
        assertThrows(IllegalArgumentException::class.java) { BlobId("out/report.xml") }
    }

    @Test
    fun `a blob identity is the canonical digest and refuses every other shape`() {
        assertEquals(Sha256.ofText("bytes"), BlobId(Sha256.ofText("bytes")).value)
        assertThrows(IllegalArgumentException::class.java) { BlobId("deadbeef") }
        assertThrows(IllegalArgumentException::class.java) { BlobId(Sha256.ofText("x").uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { BlobId("") }
    }

    @Test
    fun `a visible name refuses blank and line separators`() {
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("") }
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("  ") }
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { ArtifactName("a\rb") }
    }

    @Test
    fun `two receipts of the same bytes are two captures, never one`() {
        val first = receipt(ArtifactId("capture-1"))
        val second = receipt(ArtifactId("capture-2"))
        assertEquals(first.blobId, second.blobId, "same content means the same digest identity")
        assertNotEquals(first, second, "the capture identity keeps the two receipts apart")
        assertNotEquals(first.captureId, second.captureId)
    }

    @Test
    fun `a receipt refuses to exist without the identity its context carries`() {
        assertThrows(IllegalArgumentException::class.java) {
            receipt().copy(runId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            receipt().copy(stageName = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            receipt().copy(size = -1L)
        }
    }

    @Test
    fun `a custody receipt names the capture, the bytes and the custodian`() {
        val custody = CustodyReceipt(
            captureId = ArtifactId("capture-1"),
            blobId = BlobId(Sha256.ofText("bytes")),
            custodian = "remote-custody-port",
        )
        assertEquals("remote-custody-port", custody.custodian)
        assertEquals(ArtifactId("capture-1"), custody.captureId)
        assertThrows(IllegalArgumentException::class.java) {
            custody.copy(custodian = " ")
        }
    }

    @Test
    fun `a transfer ticket names the bytes, the grantee and its expiry`() {
        val expiry = Instant.parse("2030-01-01T00:00:00Z")
        val ticket = TransferTicket(
            blobId = BlobId(Sha256.ofText("bytes")),
            grantedTo = "fabric-transfer-port",
            expiresAt = expiry,
        )
        assertEquals(expiry, ticket.expiresAt)
        assertTrue(ticket.expiresAt.isAfter(Instant.now()), "a ticket that already expired authorizes nothing")
        assertThrows(IllegalArgumentException::class.java) {
            ticket.copy(grantedTo = "")
        }
    }
}
