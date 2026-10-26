package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import kotlinx.serialization.json.Json

/**
 * In-memory [OperationJournal]: the storage-free counterpart of
 * [SqliteOperationJournalImpl] with mirrored semantics, row for row.
 * Per the Single Runtime Spine rule ("Storage, CLI flag, local/remote
 * mode o step type no seleccionan otro algoritmo de ejecución"), choosing
 * volatile storage must never select a different execution algorithm.
 * This journal lets the SAME durable spine run without a database:
 * same walker, same reconcilers, same replay semantics — nothing
 * survives the process.
 * ## Mirrored SQLite semantics (intentional fidelity)
 * - [beginOperation] writes a RUNNING row (kind `RERUN`, raw input JSON,
 *   `startedAt` = `createdAt` = now) and throws
 *   [IllegalStateException] on a duplicate `(opId, attempt)`. The row is
 *   attributed to its run AT BIRTH, exactly like the SQLite implementation:
 *   the run id comes from the operation's own input, so a RUNNING row is
 *   visible to [listForRun] while the operation is still running. An
 *   unattributed RUNNING row made a run that died mid-operation look
 *   finished to any reader, and made the answer depend on which store lane
 *   was asking — a difference no store choice is allowed to introduce.
 * - [append] upserts on `(opId, attempt)`: on conflict ONLY `status`,
 *   `output`, `updatedAt`, `endedAt`, and run id are updated;
 *   `startedAt`/`createdAt`/fingerprint/input/deadline are preserved.
 *   `endedAt` is stamped only for terminal statuses.
 * - [get] by opId returns the highest-attempt row.
 * - [listForRun] filters on the stored run id, ordered by `createdAt`.
 * All mutations are serialized on a single monitor (the SQLite impl
 * achieves the same via DbLock + WAL).
*/
class InMemoryOperationJournal(
    private val clock: Clock,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : OperationJournal {

    private class Row(
        val opId: String,
        val attempt: Int,
        var fingerprintHex: String,
        var status: OperationStatus,
        var kind: String,
        var inputJson: String,
        var outputJson: String?,
        var startedAt: Long,
        val createdAt: Long,
        var updatedAt: Long,
        var endedAt: Long?,
        var deadlineMs: Long?,
        var runId: String?,
    )

    private val rows = LinkedHashMap<String, Row>() // key: "$opId\x1F$attempt"
    private val lock = Any()

    private fun key(opId: String, attempt: Int): String = "$opId\u0000$attempt"

    override fun append(op: DurableOperation, deadlineMs: Long?) {
        synchronized(lock) {
            val inputJson = json.encodeToString(OperationInput.serializer(), op.input)
            val outputJson = op.output?.let { json.encodeToString(OperationOutput.serializer(), it) }
            val kind = when (op) {
                is RerunOperation -> "RERUN"
                is MemoizedOperation -> "MEMOIZED"
                is CompositeOperation -> "COMPOSITE"
            }
            val now = clock.now().toEpochMilli()
            val endedAtVal: Long? = if (op.status.isTerminal) now else null
            val existing = rows[key(op.id, op.attempt)]
            if (existing == null) {
                rows[key(op.id, op.attempt)] = Row(
                    opId = op.id,
                    attempt = op.attempt,
                    fingerprintHex = op.fingerprint.hex,
                    status = op.status,
                    kind = kind,
                    inputJson = inputJson,
                    outputJson = outputJson,
                    startedAt = now,
                    createdAt = now,
                    updatedAt = now,
                    endedAt = endedAtVal,
                    deadlineMs = deadlineMs,
                    runId = op.input.runId,
                )
            } else {
                // Conflict path: preserve startedAt/createdAt/fingerprint/
                // input/deadline — update only status/output/endedAt/runId.
                existing.status = op.status
                existing.outputJson = outputJson
                existing.updatedAt = now
                existing.endedAt = endedAtVal
                existing.runId = op.input.runId
            }
        }
    }

    override fun beginOperation(
        opId: String,
        attempt: Int,
        fingerprint: String,
        inputJson: String,
        deadlineMs: Long?,
    ) {
        synchronized(lock) {
            if (rows.containsKey(key(opId, attempt))) {
                throw IllegalStateException("Operation $opId attempt $attempt already journaled")
            }
            // The attribution comes from the operation's own input, which is
            // where the run id was written down in the first place. A
            // malformed input fails here, loudly, instead of producing a row
            // that belongs to no run.
            val runId = json.decodeFromString(OperationInput.serializer(), inputJson).runId
            val now = clock.now().toEpochMilli()
            rows[key(opId, attempt)] = Row(
                opId = opId,
                attempt = attempt,
                fingerprintHex = fingerprint,
                status = OperationStatus.RUNNING,
                kind = "RERUN",
                inputJson = inputJson,
                outputJson = null,
                startedAt = now,
                createdAt = now,
                updatedAt = now,
                endedAt = null,
                deadlineMs = deadlineMs,
                runId = runId,
            )
        }
    }

    override fun get(opId: String): DurableOperation? = synchronized(lock) {
        rows.values.filter { it.opId == opId }.maxByOrNull { it.attempt }?.let { parse(it) }
    }

    override fun get(opId: String, attempt: Int): DurableOperation? = synchronized(lock) {
        rows[key(opId, attempt)]?.let { parse(it) }
    }

    override fun listForRun(runId: String): List<DurableOperation> = synchronized(lock) {
        rows.values
            .filter { it.runId == runId }
            .sortedBy { it.createdAt }
            .map { parse(it) }
    }

    override fun getDeadlineMs(opId: String, attempt: Int): Long? = synchronized(lock) {
        rows[key(opId, attempt)]?.deadlineMs
    }

    override fun getEndedAt(opId: String, attempt: Int): Long? = synchronized(lock) {
        rows[key(opId, attempt)]?.endedAt
    }

    override fun getStartedAt(opId: String, attempt: Int): Long? = synchronized(lock) {
        rows[key(opId, attempt)]?.startedAt
    }

    /** Mirror of the SQLite `readOperation`: parse the row back to its [DurableOperation] variant. */
    private fun parse(row: Row): DurableOperation {
        val fingerprint = Fingerprint(row.fingerprintHex)
        val input = json.decodeFromString(OperationInput.serializer(), row.inputJson)
        val output = row.outputJson?.let { json.decodeFromString(OperationOutput.serializer(), it) }
        return when (row.kind) {
            "MEMOIZED" -> MemoizedOperation(
                id = row.opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = row.status,
                attempt = row.attempt,
                cachedOutput = output,
            )
            "COMPOSITE" -> CompositeOperation(
                id = row.opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = row.status,
                attempt = row.attempt,
                subOperations = emptyList(),
            )
            else -> RerunOperation(
                id = row.opId,
                fingerprint = fingerprint,
                input = input,
                output = output,
                status = row.status,
                attempt = row.attempt,
            )
        }
    }

    /** Number of rows currently journaled (diagnostics/testing). */
    fun size(): Int = synchronized(lock) { rows.size }
}
