package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The engine adapter that resolves a body reference into the body it names.
 *
 * The claim under test is that a reference the run does not hold open is a CONTAINED engine
 * failure inside the caller's own outcome — and not a cancellation. A cancellation says the run
 * was stopped on purpose; nothing here stopped the run, and telling the caller that it did
 * would send it looking for a deadline that never expired.
 */
class CanonicalBodyInvokerAdapterTest {

    @Test
    fun `a reference that is not open is an engine failure and not a cancellation`() {
        val adapter = CanonicalBodyInvokerAdapter()

        val outcome = adapter.invokeBlocking(BodyRef("body/absent"), BodyInvocationContext())

        val completed = assertInstanceOf<BodyOutcome.Completed>(outcome)
        val failure = assertInstanceOf<StepOutcome.Failure>(completed.outcome).failure
        assertEquals(FailureKind.ENGINE, failure.kind)
    }

    @Test
    fun `a reference that is not open says which reference was missing`() {
        val adapter = CanonicalBodyInvokerAdapter()

        val outcome = adapter.invokeBlocking(BodyRef("body/absent"), BodyInvocationContext())

        val completed = assertInstanceOf<BodyOutcome.Completed>(outcome)
        val failure = assertInstanceOf<StepOutcome.Failure>(completed.outcome).failure
        assertTrue(
            failure.message.contains("body/absent"),
            "the failure must name the reference it could not resolve: ${failure.message}",
        )
    }

    @Test
    fun `an open reference runs the body and returns its own outcome`() {
        val adapter = CanonicalBodyInvokerAdapter()
        val reference = BodyRef("body/open")
        adapter.open(reference) { StepOutcome.Success }

        val outcome = adapter.invokeBlocking(reference, BodyInvocationContext())

        val completed = assertInstanceOf<BodyOutcome.Completed>(outcome)
        assertEquals(StepOutcome.Success, completed.outcome)
    }

    @Test
    fun `the runner receives the invocation context verbatim`() {
        val adapter = CanonicalBodyInvokerAdapter()
        val reference = BodyRef("body/context")
        var received: BodyInvocationContext? = null
        adapter.open(reference) { context ->
            received = context
            StepOutcome.Success
        }

        val context = BodyInvocationContext(
            attempt = AttemptSegment(index = 2, key = PluginStepId("retry-attempt")),
        )
        adapter.invokeBlocking(reference, context)

        assertEquals(context, received)
    }

    @Test
    fun `a reference closed by the engine is no longer open`() {
        val adapter = CanonicalBodyInvokerAdapter()
        val reference = BodyRef("body/closed")
        adapter.open(reference) { StepOutcome.Success }
        assertEquals(1, adapter.openBodyCount)

        adapter.close(reference)

        assertEquals(0, adapter.openBodyCount)
        val outcome = adapter.invokeBlocking(reference, BodyInvocationContext())
        val completed = assertInstanceOf<BodyOutcome.Completed>(outcome)
        assertInstanceOf<StepOutcome.Failure>(completed.outcome)
    }

    @Test
    fun `an absent reference carries no cause of its own`() {
        val adapter = CanonicalBodyInvokerAdapter()

        val outcome = adapter.invokeBlocking(BodyRef("body/absent"), BodyInvocationContext())

        val completed = assertInstanceOf<BodyOutcome.Completed>(outcome)
        val failure = assertInstanceOf<StepOutcome.Failure>(completed.outcome).failure
        assertNull(failure.cause)
    }
}
