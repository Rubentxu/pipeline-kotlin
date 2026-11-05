package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseLiveness
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.runtime.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Where the state of the cycle lives, and what survives an interruption.
 *
 * The guarantee under test is the one the cycle rests on: the state of a cycle
 * is a row in the product's own journal, it survives the death of the process
 * that wrote it, a transition repeated does not move it twice, and two writers
 * never write over each other.
 *
 * The journal under test is [InMemoryOperationJournal], a real implementation
 * of the port and not a mock of it. The lease under test keeps the product's
 * own rules: every acquisition goes through [RunExecutionLease], so the
 * exclusivity witnessed here is the exclusivity the product ships.
 */
class CertificationCheckpointJournalTest {

    private val version = ProductVersion.parseOrThrow("0.44.0")

    /** 64 hex, so the candidate identity is a digest and not a name. */
    private val digest = "a".repeat(64)

    /** The digest of a DIFFERENT candidate. */
    private val otherDigest = "b".repeat(64)

    private val instant = Instant.parse("2026-11-05T19:30:00Z")

    private val cycle: CertificationCycleId = requireNotNull(CertificationCycleId.of(version))

    private val owner = requireNotNull(RunOwnerId.of("writer"))

    private val otherOwner = requireNotNull(RunOwnerId.of("other-writer"))

    private val clock: Clock = FixedClock

    private val journal = InMemoryOperationJournal(clock)

    private val lease = TestLease()

    private val checkpoint = CertificationCheckpointJournal(journal, lease, clock)

