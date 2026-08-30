package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.WorkflowLoaded
import dev.rubentxu.pipeline.v2.events.WsCleaned
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * F-ARCH-L7-004 / EVT-L9-009: Event payload opacity invariant.
 *
 * Architecture test that enforces typed carriers in event payloads.
 * No Map<String,String> env fields; all ML-R9 event payloads use
 * typed fields per ADR-0049 §D8.
 *
 * EVT-L9-009: Structural grep gate — zero Map<String,String> env fields.
 * EVT-L9-010: FArchL7DomainEventExhaustivityTest asserts exactly 39 variants.
 *
 * Scenarios satisfied: EVT-L9-009, FIL-ALL-002
 *
 * RED: AssertionError on Map<String,String> field detected
 * GREEN: All event payloads use typed carriers
 */
class FArchL7EventPayloadOpacityTest {

    /**
     * Verifies a DomainEvent subclass has no Map<String,String> fields.
     */
    private fun assertNoMapStringStringFields(eventClass: Class<out DomainEvent>, eventName: String) {
        val mapFields = eventClass.declaredFields.filter { field ->
            field.type == Map::class.java ||
            (field.type.name?.contains("Map") == true && field.genericType?.toString()?.contains("String, String") == true)
        }

        if (mapFields.isNotEmpty()) {
            throw AssertionError(
                "$eventName has Map<String,String> field(s): ${mapFields.joinToString { it.name }}"
            )
        }
    }

    /**
     * Verifies a DomainEvent subclass has the expected typed fields.
     */
    private fun assertHasTypedField(eventClass: Class<out DomainEvent>, fieldName: String) {
        val hasField = eventClass.declaredFields.any { it.name == fieldName }
        assertTrue(hasField, "$eventClass should have typed field '$fieldName'")
    }

    // ==========================================================================
    // ML-R9 T-05 workspace-cleanup events
    // ==========================================================================

    @Test
    fun `DirDeleted has typed carrier fields no MapStringString`() {
        val event = DirDeleted(
            eventId = "test-1",
            runId = "run-1",
            sequence = 1L,
            occurredAt = Instant.now(),
            path = "build/output",
            deletedCount = 42,
            sha256 = "abc123"
        )

        assertHasTypedField(DirDeleted::class.java, "path")
        assertHasTypedField(DirDeleted::class.java, "deletedCount")
        assertHasTypedField(DirDeleted::class.java, "sha256")

        // Verify the fields have correct types
        val pathField = DirDeleted::class.java.getDeclaredField("path")
        assertEquals(String::class.java, pathField.type)

        val deletedCountField = DirDeleted::class.java.getDeclaredField("deletedCount")
        assertEquals(Int::class.javaPrimitiveType, deletedCountField.type)
    }

    @Test
    fun `WsCleaned has typed carrier fields no MapStringString`() {
        val event = WsCleaned(
            eventId = "test-2",
            runId = "run-1",
            sequence = 2L,
            occurredAt = Instant.now(),
            deletedFiles = 100,
            deletedDirs = 10,
            patterns = listOf("target/**", "*.tmp"),
            sha256 = "def456"
        )

        assertHasTypedField(WsCleaned::class.java, "deletedFiles")
        assertHasTypedField(WsCleaned::class.java, "deletedDirs")
        assertHasTypedField(WsCleaned::class.java, "patterns")
        assertHasTypedField(WsCleaned::class.java, "sha256")

        // Verify patterns is List<String>, not Map
        val patternsField = WsCleaned::class.java.getDeclaredField("patterns")
        assertTrue(patternsField.type.isAssignableFrom(List::class.java))
    }

    // ==========================================================================
    // ML-R9 T-06 error-handling events
    // ==========================================================================

    @Test
    fun `CatchErrorTriggered has typed carrier fields no MapStringString`() {
        val event = CatchErrorTriggered(
            eventId = "test-3",
            runId = "run-1",
            sequence = 3L,
            occurredAt = Instant.now(),
            stageName = "Build",
            buildResult = "UNSTABLE",
            stageResult = "UNSTABLE",
            message = "tolerated failure"
        )

        assertHasTypedField(CatchErrorTriggered::class.java, "stageName")
        assertHasTypedField(CatchErrorTriggered::class.java, "buildResult")
        assertHasTypedField(CatchErrorTriggered::class.java, "stageResult")
        assertHasTypedField(CatchErrorTriggered::class.java, "message")

        // Verify typed fields are String, not Map
        val stageNameField = CatchErrorTriggered::class.java.getDeclaredField("stageName")
        assertEquals(String::class.java, stageNameField.type)
    }

