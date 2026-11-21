package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * The durable facts of ONE operation, kept in the operation journal.
 *
 * ## Why the journal and not a store of its own
 *
 * The journal is the run's durable record of what it executed, and it is already the place
 * where a fact outlives the process that observed it. A second store would be a second
 * answer to "what did this run leave behind", and the two would be free to disagree.
 *
 * ## Why the facts are filed under a namespace of their own
 *
 * The engine writes a row of its own for every invocation it dispatches, under the
 * operation's identity. A step that records its own facts writes to the same table, and
 * the two rows would overwrite each other: the engine's row carries the invocation's
 * fingerprint, and a fact written over it would make the next run declare a divergence
 * against a program that did not change.
 *
 * So the facts live under their own operation id and their own run id, both derived from
 * the operation's identity by [DurableOperationFactsStore]. The derivation is ONE rule and
 * it lives in ONE place, so the row a step's facts occupy is named by the same place for
 * every caller.
 *
 * The namespace is also what keeps the facts out of the engine's per-run views: the rows a
 * run lists are the engine's own operations, and a step's private record is not one of
 * them. The facts are found by their operation id, which is what the port hands over.
 *
 * ## What it stores
 *
 * The status of the operation, and the terminal the step observed. The terminal is stored
 * as the JSON of the terminal itself, so what is read back is what was recorded.
 */
internal class JournalBackedOperationFacts(
    private val journal: OperationJournal,
    private val clock: Clock,
    override val identity: DurableOperationIdentity,
) : DurableOperationFacts {

    override fun recordedStatus(): OperationStatus? =
        DurableOperationFactsStore.read(journal, identity.opId)?.status

    override fun recordedTerminal(): DurableTaskTerminal? =
        DurableOperationFactsStore.recordedTerminal(journal, identity.opId)

    override fun begin() {
        DurableOperationFactsStore.write(
            journal = journal,
            runId = identity.runId,
            operationId = identity.opId,
            status = OperationStatus.RUNNING,
            output = null,
        )
    }

    override fun record(terminal: DurableTaskTerminal) {
        DurableOperationFactsStore.write(
            journal = journal,
            runId = identity.runId,
            operationId = identity.opId,
            status = OperationStatus.SUCCEEDED,
            output = OperationOutput(
                result = JsonPrimitive(DurableOperationFactsStore.encode(terminal)),
                durationMs = 0L,
                finishedAt = clock.now().toEpochMilli(),
            ),
        )
    }
}

/**
 * The reader of the durable facts of an operation, by the operation's id.
 *
 * The engine reads the facts of an operation it is recovering: a step whose effect
 * happened outside the engine's durable substrate recorded what that effect produced
 * before the process died, and that record is the terminal the recovery has to use.
 */
internal fun interface DurableOperationFactsReader {

    /**
     * The terminal the operation recorded, or `null` when it recorded none.
     */
    fun recordedTerminal(operationId: String): DurableTaskTerminal?
}

/**
 * The ONE place where the facts of an operation are named, written and read.
 *
 * The namespace, the row's shape and the encoding of a terminal live here, so the port
 * that hands them to a step and the reader that recovers them cannot disagree about
 * where the facts of an operation are.
 */
internal object DurableOperationFactsStore {

    /**
     * The suffix that names the facts of an operation.
     *
     * The engine's own rows are named by the operation's id, and this suffix is what
     * keeps the facts of an operation from being one of them.
     */
    const val SUFFIX = "-facts"

    /** The attempt the facts of an operation are filed under. */
    const val ATTEMPT = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The operation id the facts of an operation are filed under. */
    fun opIdOf(operationId: String): String = operationId + SUFFIX

    /** The run id the facts of an operation belong to. */
    fun runIdOf(runId: String): String = runId + SUFFIX

    /** The row of the facts of an operation, or `null` when nothing was recorded. */
    fun read(journal: OperationJournal, operationId: String): DurableOperation? =
        journal.get(opIdOf(operationId), ATTEMPT)

    /** The terminal the facts of an operation hold, or `null` when they hold none. */
    fun recordedTerminal(journal: OperationJournal, operationId: String): DurableTaskTerminal? {
        val recorded = read(journal, operationId)?.output?.result as? JsonPrimitive ?: return null
        return runCatching { json.decodeFromString<DurableTaskTerminal>(recorded.content) }.getOrNull()
    }

    /** The encoding of a terminal, as the facts store it. */
    fun encode(terminal: DurableTaskTerminal): String = json.encodeToString(terminal)

    /**
     * Writes the row of the facts of an operation, in the ONE shape every write uses.
     *
     * The row's clock reading belongs to the caller: the caller owns the output it
     * writes, and the output carries the moment the turn finished. The store does not
     * read a clock of its own, so nothing it writes says when it wrote it.
     */
    fun write(
        journal: OperationJournal,
        runId: String,
        operationId: String,
        status: OperationStatus,
        output: OperationOutput?,
    ) {
        val factsOpId = opIdOf(operationId)
        val input = OperationInput(
            stepId = factsOpId,
            params = emptyMap(),
            runId = runIdOf(runId),
            attempt = ATTEMPT,
        )
        journal.append(
            RerunOperation(
                id = factsOpId,
                fingerprint = Fingerprint.compute(input, factsOpId, ReplayPolicy.RERUN, ATTEMPT),
                input = input,
                output = output,
                status = status,
                attempt = ATTEMPT,
            ),
        )
    }
}
