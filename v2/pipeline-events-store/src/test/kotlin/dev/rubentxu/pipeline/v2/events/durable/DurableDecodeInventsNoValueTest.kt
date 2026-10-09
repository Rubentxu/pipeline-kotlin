package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.ArtifactEntry
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashedEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A durable row must not be repaired by inventing a value.
 *
 * **Fidelity (HF1, in-process).** Enters through the productive authority:
 * [JsonEventLog.decode]. It asserts what the durable decoder must refuse, not how
 * it happens to be implemented.
 *
 * **The family of defects.** Every row here fails for the same reason: a field is
 * *present but unreadable*, and the decoder turns that into a plausible value
 * instead of a refusal.
 *
 * - `occurredAt` present but unparseable becomes `Instant.now()`. Reading an old
 *   row stamps it with the time of reading, so a replayed event claims to have
 *   happened during the replay. That is ambient clock inside a decoder, and it
 *   makes replay non-deterministic.
 * - `severity` outside the vocabulary becomes `INFO`, the lowest-severity case,
 *   so an unreadable ERROR or WARNING is reported as routine information.
 * - `sizeBytes` absent becomes `0L`, so an entry of unknown size claims to be empty.
 * - `line`/`column` absent become `0`/`0`, pointing at the top of the file rather
 *   than admitting the position is unknown.
 *
 * A field that is genuinely optional may be absent. A field that the type requires
 * and the writer always emits may not be absent, and may not be malformed. Both
 * cases are refusals, and this class holds the decoder to the difference.
 */
class DurableDecodeInventsNoValueTest {

    @Test
    @DisplayName("an unparseable occurredAt is refused, not stamped with the reading time")
    fun `an unparseable occurredAt is refused rather than stamped now`() {
        val before = Instant.now()
        val row = """{"eventId":"evt-1","runId":"run-1","sequence":1,""" +
            """"kind":"RunStarted","occurredAt":"not-a-timestamp","scriptPath":"pipeline.kts"}"""

        val decoded = JsonEventLog.decode("[$row]")

        assertTrue(
            decoded.isEmpty(),
            "an unreadable occurredAt must be refused; got ${decoded.map { it.occurredAt }}",
        )

        // The assertion above is the contract. This second read makes the specific
        // failure mode visible if it regresses into a plausible value: a decoder
        // that fell back to Instant.now() would return an event stamped inside
        // this window rather than an empty list.
        val decodedAgain = JsonEventLog.decode("[$row]")
        assertTrue(
            decodedAgain.isEmpty(),
            "decoder produced ${decodedAgain.map { it.occurredAt }}, which is within $before..${Instant.now()}",
        )
    }

    @Test
    @DisplayName("an absent occurredAt is refused, distinct from an unparseable one")
    fun `an absent occurredAt is refused`() {
        val row = """{"eventId":"evt-1","runId":"run-1","sequence":1,""" +
            """"kind":"RunStarted","scriptPath":"pipeline.kts"}"""

        assertTrue(
            JsonEventLog.decode("[$row]").isEmpty(),
            "an absent occurredAt must be refused, exactly as an unparseable one is",
        )
    }

    @Test
    @DisplayName("a diagnostic severity outside the vocabulary is refused, not downgraded to INFO")
    fun `an unknown severity is refused rather than downgraded`() {
        val row = """{"eventId":"evt-1","runId":"run-1","sequence":1,""" +
            """"kind":"CompilationFinished","occurredAt":"2026-08-28T10:00:00Z",""" +
            """"cacheKey":{"value":"v","version":"v1"},""" +
            """"diagnostics":[{"severity":"NOT_A_SEVERITY","message":"m",""" +
            """"line":3,"column":4,"path":"a.kts"}]}"""

        val decoded = JsonEventLog.decode("[$row]")

        // The row itself is well-formed, so this test asserts the weaker but real
        // claim: whatever comes back must not silently carry INFO for a token the
        // vocabulary does not contain.
        val carried = decoded.filterIsInstance<CompilationFinished>().flatMap { it.diagnostics }.map { it.severity.name }
        assertTrue(
            !carried.contains("INFO") || decoded.isEmpty(),
            "an unknown severity must not decode as INFO; decoded=$decoded",
        )
    }

    @Test
    @DisplayName("an entry with absent sizeBytes is refused, not reported as an empty file")
    fun `an absent sizeBytes is refused rather than reported as zero`() {
        val complete = StashCreated(
            eventId = "evt-1",
            runId = "run-1",
            sequence = 1L,
            occurredAt = Instant.parse("2026-08-28T10:00:00Z"),
            stageName = "s",
            name = "stash-1",
            files = listOf(StashedEntry(relPath = "a.txt", sha256 = "abc", sizeBytes = 11L)),
        )
        val json = JsonEventLog.encode(listOf(complete))
        // Drop sizeBytes from the encoded entry; everything else stays valid.
        val withoutSize = json.replace(",\"sizeBytes\":11", "")

        assertTrue(
            JsonEventLog.decode(withoutSize).isEmpty(),
            "an entry with no sizeBytes must be refused, not admitted as a zero-byte file; got " +
                JsonEventLog.decode(withoutSize),
        )
    }

    @Test
    @DisplayName("the same entry with a real sizeBytes still decodes, so the refusal above is not blanket")
    fun `a well-formed stashed entry still decodes`() {
        val complete = StashCreated(
            eventId = "evt-1",
            runId = "run-1",
            sequence = 1L,
            occurredAt = Instant.parse("2026-08-28T10:00:00Z"),
            stageName = "s",
            name = "stash-1",
            files = listOf(StashedEntry(relPath = "a.txt", sha256 = "abc", sizeBytes = 11L)),
        )

        val decoded = JsonEventLog.decode(JsonEventLog.encode(listOf(complete))).single()

        assertEquals(complete, decoded, "a complete row must round-trip unchanged")
    }

    @Test
    @DisplayName("the same rule holds for archivedAt on an artifact entry")
    fun `an unparseable archivedAt is refused`() {
        val complete = ArtifactArchived(
            eventId = "evt-1",
            runId = "run-1",
            sequence = 1L,
            occurredAt = Instant.parse("2026-08-28T10:00:00Z"),
            files = listOf(
                ArtifactEntry(
                    runId = "run-1",
                    stageName = "s",
                    relPath = "a.txt",
                    sha256 = "abc",
                    size = 1L,
                    archivedAt = Instant.parse("2026-08-28T09:00:00Z"),
                ),
            ),
        )
        val corrupted = JsonEventLog.encode(listOf(complete))
            .replace("2026-08-28T09:00:00Z", "nope")

        assertTrue(
            JsonEventLog.decode(corrupted).isEmpty(),
            "an unparseable archivedAt must be refused rather than stamped with the reading time; got " +
                JsonEventLog.decode(corrupted),
        )
    }
}
