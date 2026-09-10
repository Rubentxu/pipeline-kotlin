package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlRowSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Single-writer durable store for retry control rows.
 *
 * ADR-0075 §4 + §6 + §11 — the retry control row is the durable source of
 * truth for the retry aggregate's state at attempt N. Each retry logical
 * invocation has exactly one control file under [controlDirRoot]:
 *
 * <pre>
 * {controlDirRoot}/retry-control/{sha256(controlOpId)}.attempts.json
 * </pre>
 *
 * ## Single-writer law
 * No other component writes to the control file. The dispatch loop in
 * [dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator]
 * is the only caller of [beginAttempt] and [updateStatus]. Child executors,
 * step handlers, [dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShExecution],
 * and event projectors MUST NOT mutate the control file.
 *
 * ## Persist-before-effects contract
 * [beginAttempt] writes the control row to the file BEFORE returning. The
 * caller MUST launch any child effect only after [beginAttempt] has
 * returned without throwing. The atomic-rename write ensures a crashed
 * worker never sees a half-written file.
 *
 * ## Read shape
 * [readState] returns the persisted control rows for the given retry,
 * supplemented (when a [ChildRowReader] is bound) by per-attempt child rows
 * read from the canonical [dev.rubentxu.pipeline.v2.events.durable.OperationJournal].
 * Pre-ADR-0075 legacy children — those without an associated control row —
 * are routed through [readState]'s [RetryControlState.preControlChildren]
 * bucket so the Reconciler can apply the legacy compat policy.
 *
 * ## Fingerprint propagation
 * The retry contract fingerprint is stored at the file root and copied
 * into every [RetryControlRowSnapshot.fingerprint] on read. The Reconciler
 * compares the stored fingerprint against [currentFingerprint] to detect
 * contract drift.
 */
