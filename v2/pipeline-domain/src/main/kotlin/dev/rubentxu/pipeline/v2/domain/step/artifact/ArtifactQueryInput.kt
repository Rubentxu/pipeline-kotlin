package dev.rubentxu.pipeline.v2.domain.step.artifact

/**
 * Typed input envelopes for the artifact bridge (E1.ecosystem-local-first).
 *
 * The codec round-trips these envelopes; the engine never
 * reconstructs them from individual fields.
 */

/**
 * Optional name to attach to an archive. When supplied, the
 * archive's files are recorded in the [ArtifactIndexCapability]
 * under this name; subsequent `core.artifact.query(name=...)` calls
 * can retrieve the handle.
 *
 * When null (the default), the archive behaves as before: files are
 * copied to the retention directory, no index entry is recorded.
 *
 * Backward-compatibility: adding this field does NOT change the
 * legacy `core.archiveArtifacts` behaviour for callers that omit it.
 */
data class ArtifactName(val value: String) {
    init {
        require(value.isNotEmpty()) { "ArtifactName.value must not be empty" }
        require(value.isNotBlank()) { "ArtifactName.value must not be blank" }
        require('\n' !in value && '\r' !in value) {
            "ArtifactName.value must not contain newlines"
        }
    }
}

/**
 * Input envelope for `core.artifact.query`.
 *
 * @property name the logical name to look up in the artifact index
 */
data class ArtifactQueryInput(val name: String) {
    init {
        require(name.isNotEmpty()) { "ArtifactQueryInput.name must not be empty" }
        require(name.isNotBlank()) { "ArtifactQueryInput.name must not be blank" }
    }
}
