package dev.rubentxu.pipeline.v2.domain

/**
 * Constants for block nesting limits.
 *
 * Single source of truth for MAX_BLOCK_DEPTH. Reused by:
 * - `BlockStepFlattener` (V1 transitional adapter)
 * - `CompiledPipelineValidator` (canonical IR validation)
 */
object BlockNestingConstants {
    /** Maximum block nesting depth per Jenkins CPS continuation limit. */
    const val MAX_BLOCK_DEPTH = 3
}