class FileBasedRetryControlJournal(
    private val controlDirRoot: Path,
    private val childRowReader: ChildRowReader = NullChildRowReader,
) {
    private val retryControlDir: Path = controlDirRoot.resolve("retry-control")

    init {
        Files.createDirectories(retryControlDir)
    }

    private fun controlFile(controlOpId: String): Path =
        retryControlDir.resolve("${fileKey(controlOpId)}.attempts.json")

    private fun fileKey(controlOpId: String): String {
        // SHA-256 hex of the controlOpId — safe file-name character set, fixed length.
        val digest = MessageDigest.getInstance("SHA-256").digest(controlOpId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Convenience: derive the canonical control opId for a retry context. */
    fun controlOpIdFor(
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
    ): String = RetryIdentityFactory.controlOperationId(runId, stageIndex, stepIndex, parentBodyPath)

    /**
     * Persist a control row indicating that attempt [attempt] of the retry
     * identified by [controlOpId] has been started with the given
     * [fingerprint] and aggregate [status] (typically
     * [OperationStatus.PENDING] or [OperationStatus.RUNNING]).
     *
     * Idempotent for the same (controlOpId, attempt, fingerprint) triple;
     * a second call with the same triple updates [status] in place. A
     * second call with a different fingerprint is a contract divergence
     * and throws [RetryControlJournalDivergenceException].
     *
     * ADR-0075 §6 — the dispatch loop MUST call this BEFORE launching any
     * child effect for the attempt.
     */
    fun beginAttempt(
        controlOpId: String,
        attempt: Int,
        fingerprint: Fingerprint,
        status: OperationStatus,
    ) {
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        val existing = readFile(controlOpId)
        // Existing attempts.
        val prior = existing.attempts.firstOrNull { it.attempt == attempt }
        if (prior != null) {
            if (prior.fingerprint != fingerprint.hex) {
                throw RetryControlJournalDivergenceException(
                    "beginAttempt fingerprint mismatch for $controlOpId@$attempt: " +
                        "stored=${prior.fingerprint} incoming=${fingerprint.hex}",
                )
            }
            if (prior.status == status) {
                // Same status, same fingerprint, same attempt → no-op.
                return
            }
        }
        val updated = existing.copy(
            attempts = existing.attempts
                .filter { it.attempt != attempt }
                .plusOne(
                    PersistedAttempt(
                        attempt = attempt,
                        status = status,
                        fingerprint = fingerprint.hex,
                    ),
                ),
            fingerprint = fingerprint.hex,
        )
        writeFile(controlOpId, updated)
    }

    /**
     * Update the aggregate status of an existing attempt. Throws
     * [RetryControlJournalDivergenceException] if the persisted fingerprint
     * differs from [fingerprint]. The control row is rewritten atomically.
     */
    fun updateStatus(
        controlOpId: String,
        attempt: Int,
        status: OperationStatus,
        fingerprint: Fingerprint,
    ) {
        val existing = readFile(controlOpId)
        val prior = existing.attempts.firstOrNull { it.attempt == attempt }
            ?: throw IllegalStateException(
                "updateStatus: no control row for $controlOpId@$attempt; call beginAttempt first",
            )
        if (prior.fingerprint != fingerprint.hex) {
            throw RetryControlJournalDivergenceException(
                "updateStatus fingerprint mismatch for $controlOpId@$attempt",
            )
        }
        if (prior.status == status) return
        val updated = existing.copy(
            attempts = existing.attempts.map {
                if (it.attempt == attempt) it.copy(status = status) else it
            },
            fingerprint = fingerprint.hex,
        )
        writeFile(controlOpId, updated)
    }

    /**
     * Read the durable state for one retry logical invocation.
     *
     * The returned [RetryControlState] carries:
     *  - [RetryControlState.controlRows] from the persisted control file;
     *  - [RetryControlState.childrenByAttempt] from [ChildRowReader] when bound;
     *  - [RetryControlState.preControlChildren] from [ChildRowReader.preControlRowsForControl]
     *    when the bound reader exposes pre-ADR-0075 children.
     */
    fun readState(
        controlOpId: String,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
        currentFingerprint: Fingerprint,
    ): RetryControlState {
        val file = readFile(controlOpId)
        val rows = file.attempts
            .map {
                RetryControlRowSnapshot(
                    attempt = it.attempt,
                    status = it.status,
                    fingerprint = Fingerprint(file.fingerprint),
                )
            }
            .sortedBy { it.attempt }
        val childRows = childRowReader.childrenForControlOpId(
            controlOpId = controlOpId,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            parentBodyPath = parentBodyPath,
            maxAttempts = maxAttempts,
        )
        val byAttempt = childRows
            .filter { it.attempt >= 1 }
            .groupBy { it.attempt }
        val preControl = childRows.filter { it.attempt < 1 }
        return RetryControlState(
            controlRows = rows,
            childrenByAttempt = byAttempt,
            preControlChildren = preControl,
        )
    }

    /** Atomically read (or initialize) the on-disk control file. */
    private fun readFile(controlOpId: String): ControlFile {
        val file = controlFile(controlOpId)
        if (!Files.exists(file)) {
            return ControlFile(
                schemaVersion = SCHEMA_VERSION,
                controlOpId = controlOpId,
                fingerprint = "",
                attempts = emptyList(),
            )
        }
        val text = Files.readString(file, Charsets.UTF_8)
        return try {
            val obj = JSON.parseToJsonElement(text).jsonObject
            val storedOpId = obj["controlOpId"]?.jsonPrimitive?.content
            if (storedOpId != null && storedOpId != controlOpId) {
                throw RetryControlJournalDivergenceException(
                    "control file for $controlOpId maps to a different identity: $storedOpId",
                )
            }
            val fingerprint = obj["fingerprint"]?.jsonPrimitive?.content ?: ""
            val schemaVersion = obj["schemaVersion"]?.jsonPrimitive?.intOrNull ?: SCHEMA_VERSION
            val attemptsJson = obj["attempts"]?.jsonArray ?: buildJsonArray { }
            val attempts = attemptsJson.map { el ->
                val ao = el.jsonObject
                PersistedAttempt(
                    attempt = ao["attempt"]?.jsonPrimitive?.intOrNull
                        ?: throw RetryControlJournalDivergenceException("attempt missing in $file"),
                    status = ao["status"]?.jsonPrimitive?.content
                        ?.let { OperationStatus.valueOf(it) }
                        ?: throw RetryControlJournalDivergenceException("status missing in $file"),
                    fingerprint = ao["fingerprint"]?.jsonPrimitive?.content ?: fingerprint,
                )
            }
            ControlFile(schemaVersion, controlOpId, fingerprint, attempts)
        } catch (e: SerializationException) {
            throw RetryControlJournalDivergenceException(
                "control file corrupted at $file: ${e.message}",
            )
        }
    }

    /** Atomically write the on-disk control file. */
    private fun writeFile(controlOpId: String, contents: ControlFile) {
        val file = controlFile(controlOpId)
        Files.createDirectories(file.parent)
        val payload = buildJsonObject {
            put("schemaVersion", contents.schemaVersion)
            put("controlOpId", contents.controlOpId)
            put("fingerprint", contents.fingerprint)
            put("attempts", buildJsonArray {
                contents.attempts.forEach { a ->
                    add(buildJsonObject {
                        put("attempt", a.attempt)
                        put("status", a.status.name)
                        put("fingerprint", a.fingerprint)
                    })
                }
            })
        }
        val tmp = Files.createTempFile(file.parent, "retry-ctrl-", ".tmp")
        try {
            Files.writeString(tmp, JSON.encodeToString(payload), Charsets.UTF_8)
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // Best-effort cleanup if move threw.
            Files.deleteIfExists(tmp)
        }
    }

    /** Replace-then-add helper: replaces an existing attempt OR appends a new one. */
    private fun List<PersistedAttempt>.plusOne(new: PersistedAttempt): List<PersistedAttempt> =
        filter { it.attempt != new.attempt } + new

    private companion object {
        private const val SCHEMA_VERSION = 1
        private val JSON = Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * Pure value object carrying one persisted attempt inside the control file.
 * The fingerprint is lifted to the file root and reproduced on read.
 */
internal data class PersistedAttempt(
    val attempt: Int,
    val status: OperationStatus,
    val fingerprint: String,
)

/**
 * On-disk schema for one retry logical invocation. The fingerprint is
 * stored at the file root and applies to every attempt — a different
 * fingerprint would have produced a different controlOpId (and thus a
 * different file), so the file implicitly partitions attempts by contract.
 */
internal data class ControlFile(
    val schemaVersion: Int,
    val controlOpId: String,
    val fingerprint: String,
    val attempts: List<PersistedAttempt>,
)

/**
 * Thrown when a write would produce a control row whose fingerprint
 * conflicts with the persisted one. ADR-0075 §9 — fail-closed on contract
 * divergence.
 */
class RetryControlJournalDivergenceException(message: String) : IllegalStateException(message)

/**
 * Reads per-attempt child rows for a retry. Implementations look up rows
 * in the canonical [dev.rubentxu.pipeline.v2.events.durable.OperationJournal]
 * by opId pattern (or, in the absence of a richer query API, by runId
 * scan + filter).
 *
 * The contract returns the merged (legacy + post-ADR) child view so
 * [FileBasedRetryControlJournal] can route them into the right
 * [RetryControlState] bucket.
 */
fun interface ChildRowReader {
    fun childrenForControlOpId(
        controlOpId: String,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
    ): List<RetryChildRowSnapshot>
}

/**
 * Default no-op child row reader. Yields no child rows; useful for
 * tests and for runs that don't track child durable facts (e.g., a
 * dry-run or a pure step that never writes a journal row).
 */
object NullChildRowReader : ChildRowReader {
    override fun childrenForControlOpId(
        controlOpId: String,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
    ): List<RetryChildRowSnapshot> = emptyList()
}
