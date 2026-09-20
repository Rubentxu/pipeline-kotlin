package dev.rubentxu.pipeline.v2.domain.step.artifact

/**
 * SPI for the artifact index — a derived projection of
 * `core.archiveArtifacts` outputs that supports query-by-name from
 * `core.artifact.query`.
 *
 * ## Authoritative sources

 * The index is the **second** authoritative record of an artifact;
 * the **first** is the filesystem (the archived files on disk inside
 * the run's artefacts retention directory). The index can always be
 * re-derived from the filesystem; if they disagree, the filesystem
 * wins.
 *
 * ## Why a typed SPI (and not a Map<String, Any?> public contract)

 * Mirrors the project's hexagonal rule: effectful handlers adapt to
 * typed seams, never embed IO logic. The adapter (the producer of
 * the index entries) lives in `pipeline-application`; the consumer
 * (the `core.artifact.query` Step) consumes the typed interface and
 * never touches the file directly.
 *
 * Implementations:
 * - Must be deterministic for a given set of recorded entries.
 * - Must fail closed on duplicate names (the cycle does NOT authorise
 *   a "last wins" or "first wins" resolution; the SDK requires the
 *   step to surface a typed `USER` failure on duplicate names).
 * - Must be safe under concurrent reads (callers are coroutine
 *   steps). Writes are gated by the archive Step itself, which holds
 *   the typed record atomicity at its boundary.
 */
interface ArtifactIndexCapability {

    /**
     * Record a handle under its [ArtifactHandle.name]. Called by
     * `core.archiveArtifacts` after a successful archive.
     *
     * @throws DuplicateArtifactNameException if a handle with the
     *         same name is already recorded in this run.
     */
    fun record(handle: ArtifactHandle)

    /**
     * Query a handle by name. Returns null if no handle with the
     * given name has been recorded.
     *
     * @param name the user-supplied logical name
     */
    fun query(name: String): ArtifactHandle?

    /**
     * All recorded handles, sorted by name for determinism. Used by
     * the cycle's receipt to enumerate the run's artifact set.
     */
    fun all(): List<ArtifactHandle>
}

/**
 * Closed ADT of failure modes emitted by [ArtifactIndexCapability]
 * implementations. The bridge handler maps these to
 * [dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure] with the
 * declared kind.
 */
sealed interface ArtifactIndexFailure {
    /** Two archives tried to record the same logical name. */
    data class DuplicateName(val name: String) : ArtifactIndexFailure
}

/** Thrown by [ArtifactIndexCapability.record] on duplicate names. */
class DuplicateArtifactNameException(val name: String) :
    RuntimeException("Artifact '$name' is already recorded in this run")
