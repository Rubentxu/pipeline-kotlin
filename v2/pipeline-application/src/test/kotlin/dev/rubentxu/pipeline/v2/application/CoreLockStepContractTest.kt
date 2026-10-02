package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * G1 contract proof for `core.lock` (RP6-A / WU-091).
 *
 * This slice adds the Step BEHIND the registry and does not yet register it in
 * production wiring, so everything here is testable without a coordinator, a
 * backend, or a filesystem. The claim is narrow and worth stating: the contract
 * is complete, coherent and losslessly encodable, and the capability declaration
 * is load-bearing rather than decorative.
 *
 * Registration, the file backend, the DSL façade and the resume row are later
 * slices; see `docs/v2/07-uat/SPEC_WU091_LOCK.md` §7.
 */
class CoreLockStepContractTest {

    private val definition = CoreLockStep.definition

    // ------------------------------------------------------------------ identity

    @Test
    fun `the key is the one the contract names`() {
        assertEquals("core.lock", CoreLockStep.KEY.value)
    }

    // ------------------------------------------------------- capability coherence

    @Test
    fun `all three halves of the declaration are present`() {
        assertEquals(
            setOf(
                LOCK_COORDINATION_CAPABILITY,
                BODY_CONTINUATION_CAPABILITY,
                EXECUTION_LANE_CAPABILITY,
            ),
            definition.contract.requiredCapabilities,
            "lock needs the port that decides WHETHER to run the body, the bound continuation " +
                "that runs it, and the durable lane that decides WHO owns the resulting hold",
        )
    }

    @Test
    fun `the body policy resolves to HANDLER_CONTINUATION and Sequential`() {
        val resolution = resolveBodyExecutionPolicy(definition, BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING)

        assertTrue(
            resolution is BodyPolicyResolution.Resolved,
            "core.lock must be admissible by the canonical engine, got $resolution",
        )
        val policy = (resolution as BodyPolicyResolution.Resolved).policy
        assertEquals(BodyExecutionPolicy.Sequential, policy)
        assertEquals(
            BodyExecutionOwner.HANDLER_CONTINUATION,
            definition.contract.descriptor.body.declared?.execution?.owner,
            "the owner is what makes the body conditional; CANONICAL_ENGINE would run it " +
                "unconditionally and defeat skipIfLocked",
        )
    }

    /**
     * Teeth for the row above. If the owner/capability coherence check did not
     * exist, dropping the continuation capability would be invisible — and a
     * HANDLER_CONTINUATION Step without it can never reach its own body, so the
     * lock would acquire the resource and silently run nothing.
     */
    @Test
    fun `dropping the continuation capability is rejected fail-closed`() {
        val broken = object : dev.rubentxu.pipeline.v2.domain.step.StepDefinition<CoreLockInput, CoreLockOutput> {
            override val contract = definition.contract.copy(
                requiredCapabilities = setOf(LOCK_COORDINATION_CAPABILITY),
            )
            override val handler = definition.handler
        }

        val resolution = resolveBodyExecutionPolicy(broken, BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING)

        assertTrue(
            resolution is BodyPolicyResolution.Rejected,
            "a HANDLER_CONTINUATION Step that cannot reach its body must be rejected, got $resolution",
        )
    }

    // ------------------------------------------------------------ input codec

