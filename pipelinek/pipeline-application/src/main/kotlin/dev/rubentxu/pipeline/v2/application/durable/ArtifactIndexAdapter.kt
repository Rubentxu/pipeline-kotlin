package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.ARTIFACT_INDEX_CAPABILITY
import dev.rubentxu.pipeline.v2.application.InMemoryArtifactIndex
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability

/**
 * Adapter exposing the run-scoped [ArtifactIndexCapability] to the
 * canonical runtime.
 *
 * E1.ecosystem-local-first / T1: every pipeline run gets ONE
 * [InMemoryArtifactIndex] instance, constructed at composition root and
 * shared between the producer (`core.archiveArtifacts`) and the consumer
 * (`core.artifact.query`) through the `ARTIFACT_INDEX_CAPABILITY` seam.
 *
 * The instance lifetime is the run lifetime. The **filesystem remains
 * the durable record** of archived files; the index is a derived
 * projection, intentionally ephemeral (see [InMemoryArtifactIndex] KDoc).
 * If the process crashes mid-run, the archived files on disk survive
 * but the index is lost — the next run could re-derive the index from
 * the filesystem (out of E1 cycle scope).
 *
 * Thread-safety: the underlying [InMemoryArtifactIndex] is backed by a
 * `ConcurrentHashMap` and is safe for concurrent reads from multiple
 * handler invocations within the run.
 *
 * Scope firewall: zero behaviour change for `core.archiveArtifacts`
 * when `input.name == null` (legacy callers unaffected). Only the
 * NAMED mode (`input.name != null`) consults the index; the absence
 * of the index is a typed SCRIPT failure, not a runtime exception.
 *
 * @see dev.rubentxu.pipeline.v2.application.InMemoryArtifactIndex
 * @see CoreArchiveArtifactsStep capability-routed handler
 * @see CoreArtifactQueryStep capability-routed handler
 */
object ArtifactIndexAdapter {
    /**
     * Build a fresh run-scoped artifact index. One call per run; the
     * returned instance is the single writer of `ArtifactHandle` records
     * within the run and the single reader for query-by-name.
     */
    fun build(): ArtifactIndexCapability = InMemoryArtifactIndex()

    /** The capability key under which the index is exposed to handlers. */
    val capability: dev.rubentxu.pipeline.v2.domain.step.StepCapability
        get() = ARTIFACT_INDEX_CAPABILITY
}
