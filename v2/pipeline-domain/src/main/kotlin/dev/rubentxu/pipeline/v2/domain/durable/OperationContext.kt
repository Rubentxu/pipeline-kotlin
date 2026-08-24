package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Minimal execution context for durable operations.
 *
 * This interface provides the minimal set of fields required for fingerprint
 * computation and replay cursor tracking. Full ADR-0003 Context syntax
 * (with capability declarations) is deferred to M3-R2 when the `script {}`
 * block needs it.
 *
 * @see <a href="design.md §R4">Design §R4 — Context capability API deferral</a>
 */
interface OperationContext {
    /** Deterministic run identifier derived from script path + content. */
    val runId: String

    /** Index of the currently executing stage within the pipeline. */
    val stageIndex: Int

    /** Monotonically increasing attempt number within the run (≥ 1). */
    val attempt: Int
}
