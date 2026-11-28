package dev.rubentxu.pipeline.v2.domain.step.artifact

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.StepOutcome

/**
 * Typed handle to an artifact produced by `core.archiveArtifacts`
 * (.ecosystem-local-first cycle).
 * An `ArtifactHandle` is the durable record of one archive operation.
 * It pairs a logical [name] (the user-supplied query key) with the
 * list of [files] that were archived under that name. Each file
 * carries its relative path, sha256 and size (no timestamps — the
 * observable channel `ArtifactArchived` carries timestamps).
 * The handle is the **second** authoritative record of the archive:
 * the **first** is the filesystem itself (the archived files on
 * disk). The handle is a derived projection: it can be re-derived
 * from the artifacts retention directory at any time. It exists so
 * downstream Steps can ask "where is the JAR named 'app'?" without
 * a filesystem scan.
 * The codec round-trips the whole handle; the engine never
 * reconstructs it from individual fields (per project AGENTS.md
 * Strict typed functional design).
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
     * Sorts by `relPath`, concatenates `relPath:sha256` per line, and SHA-256s the bytes
     * The sorting and framing are the contract and are UNCHANGED; only the hex spelling now
     * Comes from the shared utility, so previously computed aggregates still match
*/
    fun aggregateSha256(): String {
        val sorted = files.sortedBy { it.relPath }
        val concat = sorted.joinToString(separator = "\n") { "${it.relPath}:${it.sha256}" }
        return Sha256.ofText(concat)
    }
}

/**
 * Single archived file entry within an [ArtifactHandle].
 * Mirrors the certified `CaptureReceipt`
 * shape — visible path, digest identity, size — and adds the third identity the
 * receipt mints: [captureId], so a consumer that queries the handle by its logical
 * name can still tell two captures of one visible name apart. The bridge from
 * `core.archiveArtifacts` to `core.artifact.query` materialises
 * `CaptureReceipt` records into these ADT entries.
 * @property relPath  workspace-relative path of the archived file
 * @property sha256   sha256 of the file bytes
 * @property sizeBytes file size in bytes
 * @property absolutePath absolute on-disk path inside the run's
 *                        artefacts retention directory
 * @property captureId identity of the capture that produced this entry, minted per
 *                     capture and distinct from every other capture's. Null means the
 *                     entry names no capture — the same explicit absence the durable
 *                     archive entry documents — and no capture-scoped read may be
 *                     resolved from it.
*/
data class ArchivedFileEntry(
    val relPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val absolutePath: String,
    val captureId: String? = null,
) {
    init {
        require(relPath.isNotEmpty()) { "relPath must not be empty" }
        // One predicate, owned by the class that mints the format. The previous form checked
        // `length == 64` alone while its message promised "64 hex characters", so 64 characters
        // of any kind passed: the guard described a format it did not test.
        require(Sha256.isSha256Hex(sha256)) {
            "sha256 must be 64 lowercase hex characters (0-9a-f); got '${sha256.take(8)}...' (${sha256.length} chars)"
        }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative" }
        require(absolutePath.isNotEmpty()) { "absolutePath must not be empty" }
        // A capture identity that is present says WHICH capture; a blank says nothing
        // while pretending to say something, so the absent case stays null, never "".
        require(captureId == null || captureId.isNotBlank()) {
            "captureId must be null or non-blank (a blank identity names no capture)"
        }
    }
}
