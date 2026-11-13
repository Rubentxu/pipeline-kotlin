package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcomeFold
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilShape
import dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy

/**
 * Registry for [StepDescriptor] metadata, populated at compile time.
 * Provides a lookup for step kinds to determine body-handling characteristics, declared
 * As ONE value ([StepBody] since /)
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
     * Read-only view used by structural fitness (e.g. every body-bearing row must
     * declare a coherent body execution policy) and by policy tooling that needs to
     * enumerate declarations without naming any Step.
*/
    fun keys(): Set<PluginStepId> = descriptors.keys

    /**
     * Every body Step whose body is executed by [owner]
     * This is the authority from which an engine derives the body Step families it
     * may execute. It replaces a hard-coded list of StepKeys in the engine with a
     * declared property of the Step:
     * ```text
     * canonical eligibility  <- declared ownership, never a StepKey list
     * a new body Step        <- ONE descriptor row, no engine change
     * ```
     * Derived from [StepDescriptor.body], never from a hard-coded key list: a terminal
     * Step ([StepBody.None]) has no body to own, and a body Step
     * ([StepBody.Declared]) declares who owns it. removed the possibility of a body
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

    /**
     * Holds the canonical rows the registry ships with.
     *
     * A registry is immutable once built, and `standard()` is the only way to
     * obtain one: the rows that constitute it are stated here and nowhere
     * else.
     */
    companion object {
        /**
         * Creates a registry with the standard canonical core step descriptors.
         * The rows are grouped by the property they declare — who executes the body, or
         * that there is no body at all — rather than by convenience. A new body Step is one
         * row in the group whose owner runs it; the engine reads ownership from the
         * descriptor, never from a StepKey list.
         * Every group is a list of key-to-descriptor rows, and declaration order is the
         * order of that list: [StepDescriptorRegistry.keys] exposes it, and policy tooling
         * enumerates declarations from it.
*/
        fun standard(): StepDescriptorRegistry = StepDescriptorRegistry(
            buildMap {
                foldingBodyRows().forEach { (key, descriptor) -> put(key, descriptor) }
                canonicalEngineBodyRows().forEach { (key, descriptor) -> put(key, descriptor) }
                terminalRows().forEach { (key, descriptor) -> put(key, descriptor) }
            }
        )

        /**
         * Body rows that FOLD the body's typed outcome instead of projecting a scope.
         * A folding row is not a scoped row: the body still runs once, in the caller's own
         * context, and what the scope does with that outcome is a fold, not a projection.
         * Which fold it applies travels in the block payload, so the row declares it.
         * The coordinator derives eligibility from [BodyExecutionOwner.CANONICAL_ENGINE]
         * like every other body row, so this group is where the rows live, not what makes
         * them routable.
*/
        private fun foldingBodyRows(): List<Pair<PluginStepId, StepDescriptor>> = listOf(
            PluginStepId("core.catchError") to StepDescriptor(
                stepId = "core.catchError",
                name = "catchError",
                jenkinsSurface = "catchError",
                jenkinsDivergence = "the outcome is the one the step's POLICY declares: the scope " +
                    "absorbs the failure and ends with the declared buildResult, and a declared " +
                    "FAILURE is re-thrown outward unchanged; Jenkins marks the build and continues " +
                    "the flow.",
                configRef = "",
                inputSchema = StepArguments(
                    arguments = listOf(
                        StepArgument(
                            name = "buildResult",
                            type = StepArgumentType.STRING,
                            required = true,
                            description = "the result the build ends with when the body fails",
                        ),
                        StepArgument(
                            name = "stageResult",
                            type = StepArgumentType.STRING,
                            required = true,
                            description = "the result the stage ends with when the body fails",
                        ),
                        StepArgument(
                            name = "message",
                            type = StepArgumentType.STRING,
                            required = false,
                            description = "the text the failure carries",
                        ),
                        StepArgument(
                            name = "catchInterruptions",
                            type = StepArgumentType.BOOLEAN,
                            required = true,
                            description = "whether the scope absorbs an interruption of the body",
                        ),
                    ),
                ).jsonSchema("core.catchError"),
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    // Containment is a FOLD of the body's typed outcome, not an execution
                    // reshape: the body still runs once, in the caller's own context.
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.CANONICAL_ENGINE,
                        policy = BodyExecutionPolicy.Folding(BodyOutcomeFold.CONTAINMENT),
                    ),
                    introduces = ContextKind.CANCELLATION,
                    catchesInterruptions = true,
                ),
            ),
            PluginStepId("core.warnError") to StepDescriptor(
                stepId = "core.warnError",
                name = "warnError",
                jenkinsSurface = "warnError",
                jenkinsDivergence = "the outcome is the one the step's POLICY declares: the scope " +
                    "always ends UNSTABLE and publishes the warning, whatever the body did; " +
                    "Jenkins marks the build UNSTABLE only when the body fails.",
                configRef = "",
                inputSchema = StepArguments(
                    arguments = listOf(
                        StepArgument(
                            name = "buildResult",
                            type = StepArgumentType.STRING,
                            required = true,
                            description = "the result the build ends with, always UNSTABLE",
                        ),
                        StepArgument(
                            name = "stageResult",
                            type = StepArgumentType.STRING,
                            required = true,
                            description = "the result the stage ends with, always UNSTABLE",
                        ),
                        StepArgument(
                            name = "message",
                            type = StepArgumentType.STRING,
                            required = false,
                            description = "the text the warning carries",
                        ),
                        StepArgument(
                            name = "catchInterruptions",
                            type = StepArgumentType.BOOLEAN,
                            required = true,
                            description = "whether the scope absorbs an interruption of the body",
                        ),
                    ),
                ).jsonSchema("core.warnError"),
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.AT_MOST_ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.CANONICAL_ENGINE,
                        policy = BodyExecutionPolicy.Folding(BodyOutcomeFold.OUTPUT_DECORATION),
                    ),
                    introduces = ContextKind.OUTPUT_DECORATOR,
                    // The family catches interruptions — that is what the DSL's own
                    // `warnError(message, catchInterruptions = true)` default says. The
                    // per-call-site flag travels in the block payload; this row declares
                    // what the family supports, so a default that contradicted it would
                    // be a contradiction between the descriptor and the DSL.
                    catchesInterruptions = true,
                ),
            ),
        )

        /**
         * Body rows executed by the canonical body engine.
         *
         * The rows are split by the POLICY that runs the body: a row that projects
         * a scope and a row that retries one are two different contracts, and one
         * list that holds both grows with the product until no one can read it.
         *
         * The coordinator derives eligibility from [BodyExecutionOwner.CANONICAL_ENGINE], so
         * adding a row here is what makes a body Step routable.
         */
        private fun canonicalEngineBodyRows(): List<Pair<PluginStepId, StepDescriptor>> =
            canonicalScopedBodyRows() + canonicalRetryingBodyRows()

        /**
         * The rows that project a scope around the body they run.
         */
        private fun canonicalScopedBodyRows(): List<Pair<PluginStepId, StepDescriptor>> = listOf(
            PluginStepId("core.withEnv") to StepDescriptor(
                stepId = "core.withEnv",
                name = "withEnv",
                jenkinsSurface = "withEnv",
                configRef = "",
                inputSchema = StepArguments(
                    arguments = listOf(
                        StepArgument(
                            name = "overrides",
                            type = StepArgumentType.ARRAY,
                            required = true,
                            description = "the environment variables the body runs with",
                        ),
                    ),
                ).jsonSchema("core.withEnv"),
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.CANONICAL_ENGINE,
                        policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
                    ),
                    introduces = ContextKind.ENVIRONMENT,
                ),
            ),
            PluginStepId("core.dir") to StepDescriptor(
                stepId = "core.dir",
                name = "dir",
                jenkinsSurface = "dir",
                configRef = "",
                inputSchema = StepArguments(
                    arguments = listOf(
                        StepArgument(
                            name = "path",
                            type = StepArgumentType.STRING,
                            required = true,
                            description = "the directory the body runs in, relative to the workspace",
                        ),
                    ),
                ).jsonSchema("core.dir"),
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.CANONICAL_ENGINE,
                        policy = BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory),
                    ),
                    introduces = ContextKind.CWD,
                ),
            ),
            PluginStepId("core.withCredentials") to StepDescriptor(
                stepId = "core.withCredentials",
                name = "withCredentials",
                jenkinsSurface = "withCredentials",
                configRef = "",
                inputSchema = StepArguments(
                    arguments = listOf(
                        StepArgument(
                            name = "bindings",
                            type = StepArgumentType.ARRAY,
                            required = true,
                            description = "the credential bindings the body runs with",
                        ),
                    ),
                ).jsonSchema("core.withCredentials"),
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.CANONICAL_ENGINE,
                        policy = BodyExecutionPolicy.Scoped(BodyContextProjection.CredentialLease),
                    ),
                    introduces = ContextKind.CREDENTIALS,
                ),
            ),
            PluginStepId("core.timeout") to StepDescriptor(
                stepId = "core.timeout",
                name = "timeout",
                jenkinsSurface = "timeout",
                configRef = "",
                inputSchema = StepArguments(
                    arguments = listOf(
                        StepArgument(
                            name = "seconds",
                            type = StepArgumentType.INTEGER,
                            required = true,
                            description = "how long the body may run, in seconds",
                        ),
                    ),
                ).jsonSchema("core.timeout"),
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
            ),
            PluginStepId("core.timestamps") to StepDescriptor(
                stepId = "core.timestamps",
                name = "timestamps",
                jenkinsSurface = "timestamps",
                configRef = "",
                inputSchema = StepArguments.of().jsonSchema("core.timestamps"),
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.CANONICAL_ENGINE,
                        //: closes the declared gap. The coordinator already projects a
                        // timestamp scope around this body; the row is what makes that routing
                        // registry-derived instead of a hard-coded StepKey.
                        policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Timestamps),
                    ),
                    // A timestamp source is none of the declared ContextKinds; the
                    // projection declares `requiredContextKind = null` for the same reason.
                    introduces = null,
                ),
            ),
        )

        /**
         * The rows whose body runs again until it succeeds or the policy gives up.
         */
        private fun canonicalRetryingBodyRows(): List<Pair<PluginStepId, StepDescriptor>> = listOf(
            PluginStepId("core.retry") to StepDescriptor(
                stepId = "core.retry",
                name = "retry",
                jenkinsSurface = "retry",
                configRef = "",
                inputSchema = StepArguments(
                    arguments = listOf(
                        StepArgument(
                            name = "maxAttempts",
                            type = StepArgumentType.INTEGER,
                            required = true,
                            description = "how many times the body may run",
                        ),
                    ),
                ).jsonSchema("core.retry"),
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
            ),
            // WaitUntil polls a condition body until satisfied or backoff exceeds ceiling.
            // The polling cadence is declared structurally via the `waitUntil` sub-shape of
            // BodyExecutionPolicy.Retrying; the canonical body engine reads the sub-shape and
            // dispatches the loop without a per-StepKey branch. The WaitUntilShape defaults
            // (initialRecurrencePeriodMs=1000L, quiet=false) match Jenkins verbatim.
            PluginStepId("core.waitUntil") to StepDescriptor(
                stepId = "core.waitUntil",
                name = "waitUntil",
                jenkinsSurface = "waitUntil",
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
            ),
        )

        /**
         * Terminal steps: a declared absence of body.
         * A terminal row has no body to own, so the engine never has to decide whether it
         * may execute one. The pair is the table, because every terminal row differs only in
         * its key, its display name and its Jenkins facts.
         *
         * The Jenkins facts of these rows are the SAME the runtime's own descriptors
         * declare: the runtime registers a handler for every one of these keys, and a
         * composition that does not register one falls back to this row. The two copies
         * are checked against each other, so the fallback cannot drift away from the
         * descriptor that runs the step.
*/
        private fun terminalRows(): List<Pair<PluginStepId, StepDescriptor>> = listOf(
            "core.emit.event" to "emitEvent",
            "core.sh" to "sh",
            "core.echo" to "echo",
            "core.sleep" to "sleep",
            "core.file.writeFile" to "writeFile",
        ).map { (stepId, name) ->
            PluginStepId(stepId) to StepDescriptor(
                stepId = stepId,
                name = name,
                configRef = "",
                jenkinsSurface = jenkinsSurfaceOf(stepId),
                jenkinsDivergence = jenkinsDivergenceOf(stepId),
                body = StepBody.None,
            )
        }

        /**
         * The equivalent in Jenkins of a terminal row, empty when it has none.
         *
         * The facts are read from a table, and not from the row's own descriptor, because
         * the terminal rows are generated from a list of keys: a table is where a fact
         * about several keys can live without being written five times.
*/
        private fun jenkinsSurfaceOf(stepId: String): String = when (stepId) {
            "core.sh" -> "sh"
            "core.echo" -> "echo"
            "core.sleep" -> "sleep"
            "core.file.writeFile" -> "writeFile"
            else -> ""
        }

        /**
         * The divergence of a terminal row with its equivalent in Jenkins, with its motive.
         *
         * The only terminal row with no equivalent in Jenkins is `core.emit.event`: there
         * is no Jenkins step that publishes an event of the run.
*/
        private fun jenkinsDivergenceOf(stepId: String): String = when (stepId) {
            "core.emit.event" ->
                "Jenkins has no step that publishes an event of the run: here the step " +
                    "writes one to the event plane, and a consumer of the events sees it."
            else -> ""
        }
    }
}