    /**
     * A clock that does not move, so the writes of a test are comparable.
     */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-11-05T19:30:00Z")
    }

    private fun request(
        candidateDigest: String = digest,
        scenarios: List<String> = listOf("alpha", "beta"),
    ): CertificationRequest {
        val outcome = buildCertificationRequest(
            handoff = handoff(candidateDigest),
            catalogue = CertificationBatteryCatalogue(
                id = "harness-certification-battery",
                version = "1",
                scenarios = scenarios,
            ),
            requestedAt = instant,
        )
        return assertInstanceOf(CertificationRequestOutcome.Accepted::class.java, outcome).request
    }

    private fun handoff(candidateDigest: String): CandidateHandoff = CandidateHandoff(
        candidateId = CandidateId.fromDigest(candidateDigest),
        releaseTrain = version,
        candidateSequence = 1,
        productVersion = version,
        sourceCommit = "0".repeat(40),
        artifact = CandidateArtifact(
            name = "pipelinek-0.44.0.zip",
            sha256 = candidateDigest,
            size = 4096,
            archiveRoot = "pipelinek-0.44.0",
            implementationVersion = "0.44.0",
        ),
        distributionManifest = CandidateFileRef(
            name = "distribution-manifest.json",
            sha256 = digest,
            builtAt = "2026-11-05T19:30:00Z",
        ),
        sbom = null,
        sdk = null,
        bom = null,
    )

    private fun receipt(
        candidateDigest: String = digest,
        resultId: String = "0123456789abcdef",
    ): CertificationReceipt = CertificationReceipt(
        resultId = resultId,
        candidateId = CandidateId.fromDigest(candidateDigest),
        decision = CertificationDecision.CERTIFIED,
        battery = CertifiedBattery(
            id = "harness-certification-battery",
            version = "1",
            scenarioCount = 2,
        ),
        runs = listOf(
            ScenarioExecution("alpha", "EXECUTED_PASS"),
            ScenarioExecution("beta", "EXECUTED_PASS"),
        ),
    )

    private fun restored(): CertificationCheckpoint {
        val result = assertInstanceOf(
            CheckpointRestore.Restored::class.java,
            checkpoint.restore(cycle),
        )
        return result.checkpoint
    }

    /**
     * A row that was begun and never finished: a write the process died in the middle of.
     */
    private fun beginInterruptedRevision(revision: Int, transition: CertificationTransition) {
        val input = OperationInput(
            stepId = CertificationCheckpointJournal.STEP_ID,
            params = mapOf("transition" to JsonPrimitive(transition.identity)),
            runId = cycle.value,
            attempt = revision,
        )
        journal.beginOperation(
            opId = CertificationCheckpointJournal.opId(cycle),
            attempt = revision,
            fingerprint = Fingerprint("c".repeat(64)).hex,
            inputJson = Json.encodeToString(OperationInput.serializer(), input),
            deadlineMs = null,
        )
    }

    @Test
    fun `the state names the candidate, its digest and the request that was made`() {
        val requested = request()

        val outcome = checkpoint.record(
            cycle,
            owner,
            CertificationTransition.Requested(requested),
        )

        val written = assertInstanceOf(CheckpointRecordOutcome.Written::class.java, outcome)
        assertEquals(CandidateId.fromDigest(digest), written.checkpoint.candidate)
        assertEquals("sha256:$digest", written.checkpoint.candidate.canonical)
        assertEquals(requested, written.checkpoint.request)

        val state = restored()
        assertEquals(written.checkpoint, state)
    }

    @Test
    fun `the receipt that admitted the candidate is held by the state`() {
        val requested = request()
        checkpoint.record(cycle, owner, CertificationTransition.Requested(requested))
        val admitted = receipt()

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Admitted(admitted))

        val written = assertInstanceOf(CheckpointRecordOutcome.Written::class.java, outcome)
        assertEquals(admitted, written.checkpoint.receipt)
        assertEquals("0123456789abcdef", restored().receipt?.resultId)
    }

    @Test
    fun `the same transition twice leaves one state and one row`() {
        val requested = request()
        checkpoint.record(cycle, owner, CertificationTransition.Requested(requested))

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Requested(requested))

        val unchanged = assertInstanceOf(CheckpointRecordOutcome.Unchanged::class.java, outcome)
        assertEquals(1, unchanged.checkpoint.revision)
        assertEquals(1, journal.listForRun(cycle.value).size, "the repeated transition wrote a row")
    }

    @Test
    fun `the state survives the writer that wrote it`() {
        // A different writer over the same journal is what a process that died and came back
        // looks like: the state lives in the journal and not in the object that wrote it.
        checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))

        val afterRestart = CertificationCheckpointJournal(journal, lease, clock).restore(cycle)

        val state = assertInstanceOf(CheckpointRestore.Restored::class.java, afterRestart)
        assertEquals(request(), state.checkpoint.request)
    }

    @Test
    fun `an interrupted write leaves the state that was there`() {
        checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))
        beginInterruptedRevision(
            revision = 2,
            transition = CertificationTransition.Admitted(receipt()),
        )

        val state = restored()

        assertEquals(1, state.revision, "a write that was interrupted is not state")
        assertEquals(request(), state.request)
        assertNull(state.receipt)
    }

    @Test
    fun `a write that was interrupted is finished by the next writer of the same transition`() {
        checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))
        val admitted = receipt()
        beginInterruptedRevision(
            revision = 2,
            transition = CertificationTransition.Admitted(admitted),
        )

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Admitted(admitted))

        assertInstanceOf(CheckpointRecordOutcome.Written::class.java, outcome)
        val state = restored()
        assertEquals(2, state.revision)
        assertEquals("0123456789abcdef", state.receipt?.resultId)
    }

    @Test
    fun `a write begun for another transition is not written over`() {
        checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))
        beginInterruptedRevision(
            revision = 2,
            transition = CertificationTransition.DefectFound(
                CheckpointDefect("defect-1", CandidateId.fromDigest(digest)),
            ),
        )

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Admitted(receipt()))

        val refused = assertInstanceOf(CheckpointRecordOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("defect-1"), "the refusal must name the transition")
        assertEquals(1, restored().revision)
    }

    @Test
    fun `a second writer does not write over the one that holds the cycle`() {
        // The lease is held by another writer, and it is alive: the cycle belongs to it.
        lease.acquire(LeaseRequest(RunId(cycle.value), otherOwner))

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))

        val refused = assertInstanceOf(CheckpointRecordOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("other-writer"), "the refusal must name the live writer")
        assertEquals(0, journal.listForRun(cycle.value).size, "the refused writer wrote a row")
    }

    @Test
    fun `a writer whose authority moved is fenced out and writes nothing`() {
        // The authority moves to another owner between the acquisition and the write, which is
        // what a writer that was paused while another one took over comes back to.
        lease.authorityMovesOnAcquire = true

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))

        val refused = assertInstanceOf(CheckpointRecordOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("superseded"), "the refusal must say it was fenced out")
        assertEquals(0, journal.listForRun(cycle.value).size, "the fenced out writer wrote a row")
    }

    @Test
    fun `a receipt about another candidate is refused and writes nothing`() {
        checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))

        val outcome = checkpoint.record(
            cycle,
            owner,
            CertificationTransition.Admitted(receipt(candidateDigest = otherDigest)),
        )

        val refused = assertInstanceOf(CheckpointRecordOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains(digest), "the refusal must name the candidate asked for")
        assertTrue(refused.reason.contains(otherDigest), "the refusal must name the candidate that arrived")
        assertEquals(1, journal.listForRun(cycle.value).size, "the refused write added a row")
    }

    @Test
    fun `a state that cannot be read is not read as no state`() {
        // A row whose payload is not a checkpoint: the state is THERE and this build cannot
        // read it. Reading it as "the cycle has no state" would start the certification again.
        journal.append(
            RerunOperation(
                id = CertificationCheckpointJournal.opId(cycle),
                fingerprint = Fingerprint("d".repeat(64)),
                input = OperationInput(
                    stepId = CertificationCheckpointJournal.STEP_ID,
                    params = mapOf("transition" to JsonPrimitive("written-by-something-else")),
                    runId = cycle.value,
                    attempt = 1,
                ),
                output = OperationOutput(
                    result = JsonPrimitive("this is not a checkpoint"),
                    durationMs = 0,
                    finishedAt = 1L,
                ),
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )

        val restored = checkpoint.restore(cycle)

        assertInstanceOf(CheckpointRestore.Unreadable::class.java, restored)

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))
        val refused = assertInstanceOf(CheckpointRecordOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("cannot be read"), "the refusal must say the state is unreadable")
    }

    @Test
    fun `a journal that contradicts itself is not written over`() {
        // The row of the revision 2 is there and it is terminal, but the checkpoint it carries
        // says revision 1: the row and the payload disagree. The writer restores the payload,
        // computes revision 2, and finds the revision already written.
        val contradicting = CertificationCheckpoint(
            cycleId = cycle.value,
            candidate = CandidateId.fromDigest(digest),
            request = request(),
            revision = 1,
        )
        journal.append(
            RerunOperation(
                id = CertificationCheckpointJournal.opId(cycle),
                fingerprint = Fingerprint("e".repeat(64)),
                input = OperationInput(
                    stepId = CertificationCheckpointJournal.STEP_ID,
                    params = mapOf("transition" to JsonPrimitive("written-by-something-else")),
                    runId = cycle.value,
                    attempt = 2,
                ),
                output = OperationOutput(
                    result = Json.encodeToJsonElement(
                        CertificationCheckpoint.serializer(),
                        contradicting,
                    ),
                    durationMs = 0,
                    finishedAt = 1L,
                ),
                status = OperationStatus.SUCCEEDED,
                attempt = 2,
            ),
        )

        val outcome = checkpoint.record(cycle, owner, CertificationTransition.Requested(request()))

        val refused = assertInstanceOf(CheckpointRecordOutcome.Refused::class.java, outcome)
        assertTrue(refused.reason.contains("already written"), "the refusal must say the revision exists")
    }

    /**
     * A lease that keeps the product's own rules.
     *
     * Every acquisition goes through [RunExecutionLease.acquire], so the exclusivity this test
     * witnesses is the exclusivity the product ships and not a copy of it. The fake owns only the
     * facts -- who the record names and which token it carries -- plus the one thing no pure
     * function can do: let the authority MOVE while a writer believes it holds the lease.
     */
    private class TestLease : RunLeasePort {

        private var record: LeaseRecord? = null

        /** When true, the authority moves to another owner as soon as a writer acquires it. */
        var authorityMovesOnAcquire: Boolean = false

        override fun acquire(request: LeaseRequest): LeaseAcquisition {
            val acquisition = RunExecutionLease.acquire(record, request)
            return when (acquisition) {
                is LeaseAcquisition.Acquired -> {
                    record = if (authorityMovesOnAcquire) {
                        movedBeyond(acquisition.record)
                    } else {
                        acquisition.record
                    }
                    acquisition
                }

                is LeaseAcquisition.Reentered -> {
                    record = acquisition.record
                    acquisition
                }

                is LeaseAcquisition.TakenOver -> {
                    record = acquisition.record
                    acquisition
                }

                is LeaseAcquisition.AlreadyOwned -> acquisition
                is LeaseAcquisition.Unverifiable -> acquisition
            }
        }

        override fun release(ownerId: RunOwnerId) {
            val current = record ?: return
            record = RunExecutionLease.release(current, ownerId)
        }

        override fun observe(runId: RunId): LeaseRecord? = record?.takeIf { it.runId == runId }

        override fun isKnown(runId: RunId): Boolean = observe(runId) != null

        override fun liveness(runId: RunId): LeaseLiveness {
            val current = observe(runId) ?: return LeaseLiveness.Free
            val owner = current.ownerId ?: return LeaseLiveness.Free
            return LeaseLiveness.Held(owner)
        }

        /**
         * The authority of the cycle moves to another owner, and the token advances with it.
         */
        private fun movedBeyond(held: LeaseRecord): LeaseRecord {
            val dead = held.copy(ownerAlive = false)
            val thief = RunOwnerId.of("thief") ?: return dead
            val acquisition = RunExecutionLease.acquire(dead, LeaseRequest(dead.runId, thief))
            return (acquisition as? LeaseAcquisition.TakenOver)?.record ?: dead
        }
    }
}
