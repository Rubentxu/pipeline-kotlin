package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.events.DomainEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * F-ARCH-L7-005: DomainEvent sealed hierarchy exhaustivity.
 *
 * Architecture test that enforces DomainEvent sealed hierarchy is complete.
 *
 * The sealed hierarchy must contain exactly 39 variants:
 * - 23 existing (ML-R1 through ML-R6)
 * - 4 new for ML-R7 (FileWritten, FileRead, ArtifactArchived, ArtifactArchiveFailed)
 * - 6 new for ML-R9 T-06 (DirEntered, DirExited, DirDeleted, WsCleaned, CatchErrorTriggered, StageMarkedUnstable)
 * - 3 new for ML-R9 T-07 (WorkflowLoaded, WaitUntilPolled, WaitUntilCompleted)
 * - 2 new for ML-R9 T-09 (MilestoneReached, MilestoneAborted)
 * - 1 new for ML-R9 T-10 (TimeoutTriggered)
 * NOTE: ArtifactEntry is a nested data class, not a standalone DomainEvent
 *
 * This CLOSES the DomainEvent exhaustivity invariant from ADR-0046 §D2.
 *
 * RED: AssertionError (hierarchy count != 39)
 * GREEN: After T-10, hierarchy count == 39
 */
class FArchL7DomainEventExhaustivityTest {

    /**
     * Verifies DomainEvent sealed hierarchy contains exactly 39 variants.
     *
     * Expected variants (23 existing + 4 ML-R7 + 6 ML-R9 T-06 + 3 ML-R9 T-07 + 2 ML-R9 T-09 + 1 ML-R9 T-10):
     * 1. RunStarted
     * 2. CompilationStarted
     * 3. CompilationFinished
     * 4. RunFinished
     * 5. StageStarted
     * 6. StageFinished
     * 7. StepStarted
     * 8. StepFinished
     * 9. AgentResolved
     * 10. ParallelBranchStarted
     * 11. ParallelBranchFinished
     * 12. RetryAttemptStarted
     * 13. RetryAttemptFinished
     * 14. TimeoutScheduled
     * 15. StepFailed
     * 16. EchoOutputCaptured
     * 17. CredentialBound
     * 18. CredentialUsed
     * 19. CredentialUnbound
     * 20. GitCheckoutStarted
     * 21. GitCheckoutCompleted
     * 22. GitCheckoutFailed
     * 23. GitPollChanged
     * 24. FileWritten (ML-R7)
     * 25. FileRead (ML-R7)
     * 26. ArtifactArchived (ML-R7)
     * 27. ArtifactArchiveFailed (ML-R7)
     * 28. DirEntered (ML-R9 T-06)
     * 29. DirExited (ML-R9 T-06)
     * 30. DirDeleted (ML-R9 T-05)
     * 31. WsCleaned (ML-R9 T-05)
     * 32. CatchErrorTriggered (ML-R9 T-06)
     * 33. StageMarkedUnstable (ML-R9 T-06)
     * 34. WorkflowLoaded (ML-R9 T-07)
     * 35. WaitUntilPolled (ML-R9 T-07)
     * 36. WaitUntilCompleted (ML-R9 T-07)
     * 37. MilestoneReached (ML-R9 T-09)
     * 38. MilestoneAborted (ML-R9 T-09)
     * 39. TimeoutTriggered (ML-R9 T-10)
     */
    @Test
    fun `domain_event_sealed_hierarchy_has_39_variants`() {
        val sealedSubclasses = DomainEvent::class.sealedSubclasses

        val actualCount = sealedSubclasses.size
        val expectedCount = 39

        assertEquals(
            expectedCount,
            actualCount,
            "DomainEvent sealed hierarchy must have exactly $expectedCount variants. " +
            "Found $actualCount: ${sealedSubclasses.map { it.simpleName }}"
        )
    }

    /**
     * Verifies the 4 new ML-R7 event variants exist.
     */
    @Test
    fun `domain_event_has_ml_r7_variants`() {
        val expectedNewVariants = listOf(
            "FileWritten",
            "FileRead",
            "ArtifactArchived",
            "ArtifactArchiveFailed"
        )

        val sealedSubclasses = DomainEvent::class.sealedSubclasses
        val actualNames = sealedSubclasses.map { it.simpleName }.toSet()

        val failures = mutableListOf<String>()

        for (variant in expectedNewVariants) {
            if (variant !in actualNames) {
                failures.add("$variant: not found in sealed hierarchy")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "ML-R7 DomainEvent variants missing:\n${failures.joinToString("\n")}"
            )
        }
    }

    /**
     * Verifies all 36 variants have the required DomainEvent interface fields.
     */
    @Test
    fun `all_domain_event_variants_implement_interface_fields`() {
        val sealedSubclasses = DomainEvent::class.sealedSubclasses

        val requiredMethods = listOf(
            "eventId",
            "runId",
            "sequence",
            "kind",
            "occurredAt"
        )

        val failures = mutableListOf<String>()

        for (subclass in sealedSubclasses) {
            try {
                for (method in requiredMethods) {
                    val found = subclass.members.any { it.name == method }
                    if (!found) {
                        failures.add("${subclass.simpleName}: missing property $method")
                    }
                }
            } catch (e: Exception) {
                failures.add("${subclass.simpleName}: ${e.message}")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "DomainEvent variants missing required properties:\n${failures.joinToString("\n")}"
            )
        }
    }
}
