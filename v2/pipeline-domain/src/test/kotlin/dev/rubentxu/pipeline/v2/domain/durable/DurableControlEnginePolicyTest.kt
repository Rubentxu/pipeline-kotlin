package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.AdvanceAfterFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.CloseSuccessFromChild
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.RejectDivergence
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ResumeAttempt
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseFailure
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseSuccess
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ReuseUnstable
import dev.rubentxu.pipeline.v2.domain.durable.RetryReconciliationDecision.ScheduleAttempt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DurableControlEnginePolicyTest {

    private fun directiveFor(decision: RetryReconciliationDecision, maxAttempts: Int = 3) =
        DefaultDurableControlEnginePolicy.directiveFor(decision, maxAttempts)

    @Nested
    inner class Translation {

        @Test
        fun `ScheduleAttempt within budget launches a fresh child`() {
            assertEquals(
                EngineDirective.LaunchChild(attempt = 2, fresh = true),
                directiveFor(ScheduleAttempt(2)),
            )
        }

        @Test
        fun `ResumeAttempt within budget resumes instead of launching fresh`() {
            assertEquals(
                EngineDirective.LaunchChild(attempt = 2, fresh = false),
                directiveFor(ResumeAttempt(2)),
            )
        }

        @Test
        fun `fresh distinguishes schedule from resume`() {
            val scheduled = directiveFor(ScheduleAttempt(1)) as EngineDirective.LaunchChild
            val resumed = directiveFor(ResumeAttempt(1)) as EngineDirective.LaunchChild
            assertTrue(scheduled.fresh, "a scheduled attempt must launch a new child")
            assertFalse(resumed.fresh, "a resumed attempt must NOT launch a new child")
        }

        @Test
        fun `CloseSuccessFromChild closes as success`() {
            assertEquals(
                EngineDirective.CloseTerminal(success = true, attempt = 2),
                directiveFor(CloseSuccessFromChild(2)),
            )
        }

        @Test
        fun `ReuseSuccess closes as success`() {
            assertEquals(
                EngineDirective.CloseTerminal(success = true, attempt = 4),
                directiveFor(ReuseSuccess(4)),
            )
        }

        @Test
        fun `ReuseFailure closes as failure`() {
            assertEquals(
                EngineDirective.CloseTerminal(success = false, attempt = 4),
                directiveFor(ReuseFailure(4)),
            )
        }

        @Test
        fun `ReuseUnstable closes as unstable`() {
            assertEquals(
                EngineDirective.CloseUnstableTerminal(attempt = 4),
                directiveFor(ReuseUnstable(4)),
            )
        }

        @Test
        fun `AdvanceAfterFailure advances to a fresh attempt`() {
            assertEquals(
                EngineDirective.LaunchChild(attempt = 3, fresh = true),
                directiveFor(AdvanceAfterFailure(from = 2, to = 3)),
            )
        }

        @Test
        fun `RejectDivergence passes its reason through verbatim`() {
            val reason = "operation op-7 diverged from the control row"
            val directive = directiveFor(RejectDivergence(operationId = "op-7", reason = reason))
            assertEquals(EngineDirective.FailClosed(reason = reason), directive)
        }
    }

    @Nested
    inner class Budget {

        @Test
        fun `the budget boundary is inclusive`() {
            assertInstanceOf<EngineDirective.LaunchChild>(
                directiveFor(ScheduleAttempt(3), maxAttempts = 3),
            )
        }

        @Test
        fun `one past the budget fails closed`() {
            assertInstanceOf<EngineDirective.FailClosed>(
                directiveFor(ScheduleAttempt(4), maxAttempts = 3),
            )
        }

        @Test
        fun `resume beyond the budget fails closed`() {
            assertInstanceOf<EngineDirective.FailClosed>(
                directiveFor(ResumeAttempt(4), maxAttempts = 3),
            )
        }

        @Test
        fun `advance beyond the budget fails closed`() {
            assertInstanceOf<EngineDirective.FailClosed>(
                directiveFor(AdvanceAfterFailure(from = 3, to = 4), maxAttempts = 3),
            )
        }

        @Test
        fun `attempt zero is below the range and fails closed`() {
            assertInstanceOf<EngineDirective.FailClosed>(directiveFor(ScheduleAttempt(0)))
            assertInstanceOf<EngineDirective.FailClosed>(directiveFor(ResumeAttempt(0)))
        }

        @Test
        fun `the failure reason names the operation, the attempt and the budget`() {
            val reason = (
                directiveFor(ScheduleAttempt(7), maxAttempts = 3) as EngineDirective.FailClosed
                ).reason
            assertEquals("cannot schedule attempt=7 beyond maxAttempts=3", reason)
        }

        @Test
        fun `resume and advance carry their own operation name in the reason`() {
            val resume = (directiveFor(ResumeAttempt(9), maxAttempts = 3) as EngineDirective.FailClosed).reason
            val advance = (
                directiveFor(AdvanceAfterFailure(from = 1, to = 9), maxAttempts = 3)
                    as EngineDirective.FailClosed
                ).reason
            assertEquals("cannot resume attempt=9 beyond maxAttempts=3", resume)
            assertEquals(
                "AdvanceAfterFailure target 9 exceeds maxAttempts=3",
                advance,
            )
        }
    }

    @Nested
    inner class DegenerateBudget {

        @Test
        fun `a zero budget launches nothing`() {
            assertInstanceOf<EngineDirective.FailClosed>(directiveFor(ScheduleAttempt(1), maxAttempts = 0))
            assertInstanceOf<EngineDirective.FailClosed>(directiveFor(ResumeAttempt(1), maxAttempts = 0))
            assertInstanceOf<EngineDirective.FailClosed>(
                directiveFor(AdvanceAfterFailure(from = 1, to = 1), maxAttempts = 0),
            )
        }

        @Test
        fun `a negative budget launches nothing`() {
            assertInstanceOf<EngineDirective.FailClosed>(directiveFor(ScheduleAttempt(1), maxAttempts = -1))
        }

        @Test
        fun `terminal closures ignore the budget entirely`() {
            assertEquals(
                EngineDirective.CloseTerminal(success = true, attempt = 9),
                directiveFor(ReuseSuccess(9), maxAttempts = 0),
            )
            assertEquals(
                EngineDirective.CloseTerminal(success = false, attempt = 9),
                directiveFor(ReuseFailure(9), maxAttempts = 0),
            )
            assertEquals(
                EngineDirective.CloseUnstableTerminal(attempt = 9),
                directiveFor(ReuseUnstable(9), maxAttempts = 0),
            )
        }
    }

    @Nested
    inner class UnstableIsNotFailure {

        @Test
        fun `an unstable close is not a failure close`() {
            val unstable = directiveFor(ReuseUnstable(1))
            assertFalse(
                unstable is EngineDirective.CloseTerminal,
                "UNSTABLE must not be collapsed into CloseTerminal(success = false)",
            )
        }

        @Test
        fun `an unstable close and a failure close are distinguishable`() {
            val unstable = directiveFor(ReuseUnstable(1))
            val failure = directiveFor(ReuseFailure(1))
            assertTrue(unstable != failure, "UNSTABLE and FAILED must not render identically")
        }
    }
}
