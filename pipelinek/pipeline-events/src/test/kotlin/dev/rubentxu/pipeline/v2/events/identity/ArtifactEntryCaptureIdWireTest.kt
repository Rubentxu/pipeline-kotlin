package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.ArtifactEntry
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The wire law of the capture identity on a durable archive entry.
 *
 * [ArtifactEntry] gained the capture identity without forgetting its oldest consumers:
 *  - a payload recorded before the field existed decodes, stating no capture (null),
 *    and every other field survives unchanged;
 *  - an entry constructed without an identity — the shape the legacy direct-layout
 *    readers produce — encodes without naming any capture, so absence is a fact a
 *    consumer reads, not a value it must guess;
 *  - an entry that names its capture round-trips the identity verbatim.
 */
class ArtifactEntryCaptureIdWireTest {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    private val digest = "0".repeat(64)

    private fun entry(captureId: String?) = ArtifactEntry(
        runId = "run-1",
        stageName = "stage-a",
        relPath = "build/app.jar",
        sha256 = digest,
        size = 42L,
        archivedAt = Instant.parse("2026-01-01T00:00:00Z"),
        captureId = captureId,
    )

    @Test
    fun `a payload written before capture identities decodes stating no capture`() {
        val preIdentity =
            """{"runId":"run-1","stageName":"stage-a","relPath":"build/app.jar",""" +
            """"sha256":"$digest","size":42,"archivedAt":"2026-01-01T00:00:00Z"}"""

        val decoded = json.decodeFromString(ArtifactEntry.serializer(), preIdentity)

        assertNull(decoded.captureId, "an absent identity decodes as an explicit absence")
        assertEquals(digest, decoded.sha256, "the pre-identity fields survive untouched")
        assertEquals(42L, decoded.size)
        assertEquals("build/app.jar", decoded.relPath)
    }

    @Test
    fun `an entry constructed without an identity names no capture on the wire`() {
        // The legacy direct-layout consumer builds entries exactly like this: it derives
        // them from the retention files themselves and holds no capture to name.
        val legacyShaped = ArtifactEntry(
            runId = "run-1",
            stageName = "stage-a",
            relPath = "build/app.jar",
            sha256 = digest,
            size = 42L,
            archivedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        assertNull(legacyShaped.captureId)
        assertFalse(
            json.encodeToString(ArtifactEntry.serializer(), legacyShaped).contains("capture-"),
            "no placeholder stands in for an identity the entry does not hold",
        )
    }

    @Test
    fun `a capture identity round-trips verbatim through the durable channel`() {
        val original = entry(captureId = "capture-7d3f")

        val decoded = json.decodeFromString(
            ArtifactEntry.serializer(),
            json.encodeToString(ArtifactEntry.serializer(), original),
        )

        assertEquals(original, decoded)
        assertEquals("capture-7d3f", decoded.captureId)
    }
}
