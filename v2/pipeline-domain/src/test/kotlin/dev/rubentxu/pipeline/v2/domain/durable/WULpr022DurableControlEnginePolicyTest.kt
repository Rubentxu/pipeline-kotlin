package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.AdvanceAfterFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ResumeAttempt
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WU-LPR-022 — Pure engine policy tests.
 *
 * The policy translates a [RetryReconciliationDecision] + a decoded
 * attempt budget into a typed [EngineDirective]. The contract is closed:
 * every decision has exactly one interpretation against every legal budget,
 * and decision/budget incoherence surfaces as [EngineDirective.FailClosed]
 * (the engine is fail-closed — the runner surfaces it as a typed ENGINE
 * failure and launches ZERO children).
 *
 * These tests pin the contract. A future migration that has the canonical
 * coordinator consume the engine policy (instead of inlining the retry
 * loop) MUST preserve the directive mapping verbatim — divergence from the
 * golden would change the journal sequence and break replay determinism.
 */
class WULpr022DurableControlEnginePolicyTest {

    private val policy: DurableControlEnginePolicy = DefaultDurableControlEnginePolicy
    private val maxAttempts = 3

    @Nested
    @DisplayName("ScheduleAttempt")
    inner class ScheduleAttemptCases {

        @Test
        fun `ScheduleAttempt(1) on fresh dispatch yields LaunchChild(1, fresh=true)`() {
            val directive = policy.directiveFor(ScheduleAttempt(attempt = 1), maxAttempts)
            assertEquals(EngineDirective.LaunchChild(attempt = 1, fresh = true), directive)
        }

        @Test
        fun `ScheduleAttempt(2) yields LaunchChild(2, fresh=true)`() {
            val directive = policy.directiveFor(ScheduleAttempt(attempt = 2), maxAttempts)
            assertEquals(EngineDirective.LaunchChild(attempt = 2, fresh = true), directive)
        }

        @Test
        fun `ScheduleAttempt(3) at budget edge yields LaunchChild(3, fresh=true)`() {
            val directive = policy.directiveFor(ScheduleAttempt(attempt = 3), maxAttempts)
            assertEquals(EngineDirective.LaunchChild(attempt = 3, fresh = true), directive)
        }

        @Test
        fun `ScheduleAttempt(4) beyond budget yields FailClosed`() {
            val directive = policy.directiveFor(ScheduleAttempt(attempt = 4), maxAttempts)
            assertTrue(directive is EngineDirective.FailClosed)
            val reason = (directive as EngineDirective.FailClosed).reason
            assertTrue(reason.contains("attempt=4"), "reason MUST name the offending attempt: $reason")
            assertTrue(reason.contains("maxAttempts=3"), "reason MUST name the budget: $reason")
        }

        @Test
        fun `ScheduleAttempt(0) below 1 yields FailClosed`() {
            val directive = policy.directiveFor(ScheduleAttempt(attempt = 0), maxAttempts)
            assertTrue(directive is EngineDirective.FailClosed)
        }

        @Test
        fun `ScheduleAttempt(-1) below 1 yields FailClosed`() {
            val directive = policy.directiveFor(ScheduleAttempt(attempt = -1), maxAttempts)
            assertTrue(directive is EngineDirective.FailClosed)
        }
    }

    @Nested
    @DisplayName("ResumeAttempt")
    inner class ResumeAttemptCases {

        @Test
        fun `ResumeAttempt(1) yields LaunchChild(1, fresh=false)`() {
            val directive = policy.directiveFor(ResumeAttempt(attempt = 1), maxAttempts)
            assertEquals(EngineDirective.LaunchChild(attempt = 1, fresh = false), directive)
        }

        @Test
        fun `ResumeAttempt(3) at budget edge yields LaunchChild(3, fresh=false)`() {
            val directive = policy.directiveFor(ResumeAttempt(attempt = 3), maxAttempts)
            assertEquals(EngineDirective.LaunchChild(attempt = 3, fresh = false), directive)
        }

        @Test
        fun `ResumeAttempt(4) beyond budget yields FailClosed`() {
            val directive = policy.directiveFor(ResumeAttempt(attempt = 4), maxAttempts)
            assertTrue(directive is EngineDirective.FailClosed)
        }
    }

