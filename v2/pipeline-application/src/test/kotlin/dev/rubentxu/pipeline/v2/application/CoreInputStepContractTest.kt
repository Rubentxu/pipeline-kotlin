package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-092 / RP6-B G1 — the contract of `core.input`, with teeth.
 *
 * Every row here exists because the corresponding mistake is easy to make and
 * hard to see: an abort that still runs the body, a blank question that becomes a
 * denial instead of a declaration error, a budget that silently stops bounding
 * the wait, or a journal that cannot tell "a human refused" from "nobody
 * answered".
 */
class CoreInputStepContractTest {

    private val definition = CoreInputStep.definition

    // ------------------------------------------------------- surface

    @Test
    fun `the step is core input and nothing else`() {
        assertEquals("core.input", definition.contract.key.value)
    }

    @Test
    fun `the input surface carries exactly the Jenkins parameters`() {
        // Reflection is not on this module's test classpath, so the surface is
        // pinned by construction here and by the constructor pin in
        // PipelineDslSealedHierarchyTest (G3), which does have it.
        val full = CoreInputInput(
            message = "deploy to prod?",
            ok = "Ship it",
            submitter = "release-team",
            id = "deploy-42",
            timeoutSeconds = 900,
        )
        assertEquals("deploy to prod?", full.message)
        assertEquals("Ship it", full.ok)
        assertEquals("release-team", full.submitter)
        assertEquals("deploy-42", full.id)
        assertEquals(900, full.timeoutSeconds)
    }

    @Test
    fun `the declaration defaults match the spec`() {
        val input = CoreInputInput(message = "ship it?")
        assertEquals("Proceed", input.ok)
        assertNull(input.submitter)
        assertNull(input.id)
        assertNull(input.timeoutSeconds)
    }

    // ------------------------------------------------------- capability coherence

    @Test
    fun `all four halves of the declaration are present`() {
        assertEquals(
            setOf(
                INPUT_DECISIONS_CAPABILITY,
                BODY_CONTINUATION_CAPABILITY,
                EXECUTION_BUDGET_CAPABILITY,
                EVENT_SINK_CAPABILITY,
            ),
            definition.contract.requiredCapabilities,
            "the port that asks, the continuation a Proceed runs, the budget that bounds the " +
                "wait and the sink that makes the decision observable",
        )
    }

    @Test
    fun `the body policy resolves to HANDLER_CONTINUATION and Sequential`() {
        val resolution = resolveBodyExecutionPolicy(definition, BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING)
        assertTrue(
            resolution is BodyPolicyResolution.Resolved,
            "core.input must be admissible by the canonical engine, got $resolution",
        )
        assertEquals(BodyExecutionPolicy.Sequential, (resolution as BodyPolicyResolution.Resolved).policy)
        assertEquals(
            BodyExecutionOwner.HANDLER_CONTINUATION,
            definition.contract.descriptor.body.declared?.execution?.owner,
            "CANONICAL_ENGINE would run the body unconditionally, which would make an abort a no-op",
        )
    }

    // ------------------------------------------------------- the pure decision

    @Test
    fun `a blank question is a declaration error, not a denial`() {
        val resolution = inputIntentOf(CoreInputInput(message = "   "), ExecutionBudget(null))
        assertEquals(
            InputIntentResolution.Rejected(InputInputError.BlankMessage),
            resolution,
            "an empty message asks an operator to approve nothing; it is a bad declaration",
        )
    }

    @Test
    fun `a blank affirmative label is a declaration error`() {
        val resolution = inputIntentOf(CoreInputInput(message = "ship?", ok = ""), ExecutionBudget(null))
        assertEquals(InputIntentResolution.Rejected(InputInputError.BlankOk), resolution)
    }

    @Test
    fun `a negative timeout is a declaration error`() {
        val resolution = inputIntentOf(
            CoreInputInput(message = "ship?", timeoutSeconds = -1),
            ExecutionBudget(null),
        )
        assertEquals(InputIntentResolution.Rejected(InputInputError.NegativeTimeout(-1)), resolution)
    }

