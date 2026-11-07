package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The fail-closed gate that decides whether an operation is the one the journal holds.
 *
 * ## The guarantee
 *
 * Two digests that differ are a divergence, and the pipeline aborts — with ONE exception, and
 * the exception is the whole point of this file: a digest that is already journalized is read
 * with the spelling it was written with.
 *
 * ## The spelling that changed
 *
 * A refusal at the typed admission used to write the canonical payload with the `RERUN` policy,
 * whatever the step had declared. The policy is hashed into the identity, so a step that
 * declared `MEMOIZED` was journaled under a digest that no admission of that step would ever
 * produce. The next attempt of the same run computed the declared policy's digest, compared the
 * two and declared a DIVERGENCE — for a program that had not changed by a single byte, and for
 * an operation that had never run.
 *
 * ## How the row is read
 *
 * The row carries its own input, so the older spelling can be recomputed FROM THE ROW and
 * compared with the digest the row carries. When they agree, the row is the same operation
 * under the older spelling, and the operation has not diverged.
 *
 * ## How narrow the window is
 *
 * The row's input has to be the current operation's input — the PROGRAM is the same — and the
 * digest has to be exactly the older spelling of that input. A different program is a
 * divergence, and it stays one; a row whose digest is the declared policy's spelling is not in
 * the window, so a step whose declared policy CHANGED is still a divergence; and a row whose
 * attempt is below one is not an operation this repository ever wrote, so the gate refuses it
 * instead of recomputing an identity under an attempt that cannot exist.
 *
 * Pure and total: no journal, no store, no clock, no filesystem.
 */
class StrictFingerprintDivergenceDetectorTest {

    private val detector = StrictFingerprintDivergenceDetector()

    /** No journal entry: the first attempt, and no divergence is possible. */
    @Test
    fun `no journal entry means no divergence`() {
        val result = detector.check(operation(identity(ReplayPolicy.MEMOIZED)), null)

        assertTrue(result.isSuccess, "the first attempt of an operation cannot diverge from a row that is not there")
    }

    /** The same identity: the operation is the one the journal holds. */
    @Test
    fun `the same identity is not a divergence`() {
        val result = detector.check(
            operation(identity(ReplayPolicy.MEMOIZED)),
            operation(identity(ReplayPolicy.MEMOIZED)),
        )

        assertTrue(result.isSuccess, "the same identity is the same operation")
    }

    /** Another program: a divergence, and the pipeline aborts. */
    @Test
    fun `another program is a divergence`() {
        val result = detector.check(
            operation(identity(ReplayPolicy.MEMOIZED)),
            operation(identityOf(otherInput(), ReplayPolicy.MEMOIZED), otherProgram = true),
        )

        assertTrue(result.isFailure, "another program is another operation, and the gate is fail-closed")
        val divergence = assertInstanceOf(
            DivergenceException::class.java,
            result.exceptionOrNull(),
            "the refusal names the divergence, so an operator knows what the run is claiming",
        )
        assertEquals(
            identity(ReplayPolicy.MEMOIZED).hex,
            divergence.actual.hex,
            "the refusal names the digest the run brought and the digest the row holds",
        )
        assertEquals(
            identityOf(otherInput(), ReplayPolicy.MEMOIZED).hex,
            divergence.expected.hex,
            "and the digest the row holds is the other program's own",
        )

        val message = divergence.message.orEmpty()
        assertTrue(
            message.contains(OP_ID) &&
                message.contains(identity(ReplayPolicy.MEMOIZED).hex) &&
                message.contains(identityOf(otherInput(), ReplayPolicy.MEMOIZED).hex),
            "the refusal names the operation and both digests, so an operator can see which " +
                "side moved: a message that said nothing about the operation would leave the " +
                "reader with a digest and no operation to look up",
        )
    }

    /**
     * A row written with the OLDER SPELLING of the same program is the same operation.
     *
     * The row is the one a shipped candidate wrote: the same input, the same operation, spelled
     * with the policy the refusal used to hardcode. The run comes back, computes the declared
     * policy's digest and asks the gate whether the operation diverged. It did not.
     */
    @Test
    fun `a row written with the older spelling of the same program is the same operation`() {
        val result = detector.check(
            operation(identity(ReplayPolicy.MEMOIZED)),
            operation(Fingerprint.compute(input(), input().stepId, ReplayPolicy.RERUN, ATTEMPT)),
        )

        assertTrue(
            result.isSuccess,
            "the digest is already journalized, so it is read with the spelling it was written " +
                "with: the program is the same one and the operation has not diverged",
        )
    }

