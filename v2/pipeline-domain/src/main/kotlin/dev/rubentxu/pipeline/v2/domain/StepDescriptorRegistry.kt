package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilShape
import dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy

/**
 * Registry for [StepDescriptor] metadata, populated at compile time.
 *
 * Provides a lookup for step kinds to determine body-handling characteristics, declared
 * as ONE value ([StepBody] since B10 / W1d).
 *
 * Seeded with canonical core step descriptors. The registry is consulted by
 * [CompiledPipelineValidator] to enforce body-declaration constraints.
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

    /**
     * Every body Step whose body is executed by [owner] (B10 / W1c).
     *
     * This is the authority from which an engine derives the body Step families it
     * may execute. It replaces a hard-coded list of StepKeys in the engine with a
     * declared property of the Step:
     *
     * ```text
     * canonical eligibility  <- declared ownership, never a StepKey list
     * a new body Step        <- ONE descriptor row, no engine change
     * ```
     *
     * Derived from [StepDescriptor.body], never from a hard-coded key list: a terminal
     * Step ([StepBody.None]) has no body to own, and a body Step
     * ([StepBody.Declared]) declares who owns it. W1d removed the possibility of a body
     * Step with an implicit owner, so this filter cannot silently mis-route a new row.
     */
    fun bodyStepIds(owner: BodyExecutionOwner): Set<PluginStepId> =
        descriptors.filter { (_, descriptor) ->
            descriptor.body.declared?.execution?.owner == owner
        }.keys

    /**
     * The [BodyPolicyResolver] over this descriptor table, the authority for Step
     * families that are declared here without a registered handler (the core block
     * Steps). Same laws as [dev.rubentxu.pipeline.v2.domain.step.RegistryBodyPolicyResolver]:
     * unknown, incoherent, and unsupported declarations are rejected, never defaulted.
     */
    fun bodyPolicyResolver(support: BodyExecutionSupport): BodyPolicyResolver =
        BodyPolicyResolver { key -> resolveBodyExecutionPolicy(key, get(key), support) }

    /** Resolves one key's policy with the same fail-closed laws. */
    fun bodyPolicy(key: PluginStepId, support: BodyExecutionSupport): BodyPolicyResolution =
        resolveBodyExecutionPolicy(key, get(key), support)

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
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ONCE,
                        // Containment is a FOLD of the body's typed outcome, not an execution
                        // reshape: the body still runs once, in the caller's own context.
                        execution = BodyExecution(
                            // W1c: containment semantics live in the legacy workflow-control
                            // rewrite (`CatchErrorOverlay` propagation), not in the canonical body
                            // engine, so this Step's body is NOT owned there. Declared, not inferred:
                            // `Sequential` is also the shape of a plain body the canonical engine runs.
                            owner = BodyExecutionOwner.LEGACY_LINEAR,
                            policy = BodyExecutionPolicy.Sequential,
                        ),
                        introduces = ContextKind.CANCELLATION,
                        catchesInterruptions = true,
                    ),
                ))
                put(PluginStepId("core.warnError"), StepDescriptor(
                    stepId = "core.warnError",
                    name = "warnError",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.AT_MOST_ONCE,
                        execution = BodyExecution(
                            // W1c: output decoration is applied by the legacy workflow-control
                            // rewrite, same reasoning as `core.catchError`.
                            owner = BodyExecutionOwner.LEGACY_LINEAR,
                            policy = BodyExecutionPolicy.Sequential,
                        ),
                        introduces = ContextKind.OUTPUT_DECORATOR,
                    ),
                ))
                put(PluginStepId("core.withEnv"), StepDescriptor(
                    stepId = "core.withEnv",
                    name = "withEnv",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ONCE,
                        execution = BodyExecution(
                            owner = BodyExecutionOwner.CANONICAL_ENGINE,
                            policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
                        ),
                        introduces = ContextKind.ENVIRONMENT,
                    ),
                ))
                put(PluginStepId("core.dir"), StepDescriptor(
                    stepId = "core.dir",
                    name = "dir",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ONCE,
                        execution = BodyExecution(
                            owner = BodyExecutionOwner.CANONICAL_ENGINE,
                            policy = BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory),
                        ),
                        introduces = ContextKind.CWD,
                    ),
                ))
                put(PluginStepId("core.withCredentials"), StepDescriptor(
                    stepId = "core.withCredentials",
                    name = "withCredentials",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ONCE,
                        execution = BodyExecution(
                            owner = BodyExecutionOwner.CANONICAL_ENGINE,
                            policy = BodyExecutionPolicy.Scoped(BodyContextProjection.CredentialLease),
                        ),
                        introduces = ContextKind.CREDENTIALS,
                    ),
                ))
                put(PluginStepId("core.timeout"), StepDescriptor(
                    stepId = "core.timeout",
                    name = "timeout",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ONCE,
                        execution = BodyExecution(
                            owner = BodyExecutionOwner.CANONICAL_ENGINE,
                            // Deadline is a projected scope; CANCELLATION alone cannot say so
                            // because catchError declares the same kind with Sequential.
                            policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Deadline),
                        ),
                        introduces = ContextKind.CANCELLATION,
                    ),
                ))
                put(PluginStepId("core.timestamps"), StepDescriptor(
                    stepId = "core.timestamps",
                    name = "timestamps",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ONCE,
                        execution = BodyExecution(
                            owner = BodyExecutionOwner.CANONICAL_ENGINE,
                            // W1c: closes the declared W1b gap. The coordinator already projects a
                            // timestamp scope around this body; the row is what makes that routing
                            // registry-derived instead of a hard-coded StepKey.
                            policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Timestamps),
                        ),
                        // A timestamp source is none of the declared ContextKinds; the
                        // projection declares `requiredContextKind = null` for the same reason.
                        introduces = null,
                    ),
                ))
                put(PluginStepId("core.retry"), StepDescriptor(
                    stepId = "core.retry",
                    name = "retry",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ZERO_OR_MORE,
                        execution = BodyExecution(
                            owner = BodyExecutionOwner.CANONICAL_ENGINE,
                            // Each attempt is a distinct body invocation with its own durable
                            // identity; cardinality is decoded input, not declaration.
                            policy = BodyExecutionPolicy.Retrying(RetryPolicy()),
                        ),
                        introduces = null,
                    ),
                ))
                // WU-LPR-301: waitUntil polls a condition body until satisfied or backoff exceeds ceiling.
                // The polling cadence is declared structurally via the `waitUntil` sub-shape of
                // BodyExecutionPolicy.Retrying; the canonical body engine reads the sub-shape and
                // dispatches the loop without a per-StepKey branch. The WaitUntilShape defaults
                // (initialRecurrencePeriodMs=1000L, quiet=false) match Jenkins verbatim.
                put(PluginStepId("core.waitUntil"), StepDescriptor(
                    stepId = "core.waitUntil",
                    name = "waitUntil",
                    configRef = "",
                    body = StepBody.Declared(
                        invocation = BodyInvocationPolicy.ZERO_OR_MORE,
                        execution = BodyExecution(
                            owner = BodyExecutionOwner.CANONICAL_ENGINE,
                            policy = BodyExecutionPolicy.Retrying(
                                policy = RetryPolicy(),
                                waitUntil = WaitUntilShape(),
                            ),
                        ),
                        introduces = null,
                    ),
                ))

                // Terminal steps (no body)
                put(PluginStepId("core.emit.event"), StepDescriptor(
                    stepId = "core.emit.event",
                    name = "emitEvent",
                    configRef = "",
                    body = StepBody.None,
                ))
                put(PluginStepId("core.sh"), StepDescriptor(
                    stepId = "core.sh",
                    name = "sh",
                    configRef = "",
                    body = StepBody.None,
                ))
                put(PluginStepId("core.echo"), StepDescriptor(
                    stepId = "core.echo",
                    name = "echo",
                    configRef = "",
                    body = StepBody.None,
                ))
                put(PluginStepId("core.sleep"), StepDescriptor(
                    stepId = "core.sleep",
                    name = "sleep",
                    configRef = "",
                    body = StepBody.None,
                ))
                put(PluginStepId("core.file.writeFile"), StepDescriptor(
                    stepId = "core.file.writeFile",
                    name = "writeFile",
                    configRef = "",
                    body = StepBody.None,
                ))
            }
        )
    }
}
