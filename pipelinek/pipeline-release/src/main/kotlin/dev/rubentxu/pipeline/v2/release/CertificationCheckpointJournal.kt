package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.runtime.durable.FencingToken
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.OperationJournal
import dev.rubentxu.pipeline.v2.runtime.durable.PublishAuthority
import dev.rubentxu.pipeline.v2.runtime.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.runtime.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Where the state of the cycle lives.
 *
 * ## The one authority
 *
 * The state of a certification cycle is a row in the product's own durable
 * journal, filed under the cycle's name. This class does not keep a copy, does
 * not keep a file of its own and does not keep a cache: the journal is the
 * authority, and a second copy of the state is a second state that can
 * disagree with the first one.
 *
 * ## How a checkpoint is written
 *
 * One row per revision of the cycle. The row's output IS the checkpoint, so
 * the state that is restored is the state that was written, not a summary of
 * it. The row is written in the journal's two phases — [OperationJournal.beginOperation]
 * and [OperationJournal.append] — and a row with no terminal status is a write
 * that was interrupted: it is not state, and it says so by having no output.
 *
 * ## How two writers are kept apart
 *
 * The product's own exclusivity: the lease of the cycle, and the fencing token
 * it hands out. A writer takes the lease before it reads, so the state it
 * reads is the state under its own authority, and it checks the token against
 * the record once more before it writes. A writer whose authority moved while
 * it was working is fenced out and writes nothing.
 */
