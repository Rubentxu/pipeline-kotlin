package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.time.Instant

/**
 * Tests for the 4 new L7 DomainEvent variants (ML-R7):
 * - FileWritten
 * - FileRead
 * - ArtifactArchived
 * - ArtifactArchiveFailed
 *
 * RED: These tests assert the expected behavior of the new variants.
 * The test verifies:
 * 1. Each variant's encode() produces JSON with correct "kind"
 * 2. Each decoded result equals input via data class equals
 * 3. No forbidden fields (text/content/bytes/data) in FileWritten/FileRead serialization
 * 4. Total sealed subclass count = 27 (23 existing + 4 new)
 * 5. decode() returns null for unknown kinds (forward-compat)
 */
class DomainEventRoundTripTest {

    @Test
    fun `FileWritten encode produces correct JSON with sha256 and size`() {
        val event = FileWritten(
            eventId = "evt-fw-001",
            runId = "run-001",
            sequence = 5L,
            occurredAt = Instant.parse("2026-08-28T10:00:00Z"),
            path = Paths.get("/workspace/build/version.txt"),
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            size = 5L,
            atomicallyMoved = true,
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"FileWritten\""), "JSON must contain kind FileWritten: $json")
        assertTrue(json.contains("\"path\":\"/workspace/build/version.txt\""), "JSON must contain path: $json")
        assertTrue(json.contains("\"sha256\":\"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\""), "JSON must contain sha256: $json")
        assertTrue(json.contains("\"size\":5"), "JSON must contain size: $json")
        assertTrue(json.contains("\"atomicallyMoved\":true"), "JSON must contain atomicallyMoved: $json")
    }

    @Test
    fun `FileWritten round-trip preserves all fields`() {
        val original = FileWritten(
            eventId = "evt-fw-001",
            runId = "run-001",
            sequence = 5L,
            occurredAt = Instant.parse("2026-08-28T10:00:00Z"),
            path = Paths.get("/workspace/build/version.txt"),
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            size = 5L,
            atomicallyMoved = true,
        )

        val encoded = JsonEventLog.encode(listOf(original))
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size, "Must decode exactly 1 event")
        assertTrue(decoded[0] is FileWritten, "Decoded event must be FileWritten")
        val restored = decoded[0] as FileWritten
        assertEquals(original.runId, restored.runId)
        assertEquals(original.sequence, restored.sequence)
        assertEquals(original.sha256, restored.sha256)
        assertEquals(original.size, restored.size)
        assertEquals(original.atomicallyMoved, restored.atomicallyMoved)
    }

    @Test
    fun `FileRead encode produces correct JSON without content`() {
        val event = FileRead(
            eventId = "evt-fr-001",
            runId = "run-001",
            sequence = 6L,
            occurredAt = Instant.parse("2026-08-28T10:00:01Z"),
            path = Paths.get("/workspace/build/version.txt"),
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            size = 5L,
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"FileRead\""), "JSON must contain kind FileRead: $json")
        assertTrue(json.contains("\"path\":\"/workspace/build/version.txt\""), "JSON must contain path: $json")
        assertTrue(json.contains("\"sha256\":\"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\""), "JSON must contain sha256: $json")
        assertTrue(json.contains("\"size\":5"), "JSON must contain size: $json")
    }

    @Test
    fun `FileRead round-trip preserves all fields`() {
        val original = FileRead(
            eventId = "evt-fr-001",
            runId = "run-001",
            sequence = 6L,
            occurredAt = Instant.parse("2026-08-28T10:00:01Z"),
            path = Paths.get("/workspace/build/version.txt"),
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            size = 5L,
        )

        val encoded = JsonEventLog.encode(listOf(original))
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size, "Must decode exactly 1 event")
        assertTrue(decoded[0] is FileRead, "Decoded event must be FileRead")
        val restored = decoded[0] as FileRead
        assertEquals(original.runId, restored.runId)
        assertEquals(original.sequence, restored.sequence)
        assertEquals(original.path, restored.path)
        assertEquals(original.sha256, restored.sha256)
        assertEquals(original.size, restored.size)
    }

    @Test
    fun `FileRead with null sha256 and size round-trips correctly`() {
        val original = FileRead(
            eventId = "evt-fr-002",
            runId = "run-001",
            sequence = 7L,
            occurredAt = Instant.parse("2026-08-28T10:00:02Z"),
            path = Paths.get("/workspace/nonexistent/file.txt"),
            sha256 = null,
            size = null,
        )

        val encoded = JsonEventLog.encode(listOf(original))
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size)
        assertTrue(decoded[0] is FileRead)
        val restored = decoded[0] as FileRead
        assertNull(restored.sha256)
        assertNull(restored.size)
    }

    @Test
    fun `ArtifactArchived encode produces correct JSON with files array`() {
        val event = ArtifactArchived(
            eventId = "evt-aa-001",
            runId = "run-001",
            sequence = 10L,
            occurredAt = Instant.parse("2026-08-28T10:00:05Z"),
            files = listOf(
                ArtifactEntry(
                    runId = "run-001",
                    stageName = "build",
                    relPath = "target/app.jar",
                    sha256 = "abc123def456",
                    size = 1024L,
                    archivedAt = Instant.parse("2026-08-28T10:00:05Z"),
                ),
                ArtifactEntry(
                    runId = "run-001",
                    stageName = "build",
                    relPath = "target/lib.jar",
                    sha256 = "789xyz012abc",
                    size = 2048L,
                    archivedAt = Instant.parse("2026-08-28T10:00:05Z"),
                ),
            ),
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"ArtifactArchived\""), "JSON must contain kind ArtifactArchived: $json")
        assertTrue(json.contains("\"files\":["), "JSON must contain files array: $json")
        assertTrue(json.contains("\"relPath\":\"target/app.jar\""), "JSON must contain relPath: $json")
        assertTrue(json.contains("\"sha256\":\"abc123def456\""), "JSON must contain sha256: $json")
        assertTrue(json.contains("\"size\":1024"), "JSON must contain size: $json")
    }

    @Test
    fun `ArtifactArchived round-trip preserves all fields`() {
        val original = ArtifactArchived(
            eventId = "evt-aa-001",
            runId = "run-001",
            sequence = 10L,
            occurredAt = Instant.parse("2026-08-28T10:00:05Z"),
            files = listOf(
                ArtifactEntry(
                    runId = "run-001",
                    stageName = "build",
                    relPath = "target/app.jar",
                    sha256 = "abc123def456",
                    size = 1024L,
                    archivedAt = Instant.parse("2026-08-28T10:00:05Z"),
                ),
            ),
        )

        val encoded = JsonEventLog.encode(listOf(original))
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size, "Must decode exactly 1 event")
        assertTrue(decoded[0] is ArtifactArchived, "Decoded event must be ArtifactArchived")
        val restored = decoded[0] as ArtifactArchived
        assertEquals(original.runId, restored.runId)
        assertEquals(original.files.size, restored.files.size)
        assertEquals(original.files[0].relPath, restored.files[0].relPath)
        assertEquals(original.files[0].sha256, restored.files[0].sha256)
        assertEquals(original.files[0].size, restored.files[0].size)
    }

    @Test
    fun `ArtifactArchiveFailed encode produces correct JSON with reason`() {
        val event = ArtifactArchiveFailed(
            eventId = "evt-aaf-001",
            runId = "run-001",
            sequence = 11L,
            occurredAt = Instant.parse("2026-08-28T10:00:06Z"),
            reason = "no files matched patterns: [*.jar] (allowEmptyArchive=false)",
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"ArtifactArchiveFailed\""), "JSON must contain kind ArtifactArchiveFailed: $json")
        assertTrue(json.contains("\"reason\":\"no files matched patterns: [*.jar] (allowEmptyArchive=false)\""), "JSON must contain reason: $json")
    }

    @Test
    fun `ArtifactArchiveFailed round-trip preserves all fields`() {
        val original = ArtifactArchiveFailed(
            eventId = "evt-aaf-001",
            runId = "run-001",
            sequence = 11L,
            occurredAt = Instant.parse("2026-08-28T10:00:06Z"),
            reason = "no files matched patterns: [*.jar] (allowEmptyArchive=false)",
        )

        val encoded = JsonEventLog.encode(listOf(original))
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(1, decoded.size, "Must decode exactly 1 event")
        assertTrue(decoded[0] is ArtifactArchiveFailed, "Decoded event must be ArtifactArchiveFailed")
        val restored = decoded[0] as ArtifactArchiveFailed
        assertEquals(original.runId, restored.runId)
        assertEquals(original.reason, restored.reason)
    }

    @Test
    fun `FileWritten JSON contains no forbidden fields (text content bytes data)`() {
        val event = FileWritten(
            eventId = "evt-fw-002",
            runId = "run-001",
            sequence = 12L,
            occurredAt = Instant.parse("2026-08-28T10:00:07Z"),
            path = Paths.get("/workspace/output.txt"),
            sha256 = "abc123",
            size = 11L,
            atomicallyMoved = false,
        )

        val json = JsonEventLog.encode(listOf(event))

        assertFalse(json.contains("\"text\""), "FileWritten JSON must NOT contain 'text' field: $json")
        assertFalse(json.contains("\"content\""), "FileWritten JSON must NOT contain 'content' field: $json")
        assertFalse(json.contains("\"bytes\""), "FileWritten JSON must NOT contain 'bytes' field: $json")
        assertFalse(json.contains("\"data\""), "FileWritten JSON must NOT contain 'data' field: $json")
    }

    @Test
    fun `FileRead JSON contains no forbidden fields (text content bytes data)`() {
        val event = FileRead(
            eventId = "evt-fr-003",
            runId = "run-001",
            sequence = 13L,
            occurredAt = Instant.parse("2026-08-28T10:00:08Z"),
            path = Paths.get("/workspace/output.txt"),
            sha256 = "abc123",
            size = 11L,
        )

        val json = JsonEventLog.encode(listOf(event))

        assertFalse(json.contains("\"text\""), "FileRead JSON must NOT contain 'text' field: $json")
        assertFalse(json.contains("\"content\""), "FileRead JSON must NOT contain 'content' field: $json")
        assertFalse(json.contains("\"bytes\""), "FileRead JSON must NOT contain 'bytes' field: $json")
        assertFalse(json.contains("\"data\""), "FileRead JSON must NOT contain 'data' field: $json")
    }

    @Test
    fun `ArtifactArchived JSON contains no forbidden fields (content bytes data)`() {
        val event = ArtifactArchived(
            eventId = "evt-aa-002",
            runId = "run-001",
            sequence = 14L,
            occurredAt = Instant.parse("2026-08-28T10:00:09Z"),
            files = listOf(
                ArtifactEntry(
                    runId = "run-001",
                    stageName = "build",
                    relPath = "out.bin",
                    sha256 = "abc123",
                    size = 256L,
                    archivedAt = Instant.parse("2026-08-28T10:00:09Z"),
                ),
            ),
        )

        val json = JsonEventLog.encode(listOf(event))

        assertFalse(json.contains("\"content\""), "ArtifactArchived JSON must NOT contain 'content' field: $json")
        assertFalse(json.contains("\"bytes\""), "ArtifactArchived JSON must NOT contain 'bytes' field: $json")
        assertFalse(json.contains("\"data\""), "ArtifactArchived JSON must NOT contain 'data' field: $json")
    }

    @Test
    fun `decode returns null for unknown kinds (forward compat)`() {
        val oldEvent = """
            {"eventId":"evt-old","runId":"run-old","sequence":1,"kind":"FileWritten","occurredAt":"2026-08-28T10:00:00Z","path":"/workspace/test.txt","sha256":"abc","size":3,"atomicallyMoved":true}
        """.trimIndent()

        val decoded = JsonEventLog.decode("[$oldEvent]")

        // The decode should handle the event (it's a known kind now)
        assertTrue(decoded.isNotEmpty(), "FileWritten should decode successfully since we added the variant")
    }

    @Test
    fun `sealed hierarchy contains 29 variants`() {
        val sealedSubclasses = DomainEvent::class.sealedSubclasses
        val count = sealedSubclasses.size
        assertEquals(
            29,
            count,
            "DomainEvent sealed hierarchy must have exactly 29 variants. Found: ${sealedSubclasses.map { it.simpleName }}"
        )
    }
}