    @Test
    fun `input round-trips`() {
        val original = CoreLockInput(
            resource = "staging",
            timeoutSeconds = 30,
            reason = "deploy",
            skipIfLocked = false,
        )
        val decoded = definition.contract.inputCodec.decode(definition.contract.inputCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `input round-trips a minimal payload`() {
        val original = CoreLockInput(resource = "staging")
        val decoded = definition.contract.inputCodec.decode(definition.contract.inputCodec.encode(original))
        assertEquals(original, decoded, "an absent timeout and an absent reason must not become zeroes")
    }

    @Test
    fun `a non-object input payload is a typed codec failure`() {
        assertThrows(CoreLockCodecException::class.java) {
            definition.contract.inputCodec.decode(
                dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("\"not-an-object\""),
            )
        }
    }

    @Test
    fun `a non-integer timeout is a typed codec failure`() {
        assertThrows(CoreLockCodecException::class.java) {
            definition.contract.inputCodec.decode(
                dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
                    """{"resource":"staging","timeoutSeconds":"soon"}""",
                ),
            )
        }
    }

    // ----------------------------------------------------------- intent decision

    @Test
    fun `skipIfLocked means now`() {
        assertEquals(
            LockIntentResolution.Resolved(LockIntent.Now),
            lockIntentOf(skipIfLocked = true, timeoutSeconds = null),
        )
    }

    @Test
    fun `no timeout means forever`() {
        assertEquals(
            LockIntentResolution.Resolved(LockIntent.Forever),
            lockIntentOf(skipIfLocked = false, timeoutSeconds = null),
        )
    }

    @Test
    fun `a timeout is expressed in milliseconds`() {
        assertEquals(
            LockIntentResolution.Resolved(LockIntent.UpTo(30_000L)),
            lockIntentOf(skipIfLocked = false, timeoutSeconds = 30),
        )
    }

    @Test
    fun `skipIfLocked with a timeout is rejected, not silently resolved`() {
        val resolution = lockIntentOf(skipIfLocked = true, timeoutSeconds = 30)
        assertEquals(
            LockIntentResolution.Rejected(LockInputError.SkipIfLockedWithTimeout),
            resolution,
            "honouring one of two contradictory declarations is how a pipeline ends up with " +
                "semantics nobody wrote",
        )
    }

    @Test
    fun `a negative timeout is rejected`() {
        assertEquals(
            LockIntentResolution.Rejected(LockInputError.NegativeTimeout(-1)),
            lockIntentOf(skipIfLocked = false, timeoutSeconds = -1),
        )
    }

    // ----------------------------------------------------------- output codec

    private fun roundTrip(value: CoreLockOutput): CoreLockOutput =
        definition.contract.outputCodec.decode(definition.contract.outputCodec.encode(value))

    @Test
    fun `an acquired hold with a succeeding body round-trips`() {
        val original = CoreLockOutput(
            resource = "staging",
            bodyRan = true,
            admission = LockAdmission.Acquired("staging", reentrant = false),
            outcome = StepOutcome.Success,
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `a re-entrant acquisition is distinguishable after a round-trip`() {
        val original = CoreLockOutput(
            resource = "staging",
            bodyRan = true,
            admission = LockAdmission.Acquired("staging", reentrant = true),
            outcome = StepOutcome.Success,
        )
        assertEquals(
            true,
            (roundTrip(original).admission as LockAdmission.Acquired).reentrant,
            "re-entrancy is a contract value, not an accident of the backend, so it must " +
                "survive the journal",
        )
    }

    @Test
    fun `a body failure round-trips with its typed kind and message`() {
        val original = CoreLockOutput(
            resource = "staging",
            bodyRan = true,
            admission = LockAdmission.Acquired("staging"),
            outcome = StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "deploy.sh failed")),
        )
        val decoded = roundTrip(original)
        val failure = (decoded.outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.SCRIPT, failure.kind)
        assertEquals("deploy.sh failed", failure.message)
    }

    @Test
    fun `a skipped acquisition and an acquired one are NOT the same journal row`() {
        val skipped = CoreLockOutput(
            resource = "staging",
            bodyRan = false,
            admission = LockAdmission.Denied(LockDenialReason.Held),
            outcome = StepOutcome.Success,
        )
        val taken = CoreLockOutput(
            resource = "staging",
            bodyRan = true,
            admission = LockAdmission.Acquired("staging"),
            outcome = StepOutcome.Success,
        )
        assertNotNull(roundTrip(skipped).admission as? LockAdmission.Denied)
        assertNotNull(roundTrip(taken).admission as? LockAdmission.Acquired)
        assertTrue(
            roundTrip(skipped) != roundTrip(taken),
            "both are Success; without the admission discriminant a reader of the journal " +
                "cannot tell 'never took it' from 'took it and the body ran'",
        )
    }

    @Test
    fun `a timeout denial round-trips its waited time`() {
        val original = CoreLockOutput(
            resource = "staging",
            bodyRan = false,
            admission = LockAdmission.Denied(LockDenialReason.TimedOut(5_000L)),
            outcome = StepOutcome.Failure(
                PipelineFailure(FailureKind.TIMEOUT, "core.lock: 'staging' was not acquired within 5000ms"),
            ),
        )
        val denial = roundTrip(original).admission as LockAdmission.Denied
        assertEquals(LockDenialReason.TimedOut(5_000L), denial.reason)
    }

    @Test
    fun `a cancellation denial round-trips`() {
        val original = CoreLockOutput(
            resource = "staging",
            bodyRan = false,
            admission = LockAdmission.Denied(LockDenialReason.Cancelled),
            outcome = StepOutcome.Failure(
                PipelineFailure(FailureKind.INFRASTRUCTURE, "cancelled while waiting"),
            ),
        )
        val denial = roundTrip(original).admission as LockAdmission.Denied
        assertEquals(LockDenialReason.Cancelled, denial.reason)
    }

    @Test
    fun `an unstable body outcome round-trips`() {
        val original = CoreLockOutput(
            resource = "staging",
            bodyRan = true,
            admission = LockAdmission.Acquired("staging"),
            outcome = StepOutcome.Unstable,
        )
        assertEquals(StepOutcome.Unstable, roundTrip(original).outcome)
    }
}