    @Test
    fun `the wait is the tighter of the author timeout and the scope budget`() {
        // author 1s, budget 5s -> 1s
        assertEquals(
            InputIntentResolution.Resolved(1_000L),
            inputIntentOf(CoreInputInput(message = "?", timeoutSeconds = 1), ExecutionBudget(5_000L)),
        )
        // author 5s, budget 1s -> 1s
        assertEquals(
            InputIntentResolution.Resolved(1_000L),
            inputIntentOf(CoreInputInput(message = "?", timeoutSeconds = 5), ExecutionBudget(1_000L)),
        )
        // neither -> unbounded
        assertEquals(
            InputIntentResolution.Resolved(null),
            inputIntentOf(CoreInputInput(message = "?"), ExecutionBudget(null)),
        )
        // author only
        assertEquals(
            InputIntentResolution.Resolved(2_000L),
            inputIntentOf(CoreInputInput(message = "?", timeoutSeconds = 2), ExecutionBudget(null)),
        )
    }

    @Test
    fun `a rejected declaration carries a diagnostic and no wait`() {
        // The port receives a wait only for a Resolved intent; this pins that the
        // rejection path cannot smuggle a bound through, and that the diagnostic
        // an operator would read is always present.
        val rejected = inputIntentOf(CoreInputInput(message = ""), ExecutionBudget(1_000L))
        assertTrue(rejected is InputIntentResolution.Rejected)
        val error = (rejected as InputIntentResolution.Rejected).error
        assertTrue(
            error.diagnostic.isNotBlank(),
            "a rejected declaration must say why: ${error.diagnostic}",
        )
    }

    // ------------------------------------------------------- wire round-trip

    @Test
    fun `the input codec round-trips every field`() {
        val input = CoreInputInput(
            message = "deploy to prod?",
            ok = "Ship it",
            submitter = "release-team",
            id = "deploy-42",
            timeoutSeconds = 900,
        )
        assertEquals(input, CoreInputWireCodec.decode(CoreInputWireCodec.encode(input)))
    }

    @Test
    fun `the input codec round-trips a defaulted input byte-identically`() {
        // The compiler and the codec must produce the SAME bytes for a default
        // input, or the two authorities have already diverged.
        val input = CoreInputInput(message = "ship?")
        val encoded = CoreInputWireCodec.encode(input)
        assertEquals(encoded, CoreInputWireCodec.encode(CoreInputWireCodec.decode(encoded)))
        assertTrue(!encoded.value.contains("null"), "absent fields are omitted, not written as null")
    }

    @Test
    fun `a payload without a message is a typed decode failure`() {
        val failure = runCatching {
            CoreInputWireCodec.decode(EncodedStepValue("""{"ok":"Proceed"}"""))
        }.exceptionOrNull()
        assertTrue(failure is CoreInputCodecException, "got $failure")
        assertTrue(
            failure!!.message!!.contains("message"),
            "the diagnostic must name the missing field: ${failure.message}",
        )
    }

    @Test
    fun `the output codec tells a refusal from a missing answer`() {
        val refused = CoreInputOutput(
            requested = "ship?",
            decision = InputDecision.Abort("ana", "not today"),
            bodyRan = false,
        )
        val noAnswer = CoreInputOutput(
            requested = "ship?",
            decision = null,
            denial = InputDenialReason.TimedOut(1_000L),
        )
        val restoredRefusal = CoreInputOutputCodec.decode(CoreInputOutputCodec.encode(refused))
        val restoredSilence = CoreInputOutputCodec.decode(CoreInputOutputCodec.encode(noAnswer))
        assertEquals(refused, restoredRefusal)
        assertEquals(noAnswer, restoredSilence)
        assertTrue(
            restoredRefusal.denial == null && restoredRefusal.decision is InputDecision.Abort,
            "a refusal is a decision, never a denial",
        )
        assertTrue(
            restoredSilence.decision == null && restoredSilence.denial is InputDenialReason.TimedOut,
            "a timeout is a denial, never a decision",
        )
    }

