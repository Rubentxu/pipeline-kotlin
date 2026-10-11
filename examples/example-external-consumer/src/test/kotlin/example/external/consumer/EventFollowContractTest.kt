package example.external.consumer

import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent
import dev.rubentxu.pipeline.v2.events.follow.EventFollowHandle
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.follow.EventFollowRefusal
import dev.rubentxu.pipeline.v2.events.follow.EventFollowState
import dev.rubentxu.pipeline.v2.events.follow.EventFollowUntil
import dev.rubentxu.pipeline.v2.events.follow.EventFollower
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * PK-R1 R1-E / T8 — `events.follow.v1` from the outside.
 *
 * Two tests, two assertions kinds, mirroring [OutputFollowContractTest].
 */
class EventFollowContractTest {

    // ── CONTRACT ─────────────────────────────────────────────────────────────

    @Test
    fun contract_eventFollowerIsAnInterfaceWithOpen() {
        val cls = EventFollower::class.java
        assertTrue(cls.isInterface, "EventFollower must be an interface")
        val open = cls.methods.firstOrNull { it.name == "open" }
        assertNotNull(open)
        assertEquals(2, open!!.parameterCount)
        assertEquals(String::class.java, open.parameterTypes[0])
        assertEquals(EventFollowOptions::class.java, open.parameterTypes[1])
        assertEquals(EventFollowHandle::class.java, open.returnType)
    }

    @Test
    fun contract_eventFollowHandleIsAnAutoCloseableWithIterator() {
        val cls = EventFollowHandle::class.java
        assertTrue(cls.isInterface)
        assertTrue(AutoCloseable::class.java.isAssignableFrom(cls))
        assertTrue(cls.methods.any { it.name == "iterator" })
        assertTrue(cls.methods.any { it.name == "close" })
    }

    @Test
    fun contract_eventFollowOptionsExposesDocumentedFields() {
        val names = EventFollowOptions::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("query" in names)
        assertTrue("pollIntervalMs" in names)
        assertTrue("maxRecords" in names)
        assertTrue("until" in names)
        assertTrue("after" in names)
        assertTrue("lagReportInterval" in names)
    }

    @Test
    fun contract_eventHierarchyHasPageStateChangedRefusedCompleted() {
        val sealed = EventFollowEvent::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("Page", "StateChanged", "Refused", "Completed"),
            permitted,
        )
    }

    @Test
    fun contract_eventFollowStateHasLiveRunFinishedUnobservable() {
        val sealed = EventFollowState::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("Live", "RunFinished", "Unobservable"), permitted)
    }

    @Test
    fun contract_eventFollowRefusalHasUnknownRunRetentionLostCancelled() {
        val sealed = EventFollowRefusal::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("UnknownRun", "RetentionLost", "Cancelled"), permitted)
    }

    @Test
    fun contract_eventFollowUntilHasUnboundedUntilRunFinishedUntilSequence() {
        val sealed = EventFollowUntil::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("Unbounded", "UntilRunFinished", "UntilSequence"), permitted)
    }

    // ── CAPABILITY ──────────────────────────────────────────────────────────

    @Test
    fun capability_eventFollowOptionsDefaultsMatchDocumentedValues() {
        val opts = EventFollowOptions()
        assertSame(EventQuery.All, opts.query, "default query is EventQuery.All")
        assertEquals(25L, opts.pollIntervalMs)
        assertEquals(256, opts.maxRecords)
        assertSame(EventFollowUntil.Unbounded, opts.until)
        assertNull(opts.after, "default after is null")
        assertEquals(Duration.ofSeconds(1), opts.lagReportInterval)
    }

    @Test
    fun capability_eventFollowOptionsRejectsNonPositiveKnobs() {
        assertThrows<IllegalArgumentException> { EventFollowOptions(pollIntervalMs = -1L) }
        assertThrows<IllegalArgumentException> { EventFollowOptions(maxRecords = 0) }
        assertThrows<IllegalArgumentException> {
            EventFollowOptions(lagReportInterval = Duration.ofSeconds(-1))
        }
    }

    @Test
    fun capability_eventFollowUntilUntilSequenceRejectsNegativeSequence() {
        assertThrows<IllegalArgumentException> {
            EventFollowUntil.UntilSequence(lastSequence = -1L)
        }
    }

    @Test
    fun capability_eventQueryAllIsADataObjectAndByKindHoldsItsKind() {
        assertSame(EventQuery.All, EventQuery.All, "Query.All is a data object — same reference")
        assertEquals("run-finished", EventQuery.ByKind("run-finished").kind)
    }

    @Test
    fun capability_eventCursorHoldsRunIdAndLastSequence() {
        val cursor = EventCursor(runId = "run-x", lastSequence = 42L)
        assertEquals("run-x", cursor.runId)
        assertEquals(42L, cursor.lastSequence)
    }

    @Test
    fun capability_eventFollowStateLiveHoldsLastSequence() {
        val state = EventFollowState.Live(lastSequence = 7L)
        assertEquals(7L, state.lastSequence)
    }

    @Test
    fun capability_consumerImplementationExercisesOpenAndIterator() {
        val cursor = EventCursor(runId = "run-2", lastSequence = 0L)
        val page = EventRecordSlice(records = emptyList(), nextCursor = cursor, hasMore = true)
        val script = listOf<EventFollowEvent>(
            EventFollowEvent.StateChanged(EventFollowState.Live(lastSequence = 0L)),
            EventFollowEvent.Page(slice = page, newState = EventFollowState.Live(lastSequence = 0L)),
            EventFollowEvent.StateChanged(EventFollowState.RunFinished),
            EventFollowEvent.Completed,
        )
        val follower = ScriptedEventFollower(script)
        follower.open("run-2", EventFollowOptions()).use { handle ->
            val it = handle.iterator()
            assertSame(it, handle.iterator(), "iterator() is idempotent — same object back")
            assertEquals(script, generateSequence { if (it.hasNext()) it.next() else null }.toList())
            assertFalse(it.hasNext(), "iterator is single-pass post-terminal")
        }
    }
}

private class ScriptedEventFollower(
    private val script: List<EventFollowEvent>,
) : EventFollower {
    override fun open(runId: String, options: EventFollowOptions): EventFollowHandle =
        object : EventFollowHandle {
            private var iter: Iterator<EventFollowEvent>? = null
            override fun iterator(): Iterator<EventFollowEvent> {
                iter?.let { return it }
                val backing = script.listIterator()
                iter = backing
                return backing
            }
            override fun close() {}
        }
}