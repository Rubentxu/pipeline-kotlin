package dev.rubentxu.pipeline.v2.domain

/**
 * Constants for block nesting limits.
 *
 * Single source of truth for MAX_BLOCK_DEPTH. Its only reader is
 * [CompiledPipelineValidator], which is the canonical IR validation and the sole
 * enforcement of the limit.
 *
 * It used to list two readers: this one and `BlockStepFlattener`, described as a
 * "V1 transitional adapter". That adapter is gone — nothing in the repository ever
 * called it, so it was a second way to compute nesting depth, with its own
 * `BlockNestingDepthExceededException`, guarding nothing. Two authorities for one
 * rule is the shape this file was supposed to prevent, not an acceptable cost of
 * a migration.
 */
object BlockNestingConstants {
    /** Maximum block nesting depth per Jenkins CPS continuation limit. */
    const val MAX_BLOCK_DEPTH = 3
}
