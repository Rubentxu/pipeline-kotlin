package dev.rubentxu.pipeline.v2.domain.step.artifact

import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * Typed handle to an artifact produced by `core.archiveArtifacts`
 * (E1.ecosystem-local-first cycle).
 *
 * An `ArtifactHandle` is the durable record of one archive operation.
 * It pairs a logical [name] (the user-supplied query key) with the
 * list of [files] that were archived under that name. Each file
 * carries its relative path, sha256 and size (no timestamps — the
 * observable channel `ArtifactArchived` carries timestamps).
 *
 * The handle is the **second** authoritative record of the archive:
 * the **first** is the filesystem itself (the archived files on
 * disk). The handle is a derived projection: it can be re-derived
 * from the artifacts retention directory at any time. It exists so
 * downstream Steps can ask "where is the JAR named 'app'?" without
 * a filesystem scan.
 *
 * The codec round-trips the whole handle; the engine never
 * reconstructs it from individual fields (per project AGENTS.md
 * §Strict typed functional design).
 *
 * @property name the user-supplied logical name; non-empty
 * @property files the archived files under this name
 */
data class ArtifactHandle(
    val name: String,
    val files: List<ArchivedFileEntry>,
) : TypedStepOutput {

    init {
        require(name.isNotEmpty()) { "ArtifactHandle.name must not be empty" }
        require(name.isNotBlank()) { "ArtifactHandle.name must not be blank" }
    }

    override val outcome: StepOutcome = StepOutcome.Success

    /**
     * Convenience: the first file under this handle, or null if no
     * files were archived (which is permitted when
     * `allowEmptyArchive=true`).
     */
    fun primaryFile(): ArchivedFileEntry? = files.firstOrNull()

    /**
     * Convenience: aggregate sha256 over all files (sorted by relPath
     * for determinism). The output is NOT a content hash; it is a
     * stable handle fingerprint that changes iff any file's sha256
     * changes.
     */
    fun aggregateSha256(): String {
        val sorted = files.sortedBy { it.relPath }
        val concat = sorted.joinToString(separator = "\n") { "${it.relPath}:${it.sha256}" }
        return sha256Hex(concat.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}

/**
 * Single archived file entry within an [ArtifactHandle].
 *
 * Mirrors the certified [dev.rubentxu.pipeline.v2.application.ArchivedFileSummary]
 * shape (relPath / sha256 / size) without timestamps. The bridge from
 * `core.archiveArtifacts` to `core.artifact.query` materialises
 * `ArchivedFileSummary` records into these ADT entries.
 *
 * @property relPath  workspace-relative path of the archived file
 * @property sha256   sha256 of the file bytes
 * @property sizeBytes file size in bytes
 * @property absolutePath absolute on-disk path inside the run's
 *                        artefacts retention directory
 */
data class ArchivedFileEntry(
    val relPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val absolutePath: String,
) {
    init {
        require(relPath.isNotEmpty()) { "relPath must not be empty" }
        require(sha256.length == 64) {
            "sha256 must be 64 hex characters; got '${sha256.take(8)}...'"
        }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative" }
        require(absolutePath.isNotEmpty()) { "absolutePath must not be empty" }
    }
}
