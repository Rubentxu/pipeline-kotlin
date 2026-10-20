package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactHandle
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
import dev.rubentxu.pipeline.v2.domain.step.artifact.DuplicateArtifactNameException
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import java.util.concurrent.ConcurrentHashMap

/**
 * Production in-memory implementation of [ArtifactIndexCapability]
 * (E1.ecosystem-local-first cycle).
 *
 * One instance per pipeline run. The instance is bound at
 * composition root (`PipelineCompositionRoot`); the
 * `core.archiveArtifacts` handler and the future `core.artifact.query`
 * handler both receive it via the `ARTIFACT_INDEX_CAPABILITY`
 * capability.
 *
 * The instance is intentionally ephemeral (lives in process memory
 * for the duration of the run). The **filesystem is the durable
 * record**; this index is a derived projection. If the process
 * crashes mid-run, the index is lost, but the archived files on
 * disk persist; the next run could re-derive the index from the
 * filesystem (out of cycle scope; recorded as a future-work item).
 *
 * Thread-safety: backed by [ConcurrentHashMap]. Writes are atomic
 * via `compute`; duplicate detection is fail-closed (no last-wins,
 * no first-wins).
 */
class InMemoryArtifactIndex : ArtifactIndexCapability {

    private val byName: ConcurrentHashMap<String, ArtifactHandle> = ConcurrentHashMap()

    override fun record(handle: ArtifactHandle) {
        val existing = byName.putIfAbsent(handle.name, handle)
        if (existing != null) {
            throw DuplicateArtifactNameException(handle.name)
        }
    }

    override fun query(name: String): ArtifactHandle? = byName[name]

    override fun all(): List<ArtifactHandle> =
        byName.values.sortedBy { it.name }
}

/**
 * Capability key for the [ArtifactIndexCapability]. The handlers of
 * `core.archiveArtifacts` and `core.artifact.query` declare this
 * capability in their contracts; composition root admits it.
 */
val ARTIFACT_INDEX_CAPABILITY: StepCapability = StepCapability("artifact-index.operations")
