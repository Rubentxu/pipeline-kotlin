package dev.rubentxu.pipeline.v2.sdk.api

import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Thrown when block nesting depth exceeds the Jenkins CPS continuation limit (3).
 */
class BlockNestingDepthExceededException(
    val depth: Int,
    val maxDepth: Int = 3,
) : RuntimeException("Block nesting depth $depth exceeds maximum $maxDepth")

/**
 * Result of flattening a block step — a step with its depth and block path.
 *
 * @property spec The step specification
 * @property depth Nesting depth (0 = top-level, 1 = inside one block, etc.)
 * @property blockPath Dot-separated path of block indices from root (e.g., "0.1.2")
 */
data class FlattenedStep(
    val spec: StepSpec,
    val depth: Int,
    val blockPath: String,
)

/**
 * Result of indexing a block tree — a step with its execution index and block path.
 *
 * @property spec The step specification
 * @property stepIndex Monotonically increasing index in execution order
 * @property depth Nesting depth (0 = top-level)
 * @property blockPath Dot-separated path of block indices from root
 */
data class IndexedStep(
    val spec: StepSpec,
    val stepIndex: Int,
    val depth: Int,
    val blockPath: String,
)

/**
 * Block-step flattener and indexer.
 *
 * Provides three operations:
 * 1. [flatten] — expand any block step (WithEnv, Parallel, WithCredentialsBlock, etc.)
 *    into a flat list of [FlattenedStep] with depth and block-path metadata.
 * 2. [index] — assign monotonic step indices to the flattened view.
 * 3. [depthGuard] — validate that no block exceeds nesting depth 3.
 *
 * The flattener is generic: it handles ANY [StepSpec] that carries a `steps: List<StepSpec>`
 * field, not just [StepSpec.WithEnv].
 *
 * Design: D1, D2 (ADR-0052 §D1/D2)
 * R-1 mitigation: extracted before new step kinds land, locks shape for ml-r10..r13
 *
 * ## Extensibility
 *
 * When a new block-type [StepSpec] is added (e.g., [StepSpec.Dir], [StepSpec.TimeoutBlock],
 * [StepSpec.RetryBlock]), add a `when` branch here BEFORE the terminal-cases section.
 * The terminal-cases `else` branch ensures exhaustive matching even for not-yet-existent types.
 */
object BlockStepFlattener {

    /** Maximum block nesting depth per Jenkins CPS continuation limit. */
    const val MAX_BLOCK_DEPTH = 3

    /**
     * Flattens a step tree into a list of [FlattenedStep].
     *
     * Recursively expands any step that has nested `steps: List<StepSpec>`.
     * Terminal steps (Echo, Shell, Sleep, etc.) are returned as-is at their depth.
     *
     * @param root The root step to flatten
     * @param depth Current nesting depth (incremented when entering a block)
     * @param blockPath Dot-separated block index path from root
     * @return List of [FlattenedStep] in execution order
     */
    fun flatten(root: StepSpec, depth: Int = 0, blockPath: String = ""): List<FlattenedStep> {
        val result = mutableListOf<FlattenedStep>()
        flattenImpl(root, depth, blockPath, result)
        return result
    }

    private fun flattenImpl(
        step: StepSpec,
        depth: Int,
        blockPath: String,
        result: MutableList<FlattenedStep>,
    ) {
        result.add(FlattenedStep(step, depth, blockPath))

        // Recurse into nested steps for any block-type step
        when (step) {
            is StepSpec.WithEnv -> {
                for ((idx, inner) in step.steps.withIndex()) {
                    val childPath = if (blockPath.isEmpty()) "$idx" else "$blockPath.$idx"
                    flattenImpl(inner, depth + 1, childPath, result)
                }
            }
            is StepSpec.Parallel -> {
                for ((idx, branch) in step.branches.withIndex()) {
                    val childPath = if (blockPath.isEmpty()) "$idx" else "$blockPath.$idx"
                    for ((innerIdx, inner) in branch.steps.withIndex()) {
                        val innerPath = if (childPath.isEmpty()) "$innerIdx" else "$childPath.$innerIdx"
                        flattenImpl(inner, depth + 1, innerPath, result)
                    }
                }
            }
            is StepSpec.WithCredentialsBlock -> {
                for ((idx, inner) in step.steps.withIndex()) {
                    val childPath = if (blockPath.isEmpty()) "$idx" else "$blockPath.$idx"
                    flattenImpl(inner, depth + 1, childPath, result)
                }
            }
            // --- ML-R9 block-type steps (T-04..T-10) ---
            is StepSpec.Dir -> {
                for ((idx, inner) in step.steps.withIndex()) {
                    val childPath = if (blockPath.isEmpty()) "$idx" else "$blockPath.$idx"
                    flattenImpl(inner, depth + 1, childPath, result)
                }
            }
            // CatchError, WarnError, TimeoutBlock, RetryBlock, Timestamps,
            // AnsiColor, NodeNoOp, Milestone — add when those types are defined
            is StepSpec.CatchError -> {
                for ((idx, inner) in step.steps.withIndex()) {
                    val childPath = if (blockPath.isEmpty()) "$idx" else "$blockPath.$idx"
                    flattenImpl(inner, depth + 1, childPath, result)
                }
            }
            is StepSpec.WarnError -> {
                for ((idx, inner) in step.steps.withIndex()) {
                    val childPath = if (blockPath.isEmpty()) "$idx" else "$blockPath.$idx"
                    flattenImpl(inner, depth + 1, childPath, result)
                }
            }
            // --- Terminal steps — no recursion ---
            is StepSpec.Echo,
            is StepSpec.Shell,
            is StepSpec.Sleep,
            is StepSpec.Error,
            is StepSpec.Checkout,
            is StepSpec.WriteFile,
            is StepSpec.ReadFile,
            is StepSpec.FileExists,
            is StepSpec.ArchiveArtifacts,
            is StepSpec.DeleteDir,
            is StepSpec.CleanWs,
            is StepSpec.Unstable -> {
                // Terminal — no nested steps to flatten
            }
        }
    }

    /**
     * Indexes a step tree, assigning monotonic step indices.
     *
     * @param root The root step to index
     * @return List of [IndexedStep] in execution order with stepIndex assigned
     */
    fun index(root: StepSpec): List<IndexedStep> {
        val flattened = flatten(root)
        var currentIndex = 0
        return flattened.map { flatted ->
            IndexedStep(
                spec = flatted.spec,
                stepIndex = currentIndex++,
                depth = flatted.depth,
                blockPath = flatted.blockPath,
            )
        }
    }

    /**
     * Validates that the step tree does not exceed maximum block nesting depth.
     *
     * @param root The root step to validate
     * @throws BlockNestingDepthExceededException if depth exceeds [MAX_BLOCK_DEPTH]
     */
    fun depthGuard(root: StepSpec) {
        val flattened = flatten(root)
        val maxDepth = flattened.maxOfOrNull { it.depth } ?: 0
        if (maxDepth > MAX_BLOCK_DEPTH) {
            throw BlockNestingDepthExceededException(maxDepth, MAX_BLOCK_DEPTH)
        }
    }
}