class CertificationCheckpointJournal(
    private val journal: OperationJournal,
    private val lease: RunLeasePort,
    private val clock: Clock,
) {

    /**
     * The state of the cycle as it stands, or why it cannot be read.
     *
     * The three answers are three different facts, and the difference between
     * them is the difference between "the cycle has not started" and "the
     * cycle's state is there and cannot be read". A reader that confuses the
     * second with the first starts the work again, and the work it repeats is
     * a certification that takes hours.
     */
    fun restore(cycleId: CertificationCycleId): CheckpointRestore {
        val rows = journal.listForRun(cycleId.value).filter { it.id == opId(cycleId) }
        val latest = rows.filter { it.status.isTerminal }.maxByOrNull { it.attempt }
        val output = latest?.output ?: return CheckpointRestore.Absent
        return decode(output.result)
    }

    /**
     * Apply [transition] to the state of the cycle and make the result durable.
     *
     * A transition the state already knows changes nothing and writes nothing.
     * A transition that contradicts the state is refused. A transition that
     * moves the state is written under the cycle's lease, and only if the
     * writer still holds the authority when it writes.
     */
    fun record(
        cycleId: CertificationCycleId,
        ownerId: RunOwnerId,
        transition: CertificationTransition,
    ): CheckpointRecordOutcome {
        val runId = RunId(cycleId.value)
        val acquisition = lease.acquire(LeaseRequest(runId = runId, ownerId = ownerId))
        val token = when (acquisition) {
            is LeaseAcquisition.Acquired -> acquisition.fencingToken
            is LeaseAcquisition.Reentered -> acquisition.fencingToken
            is LeaseAcquisition.TakenOver -> acquisition.fencingToken
            is LeaseAcquisition.AlreadyOwned -> return CheckpointRecordOutcome.Refused(
                "the cycle ${cycleId.value} is being written by ${acquisition.heldBy} at token " +
                    "${acquisition.fencingToken}, so this writer does not write it.",
            )

            is LeaseAcquisition.Unverifiable -> return CheckpointRecordOutcome.Refused(
                "the lease of the cycle ${cycleId.value} could not be established: " +
                    acquisition.reason,
            )
        }

        try {
            return recordUnder(cycleId, runId, token, transition)
        } finally {
            lease.release(ownerId)
        }
    }

    /**
     * The body of [record], with the lease already taken.
     */
    private fun recordUnder(
        cycleId: CertificationCycleId,
        runId: RunId,
        token: FencingToken,
        transition: CertificationTransition,
    ): CheckpointRecordOutcome {
        val restored = restore(cycleId)
        val current = when (restored) {
            is CheckpointRestore.Restored -> restored.checkpoint
            is CheckpointRestore.Absent -> null
            is CheckpointRestore.Unreadable -> return CheckpointRecordOutcome.Refused(
                "the state of the cycle ${cycleId.value} cannot be read, so nothing is written " +
                    "over it: ${restored.reason}",
            )
        }

        return when (val outcome = applyCertificationTransition(cycleId, current, transition)) {
            is CheckpointTransitionOutcome.Rejected -> CheckpointRecordOutcome.Refused(outcome.reason)
            is CheckpointTransitionOutcome.Unchanged -> CheckpointRecordOutcome.Unchanged(
                outcome.checkpoint,
                outcome.reason,
            )

            is CheckpointTransitionOutcome.Advanced -> write(
                cycleId = cycleId,
                runId = runId,
                token = token,
                checkpoint = outcome.checkpoint,
                transition = transition,
            )
        }
    }

    /**
     * Write one revision of the cycle, after checking that this writer still
     * holds the authority.
     */
    private fun write(
        cycleId: CertificationCycleId,
        runId: RunId,
        token: FencingToken,
        checkpoint: CertificationCheckpoint,
        transition: CertificationTransition,
    ): CheckpointRecordOutcome {
        val authority = RunExecutionLease.authorisePublish(lease.observe(runId), token)
        if (authority !is PublishAuthority.Authorised) {
            return CheckpointRecordOutcome.Refused(
                "the cycle ${cycleId.value} was not written: ${authority.render()}",
            )
        }

        val opId = opId(cycleId)
        val input = OperationInput(
            stepId = STEP_ID,
            params = mapOf(
                "transition" to JsonPrimitive(transition.identity),
                "candidate" to JsonPrimitive(checkpoint.candidate.canonical),
            ),
            runId = runId.value,
            attempt = checkpoint.revision,
        )
        val fingerprint = Fingerprint.compute(input, STEP_ID, ReplayPolicy.RERUN, checkpoint.revision)

        val begun = beginRevision(opId, checkpoint.revision, fingerprint, input)
        if (begun is BeginOutcome.Refused) {
            return CheckpointRecordOutcome.Refused(begun.reason)
        }

        val output = OperationOutput(
            result = JSON.encodeToJsonElement(serializer<CertificationCheckpoint>(), checkpoint),
            durationMs = 0,
            finishedAt = clock.now().toEpochMilli(),
        )
        journal.append(
            RerunOperation(
                id = opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = OperationStatus.SUCCEEDED,
                attempt = checkpoint.revision,
            ),
        )
        return CheckpointRecordOutcome.Written(checkpoint)
    }

    /**
     * Begin the row of one revision.
     *
     * A row that is already there is not an error: it is either a write that
     * was interrupted and is being finished, or a revision another writer
     * completed first. The two are told apart by the row's own status and by
     * the transition the row was begun for.
     */
    private fun beginRevision(
        opId: String,
        revision: Int,
        fingerprint: Fingerprint,
        input: OperationInput,
    ): BeginOutcome {
        return try {
            journal.beginOperation(
                opId = opId,
                attempt = revision,
                fingerprint = fingerprint.hex,
                inputJson = JSON.encodeToString(OperationInput.serializer(), input),
                deadlineMs = null,
            )
            BeginOutcome.Begun
        } catch (e: IllegalStateException) {
            val existing = journal.get(opId, revision)
                ?: return BeginOutcome.Refused(
                    "the revision $revision of the cycle was already begun and the journal no " +
                        "longer holds the row that says so.",
                )
            if (existing.status.isTerminal) {
                return BeginOutcome.Refused(
                    "the revision $revision of the cycle is already written, so this writer " +
                        "writes nothing over it.",
                )
            }
            val begunFor = transitionIdentityOf(existing)
            val askedFor = transitionIdentityOf(input)
            if (begunFor != null && begunFor != askedFor) {
                return BeginOutcome.Refused(
                    "the revision $revision of the cycle was begun for transition '$begunFor' and " +
                        "not for '$askedFor', so this writer writes nothing over it.",
                )
            }
            BeginOutcome.Begun
        }
    }

    /**
     * The identity of the transition a journaled row was begun for.
     */
    private fun transitionIdentityOf(operation: DurableOperation): String? =
        (operation.input.params["transition"] as? JsonPrimitive)?.content

    /**
     * The identity of the transition an input describes.
     */
    private fun transitionIdentityOf(input: OperationInput): String? =
        (input.params["transition"] as? JsonPrimitive)?.content

    /**
     * Read a checkpoint back out of the row that carries it.
     */
    private fun decode(element: JsonElement): CheckpointRestore {
        return try {
            CheckpointRestore.Restored(
                JSON.decodeFromJsonElement(serializer<CertificationCheckpoint>(), element),
            )
        } catch (e: SerializationException) {
            CheckpointRestore.Unreadable(
                e.message ?: "the row does not hold a checkpoint this build can read.",
            )
        } catch (e: IllegalArgumentException) {
            CheckpointRestore.Unreadable(
                e.message ?: "the row does not hold a checkpoint this build can read.",
            )
        }
    }

    /** What beginning the row of one revision may yield. */
    private sealed interface BeginOutcome {
        data object Begun : BeginOutcome
        data class Refused(val reason: String) : BeginOutcome
    }

    companion object {
        /**
         * The step the journaled rows of a checkpoint belong to.
         *
         * It is public because it is a durable fact of the row: an operator reading the journal
         * sees which step a row belongs to, and a writer that has to finish an interrupted row
         * needs to begin it under the same step.
         */
        const val STEP_ID: String = "certification-checkpoint"

        /** The prefix of the operation identifier every revision of a cycle is filed under. */
        private const val OP_PREFIX: String = "checkpoint."

        /**
         * The operation identifier every revision of a cycle is filed under.
         *
         * It is public because the rows are durable and someone may have to find them: an
         * operator with the database at hand reads this name to know where the state of a cycle
         * lives, and a test that has to imitate a write that was interrupted needs to begin a
         * row under the same name the writer would have used.
         */
        fun opId(cycleId: CertificationCycleId): String = "$OP_PREFIX${cycleId.value}"

        /**
         * The codec of the checkpoint. The shape is closed: a row that carries a field this
         * build does not define is refused rather than read as a checkpoint.
         */
        private val JSON = Json { encodeDefaults = true }
    }
}

/**
 * What the state of a cycle may be when it is read back.
 */
sealed interface CheckpointRestore {

    /** The cycle has a state and it was read. */
    data class Restored(val checkpoint: CertificationCheckpoint) : CheckpointRestore

    /** The cycle has no state yet: no revision of it was ever written. */
    data object Absent : CheckpointRestore

    /** The cycle has a state and this build cannot read it. */
    data class Unreadable(val reason: String) : CheckpointRestore
}

/**
 * What recording a transition in the cycle's state may yield.
 */
sealed interface CheckpointRecordOutcome {

    /** The state moved and the move is durable. */
    data class Written(val checkpoint: CertificationCheckpoint) : CheckpointRecordOutcome

    /** The state already knew the transition, so nothing was written. */
    data class Unchanged(
        val checkpoint: CertificationCheckpoint,
        val reason: String,
    ) : CheckpointRecordOutcome

    /** The transition was not applied and nothing was written. */
    data class Refused(val reason: String) : CheckpointRecordOutcome
}