    /**
     * A row of ANOTHER program, spelled the older way, is still a divergence.
     *
     * This is the negative control of the row above. The window is bounded by the program: a
     * digest that happens to be the older spelling of ANOTHER input is not this operation's
     * identity, and a gate that accepted it would be a hole rather than a way of reading an old
     * row.
     */
    @Test
    fun `a row of another program spelled the older way is still a divergence`() {
        val result = detector.check(
            operation(identity(ReplayPolicy.MEMOIZED)),
            operation(
                Fingerprint.compute(otherInput(), otherInput().stepId, ReplayPolicy.RERUN, ATTEMPT),
                otherProgram = true,
            ),
        )

        assertTrue(
            result.isFailure,
            "the older spelling of ANOTHER program is not this operation's identity: the window " +
                "is bounded by the program",
        )
    }

    /**
     * A row whose DECLARED POLICY changed is a divergence.
     *
     * The window covers ONE older spelling: the one the refusal used to write, under the `RERUN`
     * policy. A row that carries a THIRD spelling — the same program under a policy nobody
     * declared for it — is not in the window. The policy is hashed into the identity, so a
     * digest under another policy is another operation, and reading it as this one would accept
     * a program whose replay semantics changed.
     */
    @Test
    fun `a row whose declared policy changed is a divergence`() {
        val result = detector.check(
            operation(identity(ReplayPolicy.MEMOIZED)),
            operation(identityOf(input(), ReplayPolicy.NEVER)),
        )

        assertTrue(
            result.isFailure,
            "the window covers the ONE older spelling the refusal used to write: a row under " +
                "another policy is another operation, and reading it as this one would accept a " +
                "program whose replay semantics changed",
        )
    }

    /**
     * A row of an IMPOSSIBLE attempt is a divergence, not a crash.
     *
     * The identity of a row is recomputed under the row's own attempt, and an attempt below one
     * is not an attempt this repository writes — the identity cannot be computed under it. The
     * gate refuses the row instead of recomputing under an attempt that cannot exist: a
     * fail-closed gate that threw while reading a row would answer the run with a crash where
     * it owes it a divergence.
     */
    @Test
    fun `a row of an impossible attempt is a divergence, not a crash`() {
        val result = detector.check(
            operation(identity(ReplayPolicy.MEMOIZED)),
            operation(
                Fingerprint.compute(input(), input().stepId, ReplayPolicy.RERUN, ATTEMPT),
                attempt = 0,
            ),
        )

        assertTrue(
            result.isFailure,
            "a row whose attempt is below one is not an operation this repository ever wrote: " +
                "the gate owes the run a divergence, not an exception raised while reading it",
        )
    }

    // ---------------------------------------------------------------------
    // the fixtures
    // ---------------------------------------------------------------------

    /** The operation under observation, with the identity it brings. */
    private fun operation(
        identity: Fingerprint,
        otherProgram: Boolean = false,
        attempt: Int = ATTEMPT,
    ): RerunOperation = RerunOperation(
        id = OP_ID,
        fingerprint = identity,
        input = if (otherProgram) otherInput() else input(),
        output = null,
        status = OperationStatus.PENDING,
        attempt = attempt,
    )

    /** The identity the operation brings, under the policy it declares. */
    private fun identity(policy: ReplayPolicy): Fingerprint = identityOf(input(), policy)

    /** The identity of the given program, under the given policy. */
    private fun identityOf(input: OperationInput, policy: ReplayPolicy): Fingerprint =
        Fingerprint.compute(input, input.stepId, policy, ATTEMPT)

    /** The durable input of the operation under observation. */
    private fun input(): OperationInput = OperationInput(
        stepId = STEP_ID,
        params = mapOf("payload" to JsonPrimitive("value")),
        runId = RUN_ID,
        attempt = ATTEMPT,
    )

    /** The durable input of ANOTHER program. */
    private fun otherInput(): OperationInput = OperationInput(
        stepId = STEP_ID,
        params = mapOf("payload" to JsonPrimitive("another program")),
        runId = RUN_ID,
        attempt = ATTEMPT,
    )

    private companion object {
        const val STEP_ID = "core.pwd"
        const val OP_ID = "run-42-s0-1"
        const val RUN_ID = "run-42"
        const val ATTEMPT = 1
    }
}
