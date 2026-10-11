package dev.rubentxu.pipeline.v2.events.shape

import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * M7 — real-store contract test for [EventShapeReadPortStoreAdapter].
 *
 * The adapter composes a typed-event reader callback; the test wires a
 * in-memory list of [dev.rubentxu.pipeline.v2.events.DomainEvent]
 * instances (the same shape the production EventSink records) and
 * asserts the projection to [TypedEvent] is correct, the bounded-scan
 * + cursor semantics hold, and the refusal cases are returned (not
 * thrown).
 *
 * Test cases:
 *  1. unknown runId → Refused(UnknownRun)
 *  2. run with no typed events → Empty
 *  3. run with one stage-started event → Events with 1 case
 *  4. run with stage-started + stage-finished + step-started + step-finished + stage-skipped → 5 cases
 *  5. bounded scan: pageSize = 2 on 5-case run returns 2 events + a cursor
 *  6. resume cursor: the next call returns the next 2 events; the third call returns the last + a null cursor
 *  7. cursor past the end returns Events(emptyList, cursor = null)
 *  8. readTypedEvents callback throws → Refused(StorageError)
 *  9. TypedEvent sealed interface has exactly 5 permittedSubclasses (the M7 audit's B.3 hardening)
 * 10. runExists callback returning false on a known runId returns Refused(UnknownRun)
 */
class EventShapeReadPortAdapterTest {

    private val runId = "m7-shape-run-1"
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun stageStarted(idx: Int = 0, name: String = "build"): StageStarted =
        StageStarted(
            eventId = "e-${idx}-start",
            runId = runId,
            sequence = (idx * 2 + 1).toLong(),
            occurredAt = at,
            stageIndex = idx,
            stageName = name,
        )

    private fun stageFinished(idx: Int = 0, outcome: String = "success"): StageFinished =
        StageFinished(
            eventId = "e-${idx}-finish",
            runId = runId,
            sequence = (idx * 2 + 2).toLong(),
            occurredAt = at,
            stageIndex = idx,
            stageName = "build",
            outcome = outcome,
        )

    private fun stageSkipped(idx: Int = 1, name: String = "deploy", reason: String = "gate=false"): StageSkipped =
        StageSkipped(
            eventId = "e-${idx}-skip",
            runId = runId,
            sequence = 100,
            occurredAt = at,
            stageIndex = idx,
            stageName = name,
            reason = reason,
        )

    private fun stepStarted(stageIdx: Int = 0, stepIdx: Int = 0, name: String = "compile"): StepStarted =
        StepStarted(
            eventId = "s-${stageIdx}-${stepIdx}-start",
            runId = runId,
            sequence = 200 + stepIdx.toLong(),
            occurredAt = at,
            stageIndex = stageIdx,
            stepIndex = stepIdx,
            stepName = name,
            stepType = "shell",
        )

    private fun stepFinished(stageIdx: Int = 0, stepIdx: Int = 0, type: String = "shell"): StepFinished =
        StepFinished(
            eventId = "s-${stageIdx}-${stepIdx}-finish",
            runId = runId,
            sequence = 300 + stepIdx.toLong(),
            occurredAt = at,
            stageIndex = stageIdx,
            stepIndex = stepIdx,
            stepName = "compile",
            stepType = type,
        )

    private fun port(events: List<Any>, known: Set<String> = setOf(runId)): EventShapeReadPort {
        return EventShapeReadPortStoreAdapter(
            readTypedEvents = { rid -> if (rid == runId) events else emptyList() },
            runExists = { rid -> rid in known },
        )
    }

    @Test
    fun `1 eventShapesForRun on an unknown runId returns Refused UnknownRun`() {
        val p = port(events = emptyList(), known = emptySet())
        val result = p.eventShapesForRun("never-existed", EventShapeOptions.Default)
        val refused = assertInstanceOf(EventShapeResult.Refused::class.java, result)
        assertInstanceOf(EventShapeRefusal.UnknownRun::class.java, refused.refusal)
    }

    @Test
    fun `2 eventShapesForRun on a known run with no typed events returns Empty`() {
        val p = port(events = emptyList())
        val result = p.eventShapesForRun(runId)
        assertEquals(EventShapeResult.Empty, result)
    }

    @Test
    fun `3 eventShapesForRun on a run with one StageStarted returns Events with one StageStartedTyped`() {
        val p = port(events = listOf(stageStarted()))
        val result = p.eventShapesForRun(runId)
        val events = assertInstanceOf(EventShapeResult.Events::class.java, result)
        assertEquals(1, events.events.size)
        val first = events.events[0]
        assertInstanceOf(StageStartedTyped::class.java, first)
        assertEquals(0, (first as StageStartedTyped).stageOrdinal)
        assertEquals("build", first.stageName)
        assertEquals(null, events.cursor)
    }

    @Test
    fun `4 eventShapesForRun on a run with all five typed events returns all five`() {
        val events = listOf(
            stageStarted(0, "build"),
            stepStarted(0, 0, "compile"),
            stepFinished(0, 0, "shell"),
            stageFinished(0, "success"),
            stageSkipped(1, "deploy", "gate=false"),
        )
        val p = port(events = events)
        val result = p.eventShapesForRun(runId)
        val typed = (result as EventShapeResult.Events).events
        assertEquals(5, typed.size)
        assertInstanceOf(StageStartedTyped::class.java, typed[0])
        assertInstanceOf(StepStartedTyped::class.java, typed[1])
        assertInstanceOf(StepFinishedTyped::class.java, typed[2])
        assertInstanceOf(StageFinishedTyped::class.java, typed[3])
        assertInstanceOf(StageSkippedTyped::class.java, typed[4])
    }

    @Test
    fun `5 bounded scan with pageSize 2 returns 2 events and a cursor`() {
        val events = listOf(
            stageStarted(0), stepStarted(0, 0), stepFinished(0, 0), stageFinished(0), stageSkipped(1),
        )
        val p = port(events = events)
        val result = p.eventShapesForRun(runId, EventShapeOptions(pageSize = 2))
        val typed = (result as EventShapeResult.Events)
        assertEquals(2, typed.events.size)
        assertNotNull(typed.cursor)
        assertEquals(2, typed.cursor!!.eventOrdinal)
    }

    @Test
    fun `6 resume cursor paginates through the run`() {
        val events = listOf(
            stageStarted(0), stepStarted(0, 0), stepFinished(0, 0), stageFinished(0), stageSkipped(1),
        )
        val p = port(events = events)
        val page1 = p.eventShapesForRun(runId, EventShapeOptions(pageSize = 2)) as EventShapeResult.Events
        assertEquals(2, page1.events.size)
        val cursor1 = page1.cursor!!
        val page2 = p.eventShapesForRun(runId, EventShapeOptions(pageSize = 2, cursor = cursor1)) as EventShapeResult.Events
        assertEquals(2, page2.events.size)
        val cursor2 = page2.cursor!!
        val page3 = p.eventShapesForRun(runId, EventShapeOptions(pageSize = 2, cursor = cursor2)) as EventShapeResult.Events
        assertEquals(1, page3.events.size)
        assertEquals(null, page3.cursor)
    }

    @Test
    fun `7 cursor past the end returns Events with empty list and a null cursor`() {
        val p = port(events = listOf(stageStarted()))
        val pastEnd = EventShapeCursor(eventOrdinal = 100)
        val result = p.eventShapesForRun(runId, EventShapeOptions(pageSize = 64, cursor = pastEnd))
        val events = assertInstanceOf(EventShapeResult.Events::class.java, result)
        assertEquals(0, events.events.size)
        assertEquals(null, events.cursor)
    }

    @Test
    fun `8 readTypedEvents throwing returns Refused StorageError`() {
        val throwingPort = EventShapeReadPortStoreAdapter(
            readTypedEvents = { _ -> throw IllegalStateException("disk full") },
            runExists = { _ -> true },
        )
        val result = throwingPort.eventShapesForRun(runId)
        val refused = assertInstanceOf(EventShapeResult.Refused::class.java, result)
        val se = assertInstanceOf(EventShapeRefusal.StorageError::class.java, refused.refusal)
        assertTrue(se.cause.contains("disk full"))
    }

    @Test
    fun `9 TypedEvent sealed interface has exactly 5 permittedSubclasses (M7 B3 hardening)`() {
        val sealed = TypedEvent::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("StageStartedTyped", "StageFinishedTyped", "StageSkippedTyped", "StepStartedTyped", "StepFinishedTyped"),
            permitted,
            "TypedEvent.permittedSubclasses must be exactly the 5 cases the M6 audit named",
        )
    }

    @Test
    fun `10 runExists returning false on a known runId returns Refused UnknownRun`() {
        val p = EventShapeReadPortStoreAdapter(
            readTypedEvents = { rid -> if (rid == runId) listOf(stageStarted()) else emptyList() },
            runExists = { _ -> false },
        )
        val result = p.eventShapesForRun(runId)
        val refused = assertInstanceOf(EventShapeResult.Refused::class.java, result)
        assertInstanceOf(EventShapeRefusal.UnknownRun::class.java, refused.refusal)
    }
}
