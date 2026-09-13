package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy

/**
 * Registry for [StepDescriptor] metadata, populated at compile time.
 *
 * Provides a lookup for step kinds to determine body-handling characteristics
 * (takesBody, bodyInvocations, introducesContext, catchesInterruptions,
 * bodyExecutionPolicy).
 *
 * Seeded with canonical core step descriptors. The registry is consulted by
 * [CompiledPipelineValidator] to enforce takesBody constraints.
 */
class StepDescriptorRegistry private constructor(
    private val descriptors: Map<PluginStepId, StepDescriptor>,
) {
    /**
     * Returns the [StepDescriptor] for the given [PluginStepId], or null if not found.
     */
    fun get(id: PluginStepId): StepDescriptor? = descriptors[id]

    /**
     * All registered step kinds, in declaration order.
     *
     * Read-only view used by structural fitness (e.g. every body-bearing row must
     * declare a coherent body execution policy) and by policy tooling that needs to
     * enumerate declarations without naming any Step.
     */
    fun keys(): Set<PluginStepId> = descriptors.keys

    companion object {
        /**
         * Creates a registry with the standard canonical core step descriptors.
         */
        fun standard(): StepDescriptorRegistry = StepDescriptorRegistry(
            buildMap {
                // Block-type steps (take body)
                put(PluginStepId("core.catchError"), StepDescriptor(
                    stepId = "core.catchError",
                    name = "catchError",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.CANCELLATION,
                    catchesInterruptions = true,
                    // Containment is a FOLD of the body's typed outcome, not an execution
                    // reshape: the body still runs once, in the caller's own context.
                    bodyExecutionPolicy = BodyExecutionPolicy.Sequential,
                ))
                put(PluginStepId("core.warnError"), StepDescriptor(
                    stepId = "core.warnError",
                    name = "warnError",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.AT_MOST_ONCE,
                    introducesContext = ContextKind.OUTPUT_DECORATOR,
                    bodyExecutionPolicy = BodyExecutionPolicy.Sequential,
                ))
                put(PluginStepId("core.withEnv"), StepDescriptor(
                    stepId = "core.withEnv",
                    name = "withEnv",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.ENVIRONMENT,
                    bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
                ))
                put(PluginStepId("core.dir"), StepDescriptor(
                    stepId = "core.dir",
                    name = "dir",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.CWD,
                    bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory),
                ))
                put(PluginStepId("core.withCredentials"), StepDescriptor(
                    stepId = "core.withCredentials",
                    name = "withCredentials",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.CREDENTIALS,
                    bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.CredentialLease),
                ))
                put(PluginStepId("core.timeout"), StepDescriptor(
                    stepId = "core.timeout",
                    name = "timeout",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.CANCELLATION,
                    // Deadline is a projected scope; CANCELLATION alone cannot say so
                    // because catchError declares the same kind with Sequential.
                    bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.Deadline),
                ))
                put(PluginStepId("core.retry"), StepDescriptor(
                    stepId = "core.retry",
                    name = "retry",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ZERO_OR_MORE,
                    introducesContext = null,
                    // Each attempt is a distinct body invocation with its own durable
                    // identity; cardinality is decoded input, not declaration.
                    bodyExecutionPolicy = BodyExecutionPolicy.Retrying(RetryPolicy()),
                ))

                // Terminal steps (no body)
                put(PluginStepId("core.emit.event"), StepDescriptor(
                    stepId = "core.emit.event",
                    name = "emitEvent",
                    configRef = "",
                    takesBody = false,
                ))
                put(PluginStepId("core.sh"), StepDescriptor(
                    stepId = "core.sh",
                    name = "sh",
                    configRef = "",
                    takesBody = false,
                ))
                put(PluginStepId("core.echo"), StepDescriptor(
                    stepId = "core.echo",
                    name = "echo",
                    configRef = "",
                    takesBody = false,
                ))
                put(PluginStepId("core.sleep"), StepDescriptor(
                    stepId = "core.sleep",
                    name = "sleep",
                    configRef = "",
                    takesBody = false,
                ))
                put(PluginStepId("core.file.writeFile"), StepDescriptor(
                    stepId = "core.file.writeFile",
                    name = "writeFile",
                    configRef = "",
                    takesBody = false,
                ))
            }
        )
    }
}
