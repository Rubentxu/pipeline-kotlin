package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.domain.DefinitionId

/**
 * Transition-period registry that lets the durable [RunCoordinator] resolve
 * the executable [PipelineSpec] for a compiled [PipelineDefinition].
 *
 * ## Why this exists
 *
 * The M2 compiler contract produces a metadata [PipelineDefinition]
 * (steps carry `configRef`, not payloads), while the live durable walker
 * still consumes the DSL-built [PipelineSpec] that carries the actual
 * payloads (shell commands, echo texts). Until the walker is rewritten
 * to dispatch from the definition alone (LF-0207+), the composition root
 * registers the spec under the definition id and the coordinator resolves
 * it at run time. This keeps the CLI execution path routed through the
 * `RunCoordinator` port (LF-0205 redirect) without pretending the
 * payloads are already in the definition.
 *
 * The registry is process-local and intentionally NOT durable: it lives
 * exactly as long as one CLI invocation. Registry misses are fail-closed.
 */
class SpecRegistry {

    private val specs = java.util.concurrent.ConcurrentHashMap<DefinitionId, PipelineSpec>()

    /**
     * Registers [spec] under [id]. Re-registering the same id replaces the
     * previous spec (idempotent re-compilation within one process).
     */
    fun register(id: DefinitionId, spec: PipelineSpec) {
        specs[id] = spec
    }

    /**
     * Returns the spec registered for [id].
     *
     * @throws IllegalArgumentException when no spec was registered — the
     *         caller compiled a definition it never handed the payloads for.
     */
    fun resolve(id: DefinitionId): PipelineSpec =
        specs[id] ?: throw IllegalArgumentException(
            "No PipelineSpec registered for the supplied definition id; " +
                "the composition root must register the compiled spec before running"
        )

    /** Number of currently registered specs (diagnostics/testing). */
    fun size(): Int = specs.size
}
