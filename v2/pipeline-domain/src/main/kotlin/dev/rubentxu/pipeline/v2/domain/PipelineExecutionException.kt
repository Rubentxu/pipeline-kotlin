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

class ShellExitException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

class InfrastructureStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

class NetworkStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

class UserStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

class PluginStepException(failure: PipelineFailure, cause: Throwable? = null) :
    PipelineStepException(failure, cause)

/** An engine invariant failed; it must never be reclassified as a step failure. */
class EngineInvariantViolation(
    message: String,
    cause: Throwable? = null,
) : PipelineExecutionException(message, cause)
