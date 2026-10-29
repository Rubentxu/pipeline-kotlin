package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import kotlinx.serialization.json.Json

/**
 * The durable row of ONE invocation, and the only thing in the application layer that writes it.
 *
 * ## What this owns
 *
 * A durable invocation has exactly one row: the [RerunOperation] that the journal holds under
 * `(opId, attempt)`. Before this class existed that row had three writers and two readers:
 *
 * ```text
 * StepDispatchEngine        journal.get(opId, 1)      ← read
 * ScriptedRegistryInvoker   journal.get(opId)         ← read, of the HIGHEST attempt
 * DurableInvocationResolver rejectSchema → append     ← write (schema rejection)
 * DurableStepExecutor       beginOperation + append  ← write (admission + execution)
 * RecoveryInterpretationEngine  append                ← write (recovery)
 * ```
 *
 * and two of those readers each rebuilt the SAME `PENDING` envelope that the divergence gate
 * compares against the journaled row. Three writers means three places to get the row's shape
 * wrong; two readers of the same fact means two answers to "which row is this invocation's?".
 * The engine read `get(opId, 1)` and the scripted invoker read `get(opId)` — the row of the
 * HIGHEST attempt — so the scripted surface compared the fingerprint of attempt 1 against a row
 * that might belong to another attempt.
 *
 * ## What this class IS
 *
 * Not a wrapper around the port and not a value object: it is the single owner of the row's
 * IDENTITY. It holds the [OperationJournal] port (so the application layer holds it in one place
 * instead of three), it holds the attempt invariant, and it holds the two decisions that belong
 * to the row rather than to the caller:
 *
 *  - [admit] — whether this invocation needs a `RUNNING` row written BEFORE the effect, which is
 *    the case only when the row does not exist yet.
 *  - [settle] — the terminal row, in ONE shape, written by ONE call.
 *
 * The measured facts stay with whoever measured them: the executor owns the duration of an
 * execution, the recovery engine owns the duration of a reconciliation, and both hand the
 * resulting [OperationOutput] to [settle]. What [settle] owns is the row: the attempt, the
 * status, and the write itself.
 *
 * ## The attempt invariant
 *
 * The durable protocol only knows ONE attempt. Every write in the codebase used attempt 1, and
 * the literal `1` appeared in five places — the executor's admission, the executor's terminal
 * append, the recovery engine's terminal append, the resolver's schema rejection and the
 * scripted invoker's fingerprint. [ATTEMPT] is where that literal lives now, and the read
 * ([read]) uses the SAME attempt as the write, so "the row of this attempt" is a single
 * question with a single answer.
 *
 * ## What this class is NOT
 *
 * It is not a second journal. It writes exactly the rows it wrote before, with the same
 * content, in the same order. It is also not the owner of the PARALLEL aggregate: a parallel
 * stage is a composite, not an invocation, and its row is a [CompositeOperation] with its own
 * shape and its own writer.
 */
internal class DurableInvocationRecord(
    private val journal: OperationJournal,
) {

    /**
     * The row of THIS attempt, or `null` when the invocation was never journalled.
     *
     * The attempt is the same one [settle] writes, so a caller that compares the returned row
     * with the row it is about to write is comparing like with like.
     */
    fun read(operationId: String): DurableOperation? = journal.get(operationId, ATTEMPT)

    /**
     * The envelope the divergence gate compares against the journaled row.
     *
     * It is a `PENDING` row: it carries the identity of the invocation being attempted and no
     * output, because nothing has been executed yet. It is NEVER written — the journal only
     * ever holds what [admit] and [settle] wrote — and it exists so that the two callers that
     * reconcile do not each build their own copy of it.
     */
    fun pending(operationId: String, fingerprint: Fingerprint, input: OperationInput): RerunOperation =
        RerunOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = null,
            status = OperationStatus.PENDING,
            attempt = ATTEMPT,
        )

    /**
     * Writes the `RUNNING` row that admits this invocation, but ONLY when there is no row yet.
     *
     * The port throws when `(opId, attempt)` already exists, so the decision of whether to
     * admit belongs here and not at each call site: a caller that admitted unconditionally
     * would fail on every resumed invocation.
     */
    fun admit(
        operationId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        alreadyJournaled: DurableOperation?,
    ) {
        if (alreadyJournaled == null) {
            journal.beginOperation(operationId, ATTEMPT, fingerprint.hex, Json.encodeToString(input))
        }
    }

    /**
     * Writes the terminal row of this invocation, in the ONE shape every terminal path uses.
     *
     * The [output] is the measured fact the caller owns (the execution's own duration, or the
     * reconciliation's), and the row is this class's: the attempt is [ATTEMPT] whatever the
     * caller passed, and the write goes through this port.
     */
    fun settle(
        operationId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        status: OperationStatus,
        output: OperationOutput?,
    ): RerunOperation {
        val row = RerunOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = output,
            status = status,
            attempt = ATTEMPT,
        )
        journal.append(row)
        return row
    }

    companion object {
        /**
         * The attempt the durable protocol knows.
         *
         * Every write in the application layer used the literal `1`; this is the one place that
         * says so, and the read uses it too.
         */
        const val ATTEMPT = 1
    }
}
