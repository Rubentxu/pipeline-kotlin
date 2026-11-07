package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.runtime.StepMetadata
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A refusal at the typed admission, and the identity its row carries.
 *
 * ## The guarantee
 *
 * The row of a refused operation carries the identity of THAT operation — the one the
 * admission would have written — so the next attempt of the same run compares two digests of
 * the same program and finds them equal.
 *
 * ## The defect this file hunts
 *
 * A refusal used to write the canonical payload with the `RERUN` policy, whatever the step had
 * declared. The policy is hashed into the identity, and it is the only record of the policy a
 * journal row carries (nothing writes a `MemoizedOperation`, so every step row travels under the
 * kind `RERUN`). So the row of a refused `core.pwd` — a step that declares `MEMOIZED` — recorded
 * a policy the step never declared, under a digest that no admission of that step would ever
 * produce.
 *
 * A failure HOLDS the cursor, so the next attempt of the same run dispatches the same step
 * again, computes the DECLARED policy's digest and compares the two in the divergence gate.
 * They differed, and the run was declared DIVERGENT: a run that failed with "schema mismatch"
 * came back as "diverged from its journaled fingerprint", a fact that was false — the program
 * had not changed by a single byte, and the operation had never run.
 *
 * ## The instrument
 *
 * The production journal ([InMemoryOperationJournal], the storage-free twin of the SQLite one,
 * row for row) and the production resolver, with the production detector. The clock does not
 * move, and nothing here touches a disk, a process or a network.
 */
class SchemaRejectionIdentityTest {

    /**
     * A refusal writes the identity of the operation it refuses.
     *
     * The refusal does not invent an identity: the caller declares it, and the row carries it.
     * The declared identity is the one the admission of the same operation would carry — the
     * canonical payload under the policy the step DECLARED.
     */
    @Test
    fun `a refusal writes the identity of the operation it refuses`() {
        val input = input()
        val identity = Fingerprint.compute(input, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT)
        val journal = journal()
        val records = DurableInvocationRecord(journal)

        resolver(records).rejectSchema(OP_ID, input, identity, "schema mismatch")

        val row = requireNotNull(
            journal.get(OP_ID, ATTEMPT),
        ) {
            "the refusal writes a row: a refusal that vanishes is a run that claims it did nothing"
        }
        assertEquals(
            identity,
            row.fingerprint,
            "the row of a refused operation carries the identity of THAT operation, under the " +
                "policy the step declared: the journal's only record of the policy is this " +
                "digest, and a hardcoded one records a policy the step never declared",
        )
        assertEquals(
            OperationStatus.FAILED,
            row.status,
            "a refusal is a FAILED row, and it keeps being one",
        )
    }

    /**
     * A run that comes back to its own refusal is not a divergence.
     *
     * The row seeded here is the row a shipped candidate wrote: the same operation, the same
     * program, spelled with the policy the refusal used to hardcode. The run comes back, computes
     * the DECLARED policy's identity and asks the gate whether the operation diverged.
     *
     * It did not. The program is the same one, and the digest is the same operation under an
     * older spelling of its identity — a digest that is already journalized is read with the
     * spelling it was written with, and it is not recalculated as if it were new.
     */
    @Test
    fun `a run that comes back to its own refusal is not a divergence`() {
        val input = input()
        val journal = journal()
        val records = DurableInvocationRecord(journal)

        // The row a shipped candidate wrote for this refusal.
        journal.append(
            RerunOperation(
                id = OP_ID,
                fingerprint = Fingerprint.compute(input, STEP_ID, ReplayPolicy.RERUN, ATTEMPT),
                input = input,
                output = null,
                status = OperationStatus.FAILED,
                attempt = ATTEMPT,
            ),
        )

        val reconciled = resolver(records).reconcileInvocation(
            metadata = metadata(),
            fingerprint = Fingerprint.compute(input, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT),
            input = input,
            operationId = OP_ID,
        )

        assertInstanceOf(
            InvocationReconciliation.Execute::class.java,
            reconciled.resolution,
            "the run came back to the refusal it already recorded, for the same program: it is " +
                "the same operation, and a divergence would be a lie about the program",
        )
    }

    /**
     * A DIFFERENT program is still a divergence.
     *
     * The window that lets an older spelling be read as the same operation is bounded by the
     * program: the row has to carry the same input. A row of another program is a divergence,
     * and it stays one even when its digest happens to be the older spelling of ITS OWN input.
     *
     * This is the negative control of the row above: without it, the window would be a hole in
     * the fail-closed gate rather than a way of reading an old row.
     */
    @Test
    fun `a different program is still a divergence`() {
        val input = input()
        val other = input(params = mapOf("payload" to JsonPrimitive("another program")))
        val journal = journal()
        val records = DurableInvocationRecord(journal)

        // A row of ANOTHER program, written with the older spelling of that other program.
        journal.append(
            RerunOperation(
                id = OP_ID,
                fingerprint = Fingerprint.compute(other, STEP_ID, ReplayPolicy.RERUN, ATTEMPT),
                input = other,
                output = null,
                status = OperationStatus.FAILED,
                attempt = ATTEMPT,
            ),
        )

        val reconciled = resolver(records).reconcileInvocation(
            metadata = metadata(),
            fingerprint = Fingerprint.compute(input, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT),
            input = input,
            operationId = OP_ID,
        )

        assertInstanceOf(
            InvocationReconciliation.Diverged::class.java,
            reconciled.resolution,
            "another program is another operation: the window that reads an older spelling is " +
                "bounded by the program, and a gate that let another program through would be " +
                "the hole this row closes",
        )
    }

    // ---------------------------------------------------------------------
    // the fixtures
    // ---------------------------------------------------------------------

    /** The journal of production: the storage-free twin of the SQLite one, row for row. */
    private fun journal(): InMemoryOperationJournal = InMemoryOperationJournal(FixedClock)

    /** The resolver, composed over the record it writes through, with the production detector. */
    private fun resolver(records: DurableInvocationRecord): DurableInvocationResolver =
        DurableInvocationResolver(
            divergenceDetector = dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            records = records,
            runningSubprocessRecovery = RunningSubprocessRecovery {
                RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured)
            },
        )

    /** The durable input of the operation under observation. */
    private fun input(
        params: Map<String, JsonPrimitive> = mapOf("payload" to JsonPrimitive("value")),
    ): OperationInput = OperationInput(
        stepId = STEP_ID,
        params = params,
        runId = RUN_ID,
        attempt = ATTEMPT,
    )

    /** The properties the step under observation declares. */
    private fun metadata(): StepMetadata = StepMetadata(
        effects = emptySet(),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    /** A clock that does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        const val STEP_ID = "core.pwd"
        const val OP_ID = "run-42-s0-1"
        const val RUN_ID = "run-42"
        const val ATTEMPT = 1
    }
}
