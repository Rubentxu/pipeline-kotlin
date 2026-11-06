package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation

/**
 * The ONE place that says what a journal row's `kind` means.
 *
 * ## Why the kind is a fact and not a default
 *
 * The `kind` column is the word the row travels under: it says which KIND of
 * operation the row is, and each kind carries its own replay policy. A reader
 * that does not know the word cannot know the policy, and the policy is what
 * decides whether the operation may run again.
 *
 * So an unknown word is NOT read as the nearest kind this build knows. It is
 * refused, with the word named. Reading it as `RERUN` —the nearest word, and
 * the one the schema defaults to— would invent a policy the row does not
 * have, and RERUN is the direction that REPEATS an effect: a row written by a
 * newer build would be re-executed as if it were a step that never ran.
 *
 * ## The same rule the event wire already follows
 *
 * The durable event wire in this module refuses a kind it does not know as
 * version skew (`EventDecodeFailure.UnknownEventKind`). The operation journal
 * had no such rule and answered RERUN to everything. Two readers of two wires
 * in the same store now answer the same way: a word nobody declared is a fact
 * this build cannot interpret, and the refusal is visible while the invention
 * is not.
 *
 * ## Both directions live here
 *
 * [encode] writes the word and [decode] reads it. The write side is exhaustive
 * over the sealed hierarchy of operations, so this build cannot write a word it
 * cannot read back. The read side is the one that has to cope with a word from
 * ELSEWHERE, and it is the one that refuses.
 */
internal object OperationKindCodec {

    /**
     * The word [op] is written under.
     *
     * Exhaustive over the sealed hierarchy: a new kind of operation does not
     * compile until it says how it is written down, and what it is written down
     * as is what [decode] will read it back as.
     */
    fun encode(op: DurableOperation): String = when (op) {
        is RerunOperation -> RERUN
        is MemoizedOperation -> MEMOIZED
        is CompositeOperation -> COMPOSITE
    }

    /**
     * The operation a row names.
     *
     * The fields are the row's own: the reader hands over what it read and
     * gets back the operation it describes, or a refusal that names the word
     * this build does not know. It never gets back a guess.
     *
     * @param kind The word the row travels under.
     * @param operationId The operation the row belongs to.
     * @param fingerprint The fingerprint the row carries.
     * @param input The input the row carries, already decoded.
     * @param output The output the row carries, already decoded, if it has one.
     * @param status The status the row carries.
     * @param attempt The attempt the row carries.
     * @throws UnknownOperationKindException If [kind] is not a word this build knows.
     */
    fun decode(
        kind: String,
        operationId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        output: OperationOutput?,
        status: OperationStatus,
        attempt: Int,
    ): DurableOperation = when (kind) {
        RERUN -> RerunOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = output,
            status = status,
            attempt = attempt,
        )
        MEMOIZED -> MemoizedOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = output,
            status = status,
            attempt = attempt,
            cachedOutput = output,
        )
        COMPOSITE -> CompositeOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = output,
            status = status,
            attempt = attempt,
            // The row does not carry the children: a composite is journaled as
            // the unit, and its children are rows of their own.
            subOperations = emptyList(),
        )
        else -> throw UnknownOperationKindException(kind = kind, operationId = operationId)
    }

    /**
     * The word a re-executed operation is written under.
     *
     * Not private: a writer that has no operation object yet —the row a
     * beginning operation writes— still has to write the same word the reader
     * reads, and two spellings of it are two chances to disagree.
     */
    const val RERUN = "RERUN"

    /** The word a memoized operation is written under. */
    const val MEMOIZED = "MEMOIZED"

    /** The word a composite operation is written under. */
    const val COMPOSITE = "COMPOSITE"
}

/**
 * A journal row that names a kind this build does not know.
 *
 * ## What this is
 *
 * Version skew, and it is refused rather than read. The row exists — there is
 * an operation in it — but this build cannot say what kind of operation it is,
 * and every kind carries its own replay policy. Answering with the nearest
 * kind this build knows would give the row a policy that nobody wrote.
 *
 * ## Why it is an exception and not a `null`
 *
 * The port this journal satisfies says that `null` means "no entry exists".
 * A row with an unknown kind IS an entry, so `null` would be a different
 * claim: it would say the operation was never journaled. In a listing it would
 * be worse than wrong — the row would simply not appear, and a run would look
 * smaller than the work it had done, which is what makes a recovery repeat an
 * effect that already happened.
 *
 * A refusal that names the word and the row is a fact the caller can act on:
 * the journal belongs to a build that knew more than this one.
 */
class UnknownOperationKindException(
    /** The word the row travels under, which this build does not know. */
    val kind: String,
    /** The operation the row belongs to. */
    val operationId: String,
) : IllegalStateException(
    "the journal row of operation $operationId carries kind \"$kind\", which this build does " +
        "not know; reading it as a kind it knows would invent a replay policy the row does not have",
)
