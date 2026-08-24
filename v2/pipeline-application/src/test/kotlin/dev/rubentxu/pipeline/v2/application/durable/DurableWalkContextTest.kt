package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.application.BranchReconciler
import java.time.Instant

/**
 * Contract tests for [DurableWalkContext] (C-035).
 *
 * Tests the context data class construction, equality, and copy semantics.
 *
 * +3 cases per M3-R4.3 T-04.
 */
class DurableWalkContextTest {

    /** Minimal fake [EventSink] for testing. */
    class FakeEventSink : EventSink {
        val events = mutableListOf<dev.rubentxu.pipeline.v2.events.DomainEvent>()
        override fun append(event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
            events.add(event)
        }
        override fun eventsFor(runId: String): Sequence<dev.rubentxu.pipeline.v2.events.DomainEvent> {
            return events.asSequence()
        }
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Minimal fake [OperationJournal] for testing. */
    class FakeOperationJournal : OperationJournal {
        override fun append(op: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation, deadlineMs: Long?) {}
        override fun get(opId: String): dev.rubentxu.pipeline.v2.domain.durable.DurableOperation? = null
        override fun get(opId: String, attempt: Int): dev.rubentxu.pipeline.v2.domain.durable.DurableOperation? = null
        override fun listForRun(runId: String): List<dev.rubentxu.pipeline.v2.domain.durable.DurableOperation> = emptyList()
        override fun getDeadlineMs(opId: String, attempt: Int): Long? = null
        override fun getEndedAt(opId: String, attempt: Int): Long? = null
        override fun getStartedAt(opId: String, attempt: Int): Long? = null
        override fun beginOperation(
            opId: String,
            attempt: Int,
            fingerprint: String,
            inputJson: String,
            deadlineMs: Long?,
            branchIndex: Int?,
        ) {}
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Minimal fake [ReplayCursorStore] for testing. */
    class FakeReplayCursorStore : ReplayCursorStore {
        override fun load(runId: String): dev.rubentxu.pipeline.v2.events.durable.ReplayCursor? = null
        override fun advance(runId: String, opId: String, stageIndex: Int) {}
        override fun advancePastParallelFrame(
            frame: dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame,
            branchResults: List<dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult>,
        ): dev.rubentxu.pipeline.v2.events.durable.StageIndex =
            dev.rubentxu.pipeline.v2.events.durable.StageIndex(0)
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Minimal fake [Clock] for testing. */
    class FakeClock : Clock {
        override fun now(): Instant = Instant.parse("2026-08-24T12:00:00Z")
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Real [BranchReconciler] with fake dependencies for testing. */
    private val testBranchReconciler = BranchReconciler(
        opJournal = FakeOperationJournal(),
        cursorStore = FakeReplayCursorStore(),
        clock = FakeClock(),
    )

    /**
     * Case 1: Context construction with all 5 fields.
     *
     * Verifies that DurableWalkContext can be constructed with all fields
     * and each field is accessible.
     */
    @Test
    fun `context construction with all 5 fields`() {
        val clock = FakeClock()
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val reconciler = testBranchReconciler
        val eventSink = FakeEventSink()

        val ctx = DurableWalkContext(
            clock = clock,
            opJournal = journal,
            cursorStore = cursorStore,
            branchReconciler = reconciler,
            eventSink = eventSink,
        )

        assertSame(clock, ctx.clock, "clock should be the provided instance")
        assertSame(journal, ctx.opJournal, "opJournal should be the provided instance")
        assertSame(cursorStore, ctx.cursorStore, "cursorStore should be the provided instance")
        assertSame(reconciler, ctx.branchReconciler, "branchReconciler should be the provided instance")
        assertSame(eventSink, ctx.eventSink, "eventSink should be the provided instance")
    }

    /**
     * Case 2: Context equality and hashCode.
     *
     * Verifies that two contexts with the same field instances are equal
     * and have the same hashCode, and that contexts with different instances are not equal.
     */
    @Test
    fun `context equality and hashCode`() {
        val clock = FakeClock()
        val journal = FakeOperationJournal()
        val cursor = FakeReplayCursorStore()
        val reconciler = testBranchReconciler
        val sink = FakeEventSink()

        // Same instance → equal
        val ctx1 = DurableWalkContext(clock, journal, cursor, reconciler, sink)
        val ctx2 = DurableWalkContext(clock, journal, cursor, reconciler, sink)
        assertEquals(ctx1, ctx2, "same instance fields → equal")
        assertEquals(ctx1.hashCode(), ctx2.hashCode(), "equal objects must have same hashCode")

        // Different instances → not equal (reference equality for fakes)
        val ctx3 = DurableWalkContext(FakeClock(), FakeOperationJournal(), cursor, reconciler, sink)
        assertNotEquals(ctx1, ctx3, "different field instances → not equal")
    }

    /**
     * Case 3: Context copy with updated field.
     *
     * Verifies that the `copy` method (generated by Kotlin data class)
     * allows updating individual fields while preserving the rest.
     */
    @Test
    fun `context copy with updated field`() {
        val originalClock = FakeClock()
        val newClock = FakeClock()
        val journal = FakeOperationJournal()
        val cursorStore = FakeReplayCursorStore()
        val reconciler = testBranchReconciler
        val eventSink = FakeEventSink()

        val original = DurableWalkContext(originalClock, journal, cursorStore, reconciler, eventSink)

        // Copy with a new clock
        val updated = original.copy(clock = newClock)

        assertSame(newClock, updated.clock, "clock should be the new instance")
        assertSame(journal, updated.opJournal, "opJournal should be preserved")
        assertSame(cursorStore, updated.cursorStore, "cursorStore should be preserved")
        assertSame(reconciler, updated.branchReconciler, "branchReconciler should be preserved")
        assertSame(eventSink, updated.eventSink, "eventSink should be preserved")
        assertNotSame(originalClock, updated.clock, "original clock should be different from new clock")

        // Original should be unchanged
        assertSame(originalClock, original.clock, "original context should be unchanged")
    }
}