    @Test
    fun `every denial case round-trips distinctly`() {
        // M-in-1 exposed why this row exists: encoding every denial under one
        // discriminant (TIMED_OUT) still round-tripped the TimedOut sample, so a
        // suite that only checks one case cannot see the lie. A closed ADT whose
        // cases are NOT distinguished on the wire is a closed ADT that has quietly
        // become an open one for whoever reads the journal.
        val cases = listOf(
            InputDenialReason.TimedOut(1_234L),
            InputDenialReason.Cancelled,
            InputDenialReason.Unanswerable("control dir is not writable"),
        )
        cases.forEach { reason ->
            val output = CoreInputOutput(requested = "ship?", denial = reason)
            val restored = CoreInputOutputCodec.decode(CoreInputOutputCodec.encode(output))
            assertEquals(reason, restored.denial, "case $reason must survive the wire")
        }
        assertEquals(3, cases.map { denialDiscriminant(it) }.toSet().size, "each case has its own discriminant")
    }

    @Test
    fun `the output codec preserves the body outcome`() {
        val output = CoreInputOutput(
            requested = "ship?",
            decision = InputDecision.Proceed("ana", null),
            bodyRan = true,
            outcome = StepOutcome.Success,
        )
        assertEquals(output, CoreInputOutputCodec.decode(CoreInputOutputCodec.encode(output)))
    }

    // ------------------------------------------------------- run outcome

    @Test
    fun `an abort fails the run and a proceed continues it`() {
        val aborted = CoreInputOutput(requested = "ship?", decision = InputDecision.Abort("ana", null))
        assertTrue(aborted.runOutcome() is StepOutcome.Failure, "an abort is the author saying stop")

        val proceeded = CoreInputOutput(
            requested = "ship?",
            decision = InputDecision.Proceed("ana", null),
            bodyRan = true,
            outcome = StepOutcome.Success,
        )
        assertEquals(StepOutcome.Success, proceeded.runOutcome())
    }

    @Test
    fun `each denial fails with its own failure kind`() {
        val timedOut = CoreInputOutput(requested = "ship?", denial = InputDenialReason.TimedOut(1L))
        assertTrue(
            (timedOut.runOutcome() as StepOutcome.Failure).failure.kind ==
                dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT,
        )
        val unanswerable = CoreInputOutput(
            requested = "ship?",
            denial = InputDenialReason.Unanswerable("no anchor"),
        )
        assertTrue(
            (unanswerable.runOutcome() as StepOutcome.Failure).failure.kind ==
                dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
        )
    }

    // ------------------------------------------------------- the answer codec

    @Test
    fun `a half-written answer is not an answer`() {
        // The law that makes a file-based mechanism safe: a truncated or
        // half-written file is a human still typing, not a refusal.
        assertNull(InputAnswerCodec.decode(""), "empty file")
        assertNull(InputAnswerCodec.decode("{"), "truncated")
        assertNull(InputAnswerCodec.decode("""{"decision":"PRO"""), "truncated value")
        assertNull(InputAnswerCodec.decode("""{"message":"go"}"""), "no decision field")
        assertNull(InputAnswerCodec.decode("""{"decision":"MAYBE"}"""), "unknown decision")
        assertNull(InputAnswerCodec.decode("not json at all"), "not json")
    }

    @Test
    fun `both answers round-trip through the answer codec`() {
        val proceed = InputDecision.Proceed("ana", "lgtm")
        val abort = InputDecision.Abort("ana", "not today")
        assertEquals(proceed, InputAnswerCodec.decode(InputAnswerCodec.encode(proceed)))
        assertEquals(abort, InputAnswerCodec.decode(InputAnswerCodec.encode(abort)))
    }
}
