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
 * The sealed hierarchy must contain exactly 29 variants:
 * - 23 existing (ML-R1 through ML-R6)
 * - 4 new for ML-R7 (FileWritten, FileRead, ArtifactArchived, ArtifactArchiveFailed)
 * - 2 new for ML-R9 (DirEntered, DirExited)
 * NOTE: ArtifactEntry is a nested data class, not a standalone DomainEvent
 *
 * This CLOSES the DomainEvent exhaustivity invariant from ADR-0046 §D2.
 *
 * RED: AssertionError (hierarchy count != 29)
 * GREEN: After T-07, hierarchy count == 29
 */
class FArchL7DomainEventExhaustivityTest {

    /**
     * Verifies DomainEvent sealed hierarchy contains exactly 29 variants.
     *
     * Expected variants (23 existing + 4 ML-R7 + 2 ML-R9):
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
     * 24. FileWritten (NEW ML-R7)
     * 25. FileRead (NEW ML-R7)
     * 26. ArtifactArchived (NEW ML-R7)
     * 27. ArtifactArchiveFailed (NEW ML-R7)
     * 28. DirEntered (NEW ML-R9)
     * 29. DirExited (NEW ML-R9)
     */
    @Test
    fun `domain_event_sealed_hierarchy_has_29_variants`() {
        val sealedSubclasses = DomainEvent::class.sealedSubclasses

        val actualCount = sealedSubclasses.size
        val expectedCount = 29

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
     * Verifies all 27 variants have the required DomainEvent interface fields.
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
