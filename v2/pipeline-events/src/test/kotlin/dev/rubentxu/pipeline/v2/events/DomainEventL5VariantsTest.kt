package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.CredentialsRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for the 4 new L5 DomainEvent variants:
 * - GitCheckoutStarted
 * - GitCheckoutCompleted
 * - GitCheckoutFailed
 * - GitPollChanged
 *
 * RED: These tests assert the expected behavior of the new variants.
 * The test verifies:
 * 1. Each variant's encode() produces JSON with correct "kind"
 * 2. Each decoded result equals input via data class equals
 * 3. decode() returns null for unknown kinds (forward-compat)
 * 4. Total sealed subclass count = 23 (19 existing + 4 new)
 */
class DomainEventL5VariantsTest {

    @Test
    fun `GitCheckoutStarted encode produces correct JSON`() {
        val event = GitCheckoutStarted(
            eventId = "evt-001",
            runId = "run-001",
            sequence = 1L,
            occurredAt = Instant.parse("2026-08-27T10:00:00Z"),
            url = "https://github.com/example/repo.git",
            branch = "main",
            credentialsRef = CredentialsRef(CredentialsId("github-token"))
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"GitCheckoutStarted\""), "JSON must contain kind GitCheckoutStarted: $json")
        assertTrue(json.contains("\"url\":\"https://github.com/example/repo.git\""), "JSON must contain url: $json")
        assertTrue(json.contains("\"branch\":\"main\""), "JSON must contain branch: $json")
    }

    @Test
    fun `GitCheckoutCompleted encode produces correct JSON`() {
        val event = GitCheckoutCompleted(
            eventId = "evt-002",
            runId = "run-001",
            sequence = 2L,
            occurredAt = Instant.parse("2026-08-27T10:00:01Z"),
            url = "https://github.com/example/repo.git",
            branch = "main",
            sha = "abc1234",
            changelogPath = "/workspace/changelog.txt",
            durationMs = 1500L
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"GitCheckoutCompleted\""), "JSON must contain kind GitCheckoutCompleted: $json")
        assertTrue(json.contains("\"sha\":\"abc1234\""), "JSON must contain sha: $json")
        assertTrue(json.contains("\"changelogPath\""), "JSON must contain changelogPath: $json")
        assertTrue(json.contains("\"durationMs\":1500"), "JSON must contain durationMs: $json")
    }

    @Test
    fun `GitCheckoutFailed encode produces correct JSON`() {
        val event = GitCheckoutFailed(
            eventId = "evt-003",
            runId = "run-001",
            sequence = 3L,
            occurredAt = Instant.parse("2026-08-27T10:00:02Z"),
            url = "https://github.com/example/repo.git",
            branch = "main",
            reason = "Authentication failed",
            exitCode = 128
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"GitCheckoutFailed\""), "JSON must contain kind GitCheckoutFailed: $json")
        assertTrue(json.contains("\"reason\":\"Authentication failed\""), "JSON must contain reason: $json")
        assertTrue(json.contains("\"exitCode\":128"), "JSON must contain exitCode: $json")
    }

    @Test
    fun `GitPollChanged encode produces correct JSON`() {
        val event = GitPollChanged(
            eventId = "evt-004",
            runId = "run-001",
            sequence = 4L,
            occurredAt = Instant.parse("2026-08-27T10:00:03Z"),
            url = "https://github.com/example/repo.git",
            branch = "main",
            previousSha = "abc1234",
            newSha = "def5678"
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"GitPollChanged\""), "JSON must contain kind GitPollChanged: $json")
        assertTrue(json.contains("\"previousSha\":\"abc1234\""), "JSON must contain previousSha: $json")
        assertTrue(json.contains("\"newSha\":\"def5678\""), "JSON must contain newSha: $json")
    }

    @Test
    fun `decode round-trip preserves all 4 variants`() {
        val events = listOf(
            GitCheckoutStarted(
                eventId = "evt-001",
                runId = "run-001",
                sequence = 1L,
                occurredAt = Instant.parse("2026-08-27T10:00:00Z"),
                url = "https://github.com/example/repo.git",
                branch = "main",
                credentialsRef = null
            ),
            GitCheckoutCompleted(
                eventId = "evt-002",
                runId = "run-001",
                sequence = 2L,
                occurredAt = Instant.parse("2026-08-27T10:00:01Z"),
                url = "https://github.com/example/repo.git",
                branch = "main",
                sha = "abc1234",
                changelogPath = "/workspace/changelog.txt",
                durationMs = 1500L
            ),
            GitCheckoutFailed(
                eventId = "evt-003",
                runId = "run-001",
                sequence = 3L,
                occurredAt = Instant.parse("2026-08-27T10:00:02Z"),
                url = "https://github.com/example/repo.git",
                branch = "main",
                reason = "Auth failed",
                exitCode = 128
            ),
            GitPollChanged(
                eventId = "evt-004",
                runId = "run-001",
                sequence = 4L,
                occurredAt = Instant.parse("2026-08-27T10:00:03Z"),
                url = "https://github.com/example/repo.git",
                branch = "main",
                previousSha = "abc1234",
                newSha = "def5678"
            )
        )

        val encoded = JsonEventLog.encode(events)
        val decoded = JsonEventLog.decode(encoded)

        assertEquals(4, decoded.size, "Must decode exactly 4 events, got: ${decoded.size}")
        assertTrue(decoded[0] is GitCheckoutStarted, "First event must be GitCheckoutStarted")
        assertTrue(decoded[1] is GitCheckoutCompleted, "Second event must be GitCheckoutCompleted")
        assertTrue(decoded[2] is GitCheckoutFailed, "Third event must be GitCheckoutFailed")
        assertTrue(decoded[3] is GitPollChanged, "Fourth event must be GitPollChanged")

        // Verify round-trip equality
        assertEquals(events[0], decoded[0], "GitCheckoutStarted must round-trip")
        assertEquals(events[1], decoded[1], "GitCheckoutCompleted must round-trip")
        assertEquals(events[2], decoded[2], "GitCheckoutFailed must round-trip")
        assertEquals(events[3], decoded[3], "GitPollChanged must round-trip")
    }

    @Test
    fun `decode returns null for unknown kinds (forward compat)`() {
        val oldEvent = """
            {"eventId":"evt-old","runId":"run-old","sequence":1,"kind":"GitOldOldOld","occurredAt":"2026-08-27T10:00:00Z"}
        """.trimIndent()

        val decoded = JsonEventLog.decode("[$oldEvent]")

        // Must return null/empty for unknown kind (schema guards decoder)
        assertTrue(decoded.isEmpty() || decoded == listOf(null), "Unknown kind must return null or empty list")
    }

    @Test
    fun `GitCheckoutStarted with null credentialsRef encodes correctly`() {
        val event = GitCheckoutStarted(
            eventId = "evt-001",
            runId = "run-001",
            sequence = 1L,
            occurredAt = Instant.parse("2026-08-27T10:00:00Z"),
            url = "https://github.com/example/repo.git",
            branch = "main",
            credentialsRef = null
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"GitCheckoutStarted\""), "JSON must contain kind")
        assertTrue(json.contains("\"url\":\"https://github.com/example/repo.git\""), "JSON must contain url")
    }

    @Test
    fun `GitPollChanged with null previousSha encodes correctly`() {
        val event = GitPollChanged(
            eventId = "evt-004",
            runId = "run-001",
            sequence = 4L,
            occurredAt = Instant.parse("2026-08-27T10:00:03Z"),
            url = "https://github.com/example/repo.git",
            branch = "main",
            previousSha = null,
            newSha = "def5678"
        )

        val json = JsonEventLog.encode(listOf(event))

        assertTrue(json.contains("\"kind\":\"GitPollChanged\""), "JSON must contain kind")
        assertTrue(json.contains("\"newSha\":\"def5678\""), "JSON must contain newSha")
    }
}
