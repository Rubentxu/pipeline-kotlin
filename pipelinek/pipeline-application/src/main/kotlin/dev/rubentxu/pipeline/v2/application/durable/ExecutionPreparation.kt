package dev.rubentxu.pipeline.v2.application.durable

/**
 * Result of an invocation's preparation / admission / decode. Distinct phases
 * never conflated:
 *  - [Rejected] is admission failure BEFORE any effect; the common executor must NOT run.
 *  - [Ready] carries a [PreparedRegistryExecution] that HAS passed
 *    preparation/admission/decode and is ready to be executed through
 *    [CommonExecutionBoundary].
 * Producing a [Ready] never runs the Step; only [CommonExecutionBoundary.execute] produces effects.
 */
sealed interface ExecutionPreparation {
    data class Rejected(val reason: String) : ExecutionPreparation
    data class Ready(val prepared: PreparedRegistryExecution) : ExecutionPreparation
}