    @Test
    fun `StageMarkedUnstable has typed carrier fields no MapStringString`() {
        val event = StageMarkedUnstable(
            eventId = "test-4",
            runId = "run-1",
            sequence = 4L,
            occurredAt = Instant.now(),
            stageName = "Test",
            message = "flaky network"
        )

        assertHasTypedField(StageMarkedUnstable::class.java, "stageName")
        assertHasTypedField(StageMarkedUnstable::class.java, "message")

        val stageNameField = StageMarkedUnstable::class.java.getDeclaredField("stageName")
        assertEquals(String::class.java, stageNameField.type)
    }

    // ==========================================================================
    // ML-R9 T-07 workflow-loaded events
    // ==========================================================================

    @Test
    fun `WorkflowLoaded has typed carrier fields no MapStringString`() {
        val event = WorkflowLoaded(
            eventId = "test-5",
            runId = "run-1",
            sequence = 5L,
            occurredAt = Instant.now(),
            path = "sub.pipeline.kts",
            stepCount = 3,
            sha256 = "ghi789"
        )

        assertHasTypedField(WorkflowLoaded::class.java, "path")
        assertHasTypedField(WorkflowLoaded::class.java, "stepCount")
        assertHasTypedField(WorkflowLoaded::class.java, "sha256")

        val pathField = WorkflowLoaded::class.java.getDeclaredField("path")
        assertEquals(String::class.java, pathField.type)

        val stepCountField = WorkflowLoaded::class.java.getDeclaredField("stepCount")
        assertEquals(Int::class.javaPrimitiveType, stepCountField.type)
    }

    @Test
    fun `WaitUntilPolled has typed carrier fields no MapStringString`() {
        val event = WaitUntilPolled(
            eventId = "test-6",
            runId = "run-1",
            sequence = 6L,
            occurredAt = Instant.now(),
            attempt = 5,
            durationMs = 250L,
            conditionResult = true
        )

        assertHasTypedField(WaitUntilPolled::class.java, "attempt")
        assertHasTypedField(WaitUntilPolled::class.java, "durationMs")
        assertHasTypedField(WaitUntilPolled::class.java, "conditionResult")

        val attemptField = WaitUntilPolled::class.java.getDeclaredField("attempt")
        assertEquals(Int::class.javaPrimitiveType, attemptField.type)

        val durationMsField = WaitUntilPolled::class.java.getDeclaredField("durationMs")
        assertEquals(Long::class.javaPrimitiveType, durationMsField.type)
    }

    @Test
    fun `WaitUntilCompleted has typed carrier fields no MapStringString`() {
        val event = WaitUntilCompleted(
            eventId = "test-7",
            runId = "run-1",
            sequence = 7L,
            occurredAt = Instant.now(),
            totalAttempts = 10,
            totalDurationMs = 5000L,
            outcome = "completed"
        )

        assertHasTypedField(WaitUntilCompleted::class.java, "totalAttempts")
        assertHasTypedField(WaitUntilCompleted::class.java, "totalDurationMs")
        assertHasTypedField(WaitUntilCompleted::class.java, "outcome")
    }

    // ==========================================================================
    // ML-R9 T-09 milestone events
    // ==========================================================================

    @Test
    fun `MilestoneReached has typed carrier fields no MapStringString`() {
        val event = MilestoneReached(
            eventId = "test-8",
            runId = "run-1",
            sequence = 8L,
            occurredAt = Instant.now(),
            ordinal = 5,
            label = "build-milestone"
        )

        assertHasTypedField(MilestoneReached::class.java, "ordinal")
        assertHasTypedField(MilestoneReached::class.java, "label")

        val ordinalField = MilestoneReached::class.java.getDeclaredField("ordinal")
        assertEquals(Int::class.javaPrimitiveType, ordinalField.type)
    }

    @Test
    fun `MilestoneAborted has typed carrier fields no MapStringString`() {
        val event = MilestoneAborted(
            eventId = "test-9",
            runId = "run-1",
            sequence = 9L,
            occurredAt = Instant.now(),
            ordinal = 3,
            reason = "ordinal-already-reached"
        )

        assertHasTypedField(MilestoneAborted::class.java, "ordinal")
        assertHasTypedField(MilestoneAborted::class.java, "reason")
    }

