package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.events.DomainEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * F-ARCH-L7-005: DomainEvent sealed hierarchy exhaustivity.
 *
 * Architecture test that enforces DomainEvent sealed hierarchy is complete.
 *
 * The sealed hierarchy must contain exactly 44 variants:
 * - 23 existing (ML-R1 through ML-R6)
 * - 4 new for ML-R7 (FileWritten, FileRead, ArtifactArchived, ArtifactArchiveFailed)
 * - 6 new for ML-R9 T-06 (DirEntered, DirExited, DirDeleted, WsCleaned, CatchErrorTriggered, StageMarkedUnstable)
 * - 3 new for ML-R9 T-07 (WorkflowLoaded, WaitUntilPolled, WaitUntilCompleted)
 * - 2 new for ML-R9 T-07 (PwdResolved, UnixDetected)
 * - 2 new for ML-R9 T-09 (MilestoneReached, MilestoneAborted)
 * - 1 new for ML-R9 T-10 (TimeoutTriggered)
 * - 1 new for S2.5.7 / B1.2c3 (StepAdmissionObserved) — LB-01 spine consolidation, WU-1
 * NOTE: ArtifactEntry is a nested data class, not a standalone DomainEvent
 *
 * This CLOSES the DomainEvent exhaustivity invariant from ADR-0046 §D2.
 *
 * RED: AssertionError (hierarchy count != 44)
 * GREEN: After S2.5.7 WU-1 addition, hierarchy count == 44
 */
class FArchL7DomainEventExhaustivityTest {

