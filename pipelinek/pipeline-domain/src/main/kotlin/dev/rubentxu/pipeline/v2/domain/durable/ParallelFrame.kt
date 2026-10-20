package dev.rubentxu.pipeline.v2.domain.durable

// NOTE: The domain layer defines a minimal StepSpec interface (this file).
// The DSL layer's StepSpec (pipeline-scripting-api) implements this interface,
// enabling BranchSpec.steps to reference steps without creating a domain→dsl
// dependency.  This is the standard pattern for separating domain (WHAT) from
// application (HOW): domain defines the interface, DSL provides the implementation.

/**
 * Minimal domain interface for a pipeline step.
 *
 * This is the domain-level contract.  The DSL layer's [StepSpec] sealed interface
 * (in [dev.rubentxu.pipeline.v2.dsl]) implements this interface, allowing
 * [BranchSpec.steps] to be typed against the domain without a DSL dependency.
 *
 * Concrete step variants (echo, shell, error, sleep, parallel) are defined
 * in the DSL layer as data classes implementing this interface.
 */
interface StepSpec {
    /** Human-readable step name, used in logs and error messages. */
    val name: String

    /** Machine-readable step type identifier. */
    val type: String
}

/**
 * Marker interface for pipeline frame types that can appear in a pipeline
 * execution sequence. Used by [dev.rubentxu.pipeline.v2.application.PipelineRun]
 * to dispatch to the appropriate frame handler (sequential vs. parallel).
 *
 * @see ParallelFrame
 */
sealed interface PipelineFrame

/**
 * Represents a parallel execution frame containing multiple branches that
 * execute concurrently, joined by a [JoinPolicy].
 *
 * This is a pure-domain type: it has no dependency on [OperationContext],
 * [ReplayCursor][dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore],
 * or any other execution artifact.
 *
 * @property branches The list of branch specifications to execute in parallel.
 * @property joinPolicy The policy determining when the parallel frame completes.
 */
data class ParallelFrame(
    val branches: List<BranchSpec>,
    val joinPolicy: JoinPolicy,
) : PipelineFrame

/**
 * Specification for a single branch within a [ParallelFrame].
 *
 * Each branch has a unique name and an ordered list of [StepSpec] steps.
 * Branches execute concurrently with other branches in the same parallel frame.
 *
 * @property name Unique identifier for this branch within the parallel frame.
 * @property steps The ordered list of steps to execute within this branch.
 */
data class BranchSpec(
    val name: String,
    val steps: List<StepSpec>,
)

/**
 * Policy determining when a [ParallelFrame] completes.
 *
 * @see ParallelFrame.joinPolicy
 */
enum class JoinPolicy {
    /**
     * Wait for all branches to complete before advancing.
     * The parallel frame succeeds if all branches succeed,
     * and fails if any branch fails.
     */
    ALL_COMPLETE,

    /**
     * Succeed as soon as the first branch succeeds, cancelling
     * all other running branches. If all branches fail, the
     * parallel frame fails with the first failure.
     */
    FIRST_SUCCESS,

    /**
     * Succeed or fail as soon as any branch completes (first to finish).
     * Other running branches are cancelled.
     */
    ANY_COMPLETE,
}
