package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P3-E E4 — two decoders that used to invent a semantic fact out of a missing field.
 *
 * ## The defect, twice, in opposite directions
 *
 * ```kotlin
 * stageResult = stringField(s, "stageResult") ?: "UNSTABLE"
 * outcome     = stringField(s, "outcome")     ?: "completed"
 * ```
 *
 * Both convert "this record said nothing" into "this record asserted X". Neither default is
 * neutral, which is what makes a default on a semantic field worse than no default at all:
 *
 * - `stageResult` defaults to **UNSTABLE**, and UNSTABLE means the run continues where
 *   FAILURE aborts it. A record whose stage result could not be read came back describing a
 *   *different run* than the one that happened — not a vaguer version of it, a different one.
 * - `outcome` defaults to **`completed`**, the single token that means the wait condition was
 *   satisfied. So a truncated record reported success.
 *
 * ## Why absence is corruption and not a version this runtime predates
 *
 * This was verified before the change, not assumed. `EventJsonWriter` writes both keys
 * unconditionally, and a scan of every historical JSON in the repository found
 * `CatchErrorTriggered` carrying `buildResult` and `stageResult` in **4/4** occurrences
 * (`FAILURE` x2, `UNSTABLE` x2) and `WaitUntilCompleted.outcome` in **1/1** (`completed`), with
 * no absence anywhere. So there is no legitimate historical record that this would reject,
 * and the compatibilities note that made a default seem defensible does not exist.
 *
 * ## What "fail closed" means here
 *
 * `decodeEvent` returns `null` for an unclassifiable line — the same shape it already used for
 * a missing `eventId`, and the same shape `PluginEventEmitted` uses when its identity fields
 * are absent. The line is not decoded into a fabricated event and it is not dropped into a
 * silently shorter stream: it stays unclassified so the reader can see that something was
 * there and could not be read.
 */
class SemanticFieldAbsentIsMalformedTest {

    private val occurredAt = Instant.parse("2026-10-05T12:00:00Z")

    private val catchError = CatchErrorTriggered(
        eventId = "evt-catch-001",
        runId = "run-catch-001",
        sequence = 4L,
        occurredAt = occurredAt,
        stageName = "build",
        buildResult = "FAILURE",
        stageResult = "UNSTABLE",
        message = "rethrow to the outer scope",
    )

    private val waitUntil = WaitUntilCompleted(
        eventId = "evt-wait-001",
        runId = "run-wait-001",
        sequence = 7L,
        occurredAt = occurredAt,
        totalAttempts = 3,
        totalDurationMs = 1_250L,
        outcome = "deadline-exceeded",
    )

    // ── the positives: a real record still round-trips ────────────────────────

    @Test
    fun `a complete CatchErrorTriggered still round-trips exactly`() {
        val decoded = JsonEventLog.decode("[${EventJsonWriter.encodeEvent(catchError)}]").single()

        assertEquals(catchError, decoded)
    }

    @Test
    fun `a complete WaitUntilCompleted still round-trips exactly`() {
        val decoded = JsonEventLog.decode("[${EventJsonWriter.encodeEvent(waitUntil)}]").single()

        assertEquals(waitUntil, decoded)
    }

    @Test
    fun `an absent buildResult still decodes as null because the field is nullable`() {
        // The contrast that keeps this honest: `buildResult` IS nullable, and a JSON null is
        // the truthful encoding of "not declared". Only the NON-NULL fields refuse.
        val encoded = EventJsonWriter.encodeEvent(catchError.copy(buildResult = null))
        val decoded = JsonEventLog.decode("[$encoded]").single() as CatchErrorTriggered

        assertEquals(null, decoded.buildResult, "a declared-absent buildResult is legitimate")
        assertEquals(catchError.stageResult, decoded.stageResult)
    }

    // ── the negatives: a missing semantic field refuses ────────────────────────

    @Test
    fun `a CatchErrorTriggered without stageResult is not decoded as UNSTABLE`() {
        val tampered = EventJsonWriter.encodeEvent(catchError)
            .replace(",\"stageResult\":\"UNSTABLE\"", "")

        assertTrue(
            !tampered.contains("\"stageResult\""),
            "the fixture must actually have the key removed: $tampered",
        )

        val decoded = JsonEventLog.decode("[$tampered]")

        assertTrue(
            decoded.isEmpty(),
            "a record with no readable stageResult must stay unclassified, but decoded to " +
                "$decoded — the old default reported UNSTABLE, which claims the run continued",
        )
    }

    @Test
    fun `a CatchErrorTriggered with a JSON null stageResult is not decoded as UNSTABLE`() {
        val tampered = EventJsonWriter.encodeEvent(catchError)
            .replace("\"stageResult\":\"UNSTABLE\"", "\"stageResult\":null")

        val decoded = JsonEventLog.decode("[$tampered]")

        assertTrue(
            decoded.isEmpty(),
            "stageResult is a NON-NULL String, so a JSON null is corruption, not an absence: $decoded",
        )
    }

    @Test
    fun `a WaitUntilCompleted without outcome is not decoded as completed`() {
        val tampered = EventJsonWriter.encodeEvent(waitUntil)
            .replace(",\"outcome\":\"deadline-exceeded\"", "")

        assertTrue(!tampered.contains("\"outcome\""), "fixture must drop the key: $tampered")

        val decoded = JsonEventLog.decode("[$tampered]")

        assertTrue(
            decoded.isEmpty(),
            "a record with no readable outcome must stay unclassified, but decoded to $decoded — " +
                "the old default reported COMPLETED, i.e. that the wait condition held",
        )
    }

    @Test
    fun `a WaitUntilCompleted with a JSON null outcome is not decoded as completed`() {
        val tampered = EventJsonWriter.encodeEvent(waitUntil)
            .replace("\"outcome\":\"deadline-exceeded\"", "\"outcome\":null")

        val decoded = JsonEventLog.decode("[$tampered]")

        assertTrue(
            decoded.isEmpty(),
            "outcome is a non-null String, so JSON null is corruption: $decoded",
        )
    }

    @Test
    fun `a bad stageResult does not take its healthy neighbours down with it`() {
        // One unreadable record must not blind the reader to the records around it, or the
        // fail-closed decision would turn into a silent gap in the durable stream.
        val bad = EventJsonWriter.encodeEvent(catchError).replace("\"stageResult\":\"UNSTABLE\"", "")
        val good = EventJsonWriter.encodeEvent(catchError.copy(eventId = "evt-catch-002"))

        val decoded = JsonEventLog.decode("[$bad,$good]")

        assertEquals(1, decoded.size, "the healthy record must still be observable: $decoded")
        assertEquals("evt-catch-002", decoded.single().eventId)
    }
}
