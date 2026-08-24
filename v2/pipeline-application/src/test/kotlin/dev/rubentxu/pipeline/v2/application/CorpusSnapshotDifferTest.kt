package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class CorpusSnapshotDifferTest {

    private fun makeSnapshot(events: List<Map<String, Any?>>) = CorpusNormalizer.FixtureSnapshot(
        events = events,
        diagnostics = emptyList(),
        descriptorProjection = emptyMap(),
    )

    @Test
    fun `diff returns EXPECTED when snapshots match`() {
        val snapshot = makeSnapshot(
            listOf(
                mapOf("kind" to "RunStarted"),
                mapOf("kind" to "StageStarted", "stageName" to "build"),
            )
        )

        val diff = CorpusSnapshotDiffer.diff(snapshot, snapshot)
        assertEquals(CorpusSnapshotDiffer.Classification.EXPECTED, diff.classification)
        assertEquals(false, diff.isFailure)
    }

    @Test
    fun `diff returns BREAK when event count differs`() {
        val baseline = makeSnapshot(
            listOf(
                mapOf("kind" to "RunStarted"),
                mapOf("kind" to "StageStarted"),
            )
        )
        val current = makeSnapshot(
            listOf(
                mapOf("kind" to "RunStarted"),
                mapOf("kind" to "StageStarted"),
                mapOf("kind" to "StageFinished"),
            )
        )

        val diff = CorpusSnapshotDiffer.diff(baseline, current)
        assertEquals(CorpusSnapshotDiffer.Classification.BREAK, diff.classification)
        assertEquals(true, diff.isFailure)
    }

    @Test
    fun `diff returns BREAK when event kind changes`() {
        val baseline = makeSnapshot(
            listOf(
                mapOf("kind" to "RunStarted"),
                mapOf("kind" to "StageStarted"),
            )
        )
        val current = makeSnapshot(
            listOf(
                mapOf("kind" to "RunStarted"),
                mapOf("kind" to "ParallelBranchStarted"),
            )
        )

        val diff = CorpusSnapshotDiffer.diff(baseline, current)
        assertEquals(CorpusSnapshotDiffer.Classification.BREAK, diff.classification)
        assertEquals(true, diff.isFailure)
    }

    @Test
    fun `diff returns DIAGNOSTICS when diagnostic count differs`() {
        val baseline = CorpusNormalizer.FixtureSnapshot(
            events = emptyList(),
            diagnostics = listOf(mapOf("message" to "error1")),
            descriptorProjection = emptyMap(),
        )
        val current = CorpusNormalizer.FixtureSnapshot(
            events = emptyList(),
            diagnostics = emptyList(),
            descriptorProjection = emptyMap(),
        )

        val diff = CorpusSnapshotDiffer.diff(baseline, current)
        assertEquals(CorpusSnapshotDiffer.Classification.DIAGNOSTICS, diff.classification)
        assertEquals(false, diff.isFailure)
    }
}