    @Nested
    @DisplayName("CloseSuccessFromChild")
    inner class CloseSuccessFromChildCases {

        @Test
        fun `CloseSuccessFromChild(1) yields CloseTerminal(success=true, 1)`() {
            val directive = policy.directiveFor(CloseSuccessFromChild(attempt = 1), maxAttempts)
            assertEquals(
                EngineDirective.CloseTerminal(success = true, attempt = 1),
                directive,
            )
        }

        @Test
        fun `CloseSuccessFromChild ignores maxAttempts (attempt can be any value)`() {
            val directive = policy.directiveFor(CloseSuccessFromChild(attempt = 7), maxAttempts)
            assertEquals(
                EngineDirective.CloseTerminal(success = true, attempt = 7),
                directive,
            )
        }
    }

    @Nested
    @DisplayName("AdvanceAfterFailure")
    inner class AdvanceAfterFailureCases {

        @Test
        fun `AdvanceAfterFailure(1, 2) yields LaunchChild(2, fresh=true)`() {
            val directive = policy.directiveFor(
                AdvanceAfterFailure(from = 1, to = 2),
                maxAttempts,
            )
            assertEquals(EngineDirective.LaunchChild(attempt = 2, fresh = true), directive)
        }

        @Test
        fun `AdvanceAfterFailure(2, 3) at budget edge yields LaunchChild(3, fresh=true)`() {
            val directive = policy.directiveFor(
                AdvanceAfterFailure(from = 2, to = 3),
                maxAttempts,
            )
            assertEquals(EngineDirective.LaunchChild(attempt = 3, fresh = true), directive)
        }

        @Test
        fun `AdvanceAfterFailure(3, 4) beyond budget yields FailClosed`() {
            val directive = policy.directiveFor(
                AdvanceAfterFailure(from = 3, to = 4),
                maxAttempts,
            )
            assertTrue(directive is EngineDirective.FailClosed)
            val reason = (directive as EngineDirective.FailClosed).reason
            assertTrue(reason.contains("4"), "reason MUST name the offending target: $reason")
        }

        @Test
        fun `AdvanceAfterFailure(1, 0) below 1 yields FailClosed`() {
            val directive = policy.directiveFor(
                AdvanceAfterFailure(from = 1, to = 0),
                maxAttempts,
            )
            assertTrue(directive is EngineDirective.FailClosed)
        }
    }

    @Nested
    @DisplayName("ReuseSuccess / ReuseFailure")
    inner class ReuseCases {

        @Test
        fun `ReuseSuccess(2) yields CloseTerminal(success=true, 2)`() {
            val directive = policy.directiveFor(ReuseSuccess(attempt = 2), maxAttempts)
            assertEquals(EngineDirective.CloseTerminal(success = true, attempt = 2), directive)
        }

        @Test
        fun `ReuseFailure(3) yields CloseTerminal(success=false, 3)`() {
            val directive = policy.directiveFor(ReuseFailure(attempt = 3), maxAttempts)
            assertEquals(EngineDirective.CloseTerminal(success = false, attempt = 3), directive)
        }

        @Test
        fun `ReuseSuccess ignores maxAttempts (already terminal)`() {
            val directive = policy.directiveFor(ReuseSuccess(attempt = 99), maxAttempts)
            assertEquals(EngineDirective.CloseTerminal(success = true, attempt = 99), directive)
        }
    }

    @Nested
    @DisplayName("RejectDivergence")
    inner class RejectDivergenceCases {

        @Test
        fun `RejectDivergence carries reason verbatim into FailClosed`() {
            val directive = policy.directiveFor(
                RejectDivergence(operationId = "op-1", reason = "fingerprint mismatch at attempt 2"),
                maxAttempts,
            )
            assertTrue(directive is EngineDirective.FailClosed)
            assertEquals(
                "fingerprint mismatch at attempt 2",
                (directive as EngineDirective.FailClosed).reason,
            )
        }
    }

    @Nested
    @DisplayName("Exhaustiveness")
    inner class Exhaustiveness {

        @Test
        fun `engine policy is total over the closed decision ADT`() {
            // Pin: every decision variant produces a directive, never throws.
            val decisions: List<RetryReconciliationDecision> = listOf(
                ScheduleAttempt(1),
                ResumeAttempt(1),
                CloseSuccessFromChild(1),
                AdvanceAfterFailure(1, 2),
                ReuseSuccess(1),
                ReuseFailure(1),
                RejectDivergence("op", "x"),
            )
            decisions.forEach { d ->
                val directive = policy.directiveFor(d, maxAttempts)
                assertTrue(
                    directive is EngineDirective.LaunchChild ||
                        directive is EngineDirective.CloseTerminal ||
                        directive is EngineDirective.FailClosed,
                    "policy MUST map every decision to a typed directive; got $directive for $d",
                )
            }
        }
    }
}