    // ==========================================================================
    // ML-R9 T-10 timeout event
    // ==========================================================================

    @Test
    fun `TimeoutTriggered has typed carrier fields no MapStringString`() {
        val event = TimeoutTriggered(
            eventId = "test-10",
            runId = "run-1",
            sequence = 10L,
            occurredAt = Instant.now(),
            stageOrStep = "step:sh",
            action = "interrupt",
            durationMs = 30000L
        )

        assertHasTypedField(TimeoutTriggered::class.java, "stageOrStep")
        assertHasTypedField(TimeoutTriggered::class.java, "action")
        assertHasTypedField(TimeoutTriggered::class.java, "durationMs")

        val actionField = TimeoutTriggered::class.java.getDeclaredField("action")
        assertEquals(String::class.java, actionField.type)

        val durationMsField = TimeoutTriggered::class.java.getDeclaredField("durationMs")
        assertEquals(Long::class.javaPrimitiveType, durationMsField.type)
    }

    // ==========================================================================
    // ML-R9 T-05 Dir events (Entered/Exited)
    // ==========================================================================

    @Test
    fun `DirEntered has typed carrier fields no MapStringString`() {
        val event = DirEntered(
            eventId = "test-11",
            runId = "run-1",
            sequence = 11L,
            occurredAt = Instant.now(),
            path = "build",
            previousPath = "/workspace"
        )

        assertHasTypedField(DirEntered::class.java, "path")
        assertHasTypedField(DirEntered::class.java, "previousPath")

        val pathField = DirEntered::class.java.getDeclaredField("path")
        assertEquals(String::class.java, pathField.type)
    }

    @Test
    fun `DirExited has typed carrier fields no MapStringString`() {
        val event = DirExited(
            eventId = "test-12",
            runId = "run-1",
            sequence = 12L,
            occurredAt = Instant.now(),
            path = "build",
            restoredTo = "/workspace"
        )

        assertHasTypedField(DirExited::class.java, "path")
        assertHasTypedField(DirExited::class.java, "restoredTo")
    }

    // ==========================================================================
    // Composite: All 12 NEW ML-R9 events use typed carriers
    // ==========================================================================

    @Test
    fun `all 12 new ML-R9 events have no MapStringString fields`() {
        val eventClasses = listOf(
            DirEntered::class.java,
            DirExited::class.java,
            DirDeleted::class.java,
            WsCleaned::class.java,
            CatchErrorTriggered::class.java,
            StageMarkedUnstable::class.java,
            WorkflowLoaded::class.java,
            WaitUntilPolled::class.java,
            WaitUntilCompleted::class.java,
            MilestoneReached::class.java,
            MilestoneAborted::class.java,
            TimeoutTriggered::class.java
        )

        val failures = mutableListOf<String>()

        for (eventClass in eventClasses) {
            try {
                assertNoMapStringStringFields(eventClass, eventClass.simpleName)
            } catch (e: AssertionError) {
                failures.add(e.message ?: "unknown error")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Event payload opacity violations:\n${failures.joinToString("\n")}"
            )
        }
    }

    /**
     * FIL-ALL-002: DirDeleted and WsCleaned use sha256 typed carrier (not Map).
     */
    @Test
    fun `DirDeleted and WsCleaned use sha256 typed carrier`() {
        // SHA-256 hex strings are typed as String, not Map
        val dirDeleted = DirDeleted(
            eventId = "dd-1",
            runId = "r1",
            sequence = 1L,
            occurredAt = Instant.now(),
            path = "build",
            deletedCount = 5,
            sha256 = "deadbeef1234"
        )

        val wsCleaned = WsCleaned(
            eventId = "wc-1",
            runId = "r1",
            sequence = 2L,
            occurredAt = Instant.now(),
            deletedFiles = 10,
            deletedDirs = 2,
            patterns = emptyList(),
            sha256 = "cafebabe5678"
        )

        assertNotNull(dirDeleted.sha256)
        assertNotNull(wsCleaned.sha256)

        // sha256 is String, not a Map
        val sha256Field = DirDeleted::class.java.getDeclaredField("sha256")
        assertEquals(String::class.java, sha256Field.type)
    }
}
