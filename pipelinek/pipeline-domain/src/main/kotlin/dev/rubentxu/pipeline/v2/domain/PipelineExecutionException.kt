package dev.rubentxu.pipeline.v2.domain

/** Base exception for execution failures with intentional control-flow semantics. */
sealed class PipelineExecutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A step contract failed and can be represented at the public outcome seam. */
open class PipelineStepException(
    val failure: PipelineFailure,
    cause: Throwable? = null,
) : PipelineExecutionException(failure.message, cause)

/**
 * A shell step failed.
 *
 * The type names the family of step that failed; the classification the
 * product publishes travels in the failure this exception carries, so a
 * caller reads the failure's kind to learn whether the command exited
 * non-zero or something else went wrong.
 */
class ShellExitException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

/**
 * A step failed because of the machinery around it: the disk, the
 * launcher, the runtime's own resources.
 *
 * The type names the family of step that failed; the classification the
 * product publishes travels in the failure this exception carries.
 */
class InfrastructureStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

/**
 * A step failed because the network did not answer.
 *
 * The type names the family of step that failed; the classification the
 * product publishes travels in the failure this exception carries, and a
 * caller that retries must read that classification rather than the type.
 */
class NetworkStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

/**
 * A step refused what the user asked for: an input it cannot accept, a
 * value outside the declared range.
 *
 * The type names the family of step that failed; the classification the
 * product publishes travels in the failure this exception carries.
 */
class UserStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

/**
 * A plugin broke the contract it declared.
 *
 * The type names the family of step that failed; the classification and the
 * description the producer wrote travel in the failure this exception
 * carries.
 */
class PluginStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

/** An engine invariant failed; it must never be reclassified as a step failure. */
class EngineInvariantViolation(
    message: String,
    cause: Throwable? = null,
) : PipelineExecutionException(message, cause)
