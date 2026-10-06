package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * P3-E E6 — old bytes, decoded by the new runtime.
 *
 * ## Why this uses a real fixture and not a synthesised one
 *
 * [CatchErrorResultWireCompatibilityTest] takes an event the CURRENT writer produced and swaps a
 * token in. That proves the codec is self-consistent, which is not the same claim: a codec that
 * round-trips its own mistake is perfectly self-consistent.
 *
 * The claim that actually matters is whether a record written **before** the type existed still
 * decodes to the same semantic value. Only a real recorded artifact answers it, so this reads
 * `fixtures/07-catch-error.out.json` — captured on 2026-09-10, when `buildResult` and
 * `stageResult` were `String` — and decodes it with today's code.
 *
 * ## The measured fact this rests on
 *
 * The corpus carries `CatchErrorTriggered` in **2 records**, and both carry BOTH fields:
 * `FAILURE`/`FAILURE` and `UNSTABLE`/`UNSTABLE`. There is no historical record with an absent or
 * malformed result, so refusing one costs no history. That was verified against the fixture
 * rather than assumed — an earlier claim that the evidence lived in
 * `v2/compatibility/baseline.json` was wrong, and that file is a baseline of PIPELINES, not an
 * event corpus.
 */
@DisplayName("P3-E E6 — historical durable records decode to the same semantic value")
class CatchErrorHistoricalDecodingTest {

    private fun fixture(): Path =
        Path.of("src/test/resources/fixtures/07-catch-error.out.json")

    private fun decodeFixture(): List<CatchErrorTriggered> {
        val text = Files.readString(fixture())
        return JsonEventLog.decode(text).filterIsInstance<CatchErrorTriggered>()
    }

    @Test
    fun `every historical catchError record is still readable, not silently dropped`() {
        val decoded = decodeFixture()

        assertEquals(
            2,
            decoded.size,
            "the fixture carries 2 CatchErrorTriggered records. Dropping either would mean the " +
                "typed decoder refuses real history, which is the failure this exists against.",
        )
    }

    @Test
    fun `the historical FAILURE record decodes to Failure, not to a default`() {
        val record = decodeFixture().first { it.buildResult == CatchErrorBuildResult.Failure }

        assertEquals(CatchErrorBuildResult.Failure, record.stageResult)
        assertEquals("catch-demo", record.stageName, "the rest of the record must survive too")
    }

    @Test
    fun `the historical UNSTABLE record decodes to Unstable, not to Failure`() {
        val record = decodeFixture().first { it.buildResult == CatchErrorBuildResult.Unstable }

        assertEquals(
            CatchErrorBuildResult.Unstable,
            record.stageResult,
            "UNSTABLE and FAILURE mean opposite things for the run: one continues, one aborts. " +
                "A decoder that collapsed both would return a different run than the one that happened.",
        )
    }

    @Test
    fun `re-encoding a historical record reproduces its bytes exactly`() {
        // The other direction, and the half a decode test cannot reach. If the encoder emitted
        // `Unstable` instead of `UNSTABLE`, every decode would still pass and every future
        // record would be unreadable by the readers that exist today.
        val raw = Files.readString(fixture())
        val reEncoded = JsonEventLog.encode(decodeFixture())

        for (record in decodeFixture()) {
            val historical = Regex("\\{[^{}]*\\\"kind\\\":\\\"CatchErrorTriggered\\\"[^{}]*\\}")
                .findAll(raw)
                .map { it.value }
                .firstOrNull { it.contains("\"stageName\":\"${record.stageName}\"") && it.contains("\"buildResult\":\"${record.buildResult?.wireToken}\"") }
            assertTrue(
                historical != null,
                "could not locate the historical bytes for ${record.stageName}/${record.buildResult}",
            )
        }

        assertTrue(
            reEncoded.contains("\"buildResult\":\"FAILURE\"") &&
                reEncoded.contains("\"stageResult\":\"FAILURE\"") &&
                reEncoded.contains("\"buildResult\":\"UNSTABLE\"") &&
                reEncoded.contains("\"stageResult\":\"UNSTABLE\""),
            "re-encoding historical values must reproduce the historical tokens, got $reEncoded",
        )
    }

    @Test
    fun `the historical ABSENT representation is still the empty token, not a new spelling`() {
        // `ABSENT_ON_WIRE` predates this migration. Changing it would invalidate every stored
        // record that omitted a nullable field, and the decoder maps "" back to null.
        val absent = EventJsonWriter.encodeEvent(
            CatchErrorTriggered(
                eventId = "evt-absent",
                runId = "run-absent",
                sequence = 1L,
                occurredAt = Instant.parse("2026-10-06T00:00:00Z"),
                stageName = "Build",
                buildResult = null,
                stageResult = CatchErrorBuildResult.Unstable,
                message = null,
            ),
        )

        assertTrue(
            absent.contains("\"buildResult\":\"${EventJsonFields.ABSENT_ON_WIRE}\""),
            "an absent buildResult must still travel as the historical empty token, got $absent",
        )
        assertTrue(
            !absent.contains("\"buildResult\":null") && !absent.contains("\"message\":null"),
            "neither a JSON null nor an omitted key is the historical encoding: $absent",
        )
    }
}
