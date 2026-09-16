package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilControlRowSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * Single-writer durable store for waitUntil control rows.
 *
 * WU-G5R.5 — mirrors [FileBasedRetryControlJournal] but for the waitUntil
 * predicate polling loop. File at `{controlDirRoot}/wait-until-control/{sha256(controlOpId)}.attempts.json`.
 *
 * ## Single-writer law
 * No other component writes to the control file. The dispatch loop in
 * [CanonicalDurableRunCoordinator] is the only caller of [beginAttempt] and
 * [updateStatus].
 *
 * ## Persist-before-effects contract
 * [beginAttempt] writes the control row to the file BEFORE returning. The caller
 * MUST launch any predicate body effect only after [beginAttempt] has returned.
 */
class FileBasedWaitUntilControlJournal(
    private val controlDirRoot: Path,
) : WaitUntilControlJournal {
    private val waitUntilControlDir: Path = controlDirRoot.resolve("wait-until-control")

    init {
        Files.createDirectories(waitUntilControlDir)
    }

    private fun controlFile(controlOpId: String): Path =
        waitUntilControlDir.resolve("${fileKey(controlOpId)}.attempts.json")

    private fun fileKey(controlOpId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(controlOpId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun beginAttempt(
        controlOpId: String,
        attempt: Int,
        currentBackoffMs: Long,
        fingerprint: Fingerprint,
        status: OperationStatus,
    ) {
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        val existing = readFile(controlOpId)
        val prior = existing.attempts.firstOrNull { it.attempt == attempt }
        if (prior != null) {
            if (prior.fingerprint != fingerprint.hex) {
                throw WaitUntilControlJournalDivergenceException(
                    "beginAttempt fingerprint mismatch for $controlOpId@$attempt: " +
                        "stored=${prior.fingerprint} incoming=${fingerprint.hex}",
                )
            }
            if (prior.status == status) {
                return // Idempotent.
            }
        }
        val updated = existing.copy(
            attempts = existing.attempts
                .filter { it.attempt != attempt }
                .plusOne(
                    PersistedWaitUntilAttempt(
                        attempt = attempt,
                        status = status,
                        currentBackoffMs = currentBackoffMs,
                        fingerprint = fingerprint.hex,
                    ),
                ),
            fingerprint = fingerprint.hex,
        )
        writeFile(controlOpId, updated)
    }

    override fun updateStatus(
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
            throw WaitUntilControlJournalDivergenceException(
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

    override fun readState(
        controlOpId: String,
        initialRecurrencePeriodMs: Long,
        maxBackoffMs: Long,
        currentFingerprint: Fingerprint,
    ): WaitUntilControlState {
        val file = readFile(controlOpId)
        val rows = file.attempts
            .map {
                WaitUntilControlRowSnapshot(
                    attempt = it.attempt,
                    status = it.status,
                    currentBackoffMs = it.currentBackoffMs,
                    fingerprint = Fingerprint(file.fingerprint),
                )
            }
            .sortedBy { it.attempt }
        return WaitUntilControlState(
            controlRows = rows,
        )
    }

    private fun readFile(controlOpId: String): WaitUntilControlFile {
        val file = controlFile(controlOpId)
        if (!Files.exists(file)) {
            return WaitUntilControlFile(
                schemaVersion = WaitUntilControlJournal.SCHEMA_VERSION,
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
                throw WaitUntilControlJournalDivergenceException(
                    "control file for $controlOpId maps to a different identity: $storedOpId",
                )
            }
            val fingerprint = obj["fingerprint"]?.jsonPrimitive?.content ?: ""
            val attemptsJson = obj["attempts"]?.jsonArray ?: buildJsonArray { }
            val attempts = attemptsJson.map { el ->
                val ao = el.jsonObject
                PersistedWaitUntilAttempt(
                    attempt = ao["attempt"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: throw WaitUntilControlJournalDivergenceException("attempt missing in $file"),
                    status = ao["status"]?.jsonPrimitive?.content
                        ?.let { OperationStatus.valueOf(it) }
                        ?: throw WaitUntilControlJournalDivergenceException("status missing in $file"),
                    currentBackoffMs = ao["currentBackoffMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    fingerprint = ao["fingerprint"]?.jsonPrimitive?.content ?: fingerprint,
                )
            }
            WaitUntilControlFile(WaitUntilControlJournal.SCHEMA_VERSION, controlOpId, fingerprint, attempts)
        } catch (e: SerializationException) {
            throw WaitUntilControlJournalDivergenceException(
                "control file corrupted at $file: ${e.message}",
            )
        }
    }

    private fun writeFile(controlOpId: String, contents: WaitUntilControlFile) {
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
                        put("currentBackoffMs", a.currentBackoffMs)
                        put("fingerprint", a.fingerprint)
                    })
                }
            })
        }
        val tmp = Files.createTempFile(file.parent, "waituntil-ctrl-", ".tmp")
        try {
            Files.writeString(tmp, JSON.encodeToString(payload), Charsets.UTF_8)
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun List<PersistedWaitUntilAttempt>.plusOne(new: PersistedWaitUntilAttempt): List<PersistedWaitUntilAttempt> =
        filter { it.attempt != new.attempt } + new

    private companion object {
        private val JSON = Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
internal data class WaitUntilControlFile(
    val schemaVersion: Int,
    val controlOpId: String,
    val fingerprint: String,
    val attempts: List<PersistedWaitUntilAttempt>,
)

@Serializable
internal data class PersistedWaitUntilAttempt(
    val attempt: Int,
    val status: OperationStatus,
    val currentBackoffMs: Long,
    val fingerprint: String,
)
