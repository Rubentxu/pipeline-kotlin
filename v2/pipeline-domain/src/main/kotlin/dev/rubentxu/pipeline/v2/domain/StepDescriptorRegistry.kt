package dev.rubentxu.pipeline.v2.domain

/**
 * Registry for [StepDescriptor] metadata, populated at compile time.
 *
 * Provides a lookup for step kinds to determine body-handling characteristics
 * (takesBody, bodyInvocations, introducesContext, catchesInterruptions).
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
                ))
                put(PluginStepId("core.warnError"), StepDescriptor(
                    stepId = "core.warnError",
                    name = "warnError",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.AT_MOST_ONCE,
                    introducesContext = ContextKind.OUTPUT_DECORATOR,
                ))
                put(PluginStepId("core.withEnv"), StepDescriptor(
                    stepId = "core.withEnv",
                    name = "withEnv",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.ENVIRONMENT,
                ))
                put(PluginStepId("core.dir"), StepDescriptor(
                    stepId = "core.dir",
                    name = "dir",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.CWD,
                ))
                put(PluginStepId("core.withCredentials"), StepDescriptor(
                    stepId = "core.withCredentials",
                    name = "withCredentials",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.CREDENTIALS,
                ))
                put(PluginStepId("core.timeout"), StepDescriptor(
                    stepId = "core.timeout",
                    name = "timeout",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ONCE,
                    introducesContext = ContextKind.CANCELLATION,
                ))
                put(PluginStepId("core.retry"), StepDescriptor(
                    stepId = "core.retry",
                    name = "retry",
                    configRef = "",
                    takesBody = true,
                    bodyInvocations = BodyInvocationPolicy.ZERO_OR_MORE,
                    introducesContext = null,
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