    /**
     * Verifies DomainEvent sealed hierarchy contains exactly 51 variants.
     *
     * Expected variants (23 existing + 4 ML-R7 + 6 ML-R9 T-06 + 3 ML-R9 T-07 + 2 ML-R9 T-07 + 2 ML-R9 T-09 + 1 ML-R9 T-10 + 2 ML-R9 T-08):
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
     * 37. PwdResolved (ML-R9 T-07 — utility-step pwd family)
     * 38. UnixDetected (ML-R9 T-07 — utility-step isUnix family)
     * 39. MilestoneReached (ML-R9 T-09)
     * 40. MilestoneAborted (ML-R9 T-09)
     * 41. TimeoutTriggered (ML-R9 T-10)
     * 42. TimestampsEntered (ML-R9 T-08)
     * 43. TimestampsExited (ML-R9 T-08)
     * 44. StepAdmissionObserved (S2.5.7 / B1.2c3 — LB-01 spine consolidation, WU-1)
     * 45. FileExistsChecked (WU-LPR-104 — core.fileExists observability)
     * 46. StashCreated (WU-LPR-089 — core.stash durable cross-stage data movement)
     * 47. StashRestored (WU-LPR-089 — core.unstash durable cross-stage data movement)
     * 48. StashFailed (WU-LPR-089 — core.stash/core.unstash typed failure observability)
     * 49. HtmlReportPublished (WU-LPR-090 phase-a — core.publishHtml observability)
     * 50. HtmlReportSkipped (WU-LPR-090 phase-a — core.publishHtml observability)
     * 51. HtmlReportFailed (WU-LPR-090 phase-a — core.publishHtml typed failure observability)
     * 52. DirectiveAdmitted (S1-C — directive seam admission observability)
     * 53. DirectiveDenied (S1-C — directive seam fail-closed denial observability)
     * 54. StageSkipped (S2-A — a stage whose gate verdict was a decided negative)
     * 55. PostConditionSelected (S2-B — the post block selection decision)
     * 56. GateEvaluated (S2-C — the composed gate verdict, emitted even when satisfied)
     * 57. LockRequested (RP6-A / WU-091 §6 — core.lock lifecycle observability)
     * 58. LockAcquired (RP6-A / WU-091 §6)
     * 59. LockReleased (RP6-A / WU-091 §6 — release at every terminal)
     * 60. LockSkipped (RP6-A / WU-091 §6 — skipIfLocked with the resource held)
     * 61. LockAcquireFailed (RP6-A / WU-091 §6 — timeout or cancellation)
     * 62. InputRequested (RP6-B / WU-092 §6 — a question reached the request file)
     * 63. InputProceed (RP6-B / WU-092 §6 — affirmative answer; the body ran)
     * 64. InputAborted (RP6-B / WU-092 §6 — negative answer; the body was skipped)
     * 65. InputDenied (RP6-B / WU-092 §6 — timed out, cancelled or unanswerable)
     * 66. HttpRequestStarted (RP6-C / WU-093 §5 — a request left, before any answer)
     * 67. HttpResponseReceived (RP6-C / WU-093 §5 — a response arrived, any status)
     * 68. HttpStatusRejected (RP6-C / WU-093 §5 — status outside what the author listed)
     * 69. HttpRequestFailed (RP6-C / WU-093 §5 — no response at all)
     * 70. ExecutionTargetResolved (RP7-SEM S3.1 — a target was granted or refused)
     * 71. PluginEventEmitted (P3 slice 2 — a plugin contributed an event through the registry)
     */
    @Test
    fun `domain_event_sealed_hierarchy_has_71_variants`() {
        val sealedSubclasses = DomainEvent::class.sealedSubclasses

        val actualCount = sealedSubclasses.size
        // 53 through S1-C, StageSkipped (S2-A), PostConditionSelected (S2-B),
        // GateEvaluated (S2-C). S2-C missed this pin: it moved the sibling pin in
        // DomainEventRoundTripTest but not this one, so the exhaustivity fitness
        // stayed red until the L5 closure gate ran on the committed tree.
        // +5 through RP6-A / WU-091 §6: the core.lock lifecycle, added when core.lock
        // reached production routing (every step MUST emit its own typed events).
        // +4 through RP6-B / WU-092 §6: the core.input lifecycle, added with the
        // four sealed events in G1. Same lesson as S2-C, one tier down: the four
        // variants shipped in 523ffb0a and this pin was only reached by the G3 gate,
        // because the gate that ran for G1 never included this module.
        // +4 through RP6-C / WU-093 §5: the http.request lifecycle, added with
        // the four sealed events in G3. Third recurrence of this exact miss, so the
        // rule is now stated rather than remembered: ANY sealed hierarchy growth MUST
        // move this pin in the same commit that adds the variant. A pin that is
        // renamed but not re-valued is a false green waiting for the next gate.
        // +1 through RP7-SEM S3.1: ExecutionTargetResolved, the event that makes
        // `agent` observable. This pin is the FOURTH copy of this count in the repo
        // (the DomainEventRoundTripTest sibling in pipeline-events, this one in
        // pipeline-architecture-tests, and the numbered KDoc list above all restate
        // it), which is itself the defect: a hand-maintained integer in four places
        // is a count that will be wrong again. It is left duplicated deliberately
        // rather than "fixed" in a semantic-change commit — collapsing the copies
        // is a governance WU of its own, and quietly deleting a pin here would
        // trade a loud, dated failure for a silent one.
        // +1 through P3 slice 2: PluginEventEmitted, the single closed carrier for events a
        // plugin contributes through the open registry. FIFTH copy of this count missed, and the
        // first one caught by a gate rather than by the surgical run that shipped the variant —
        // because the surgical rung ran only :pipeline-events-store:test and this fitness lives
        // in pipeline-architecture-tests. So the rule gains its operational half: the ladder is
        // not "run the module you touched", it is "run the module you touched AND the fitness
        // module", and the fitness module is a separate Gradle project that no per-module filter
        // reaches.
        val expectedCount = 71

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
     * S1-C — the directive seam emits exactly two closed observability
     * variants; anything else would let policy behaviour leak into the
     * event vocabulary.
     */
    @Test
    fun `domain_event_has_directive_seam_variants`() {
        val expected = listOf("DirectiveAdmitted", "DirectiveDenied")

        val actualNames = DomainEvent::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        val missing = expected.filter { it !in actualNames }

        assertTrue(
            missing.isEmpty(),
            "S1-C directive seam variants missing: $missing (found ${actualNames.filter { it.startsWith("Directive") }})",
        )
    }

    /**
     * Verifies all 44 variants have the required DomainEvent interface fields.
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
