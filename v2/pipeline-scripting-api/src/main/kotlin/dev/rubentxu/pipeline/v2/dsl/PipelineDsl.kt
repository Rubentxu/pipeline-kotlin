package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Specification of a pipeline as built by the DSL.
 */
data class PipelineSpec(
    val stages: List<StageSpec>,
)

/**
 * Specification of a single stage within a pipeline.
 */
data class StageSpec(
    val name: String,
    val steps: List<StepSpec>,
    val options: OptionsSpec? = null,
    val agent: AgentSpec? = null,
    /**
     * Environment variables for this stage.
     * Injected via ProcessBuilder.environment() into each step.
     */
    val environment: Map<String, String>? = null,
)

/**
 * Sealed hierarchy of steps that a stage can contain.
 *
 * This interface extends [dev.rubentxu.pipeline.v2.domain.durable.StepSpec] to enable
 * the domain layer's [dev.rubentxu.pipeline.v2.domain.durable.BranchSpec] to reference
 * DSL steps without creating a domain→DSL dependency (ADR-0033).
 */
sealed interface StepSpec : dev.rubentxu.pipeline.v2.domain.durable.StepSpec {
    override val name: String
    override val type: String
    /** Retry policy for this step, or null if no retry. */
    val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? get() = null
    /** Timeout in milliseconds for this step, or null if no timeout. */
    val timeoutMillis: Long? get() = null

    data class Echo(
        val text: String,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "echo"
        override val type: String get() = "echo"
    }

    data class Shell(
        val command: String,
        val isScriptBlock: Boolean = false,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
        /**
         * Whether to capture stdout to output.txt for return value access.
         */
        val returnStdout: Boolean = false,
    ) : StepSpec {
        override val name: String get() = "sh"
        override val type: String get() = "sh"
    }

    /**
     * Records an error condition. The error is recorded in the event log
     * but no exception is thrown (record-only semantics).
     * failureKind is a string: INFRASTRUCTURE, NETWORK, SCRIPT, USER, TIMEOUT, UNKNOWN
     */
    data class Error(
        val message: String,
        val failureKind: String = "UNKNOWN",
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "error"
        override val type: String get() = "error"
    }

    /**
     * Records a sleep/delay step. The delay is recorded in the event log
     * but no actual sleeping occurs (record-only semantics).
     */
    data class Sleep(
        val seconds: Long,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "sleep"
        override val type: String get() = "sleep"
    }

    /**
     * Represents a parallel execution block containing multiple branches.
     * The runtime emits ParallelBranchStarted/ParallelBranchFinished events for each branch.
     */
    data class Parallel(
        val branches: List<BranchSpec>,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "parallel"
        override val type: String get() = "parallel"
    }

    /**
     * Specification for a single branch within a parallel block.
     */
    data class BranchSpec(
        val name: String,
        val steps: List<StepSpec>,
    )

    /**
     * Credentials binding for the withCredentials DSL block.
     *
     * Two binding kinds are supported:
     * - [Kind.STRING]: injects a plaintext value as an environment variable
     * - [Kind.USERNAME_PASSWORD]: injects two env vars (username and password)
     *
     * @see WithCredentialsBlock
     */
    data class CredentialsBinding(
        val kind: Kind,
        val credentialsId: CredentialsId,
        val variable: String? = null,
        val usernameVariable: String? = null,
        val passwordVariable: String? = null,
    ) {
        enum class Kind {
            STRING,
            USERNAME_PASSWORD
        }

        companion object {
            /**
             * Creates a STRING binding: the secret value is injected as a single env var.
             *
             * @param credentialsId The credentials ID in the store
             * @param variable The environment variable name
             */
            fun string(credentialsId: CredentialsId, variable: String): CredentialsBinding {
                return CredentialsBinding(Kind.STRING, credentialsId, variable = variable)
            }

            /**
             * Creates a USERNAME_PASSWORD binding: two env vars are injected.
             *
             * @param credentialsId The credentials ID in the store
             * @param usernameVariable The username environment variable name
             * @param passwordVariable The password environment variable name
             */
            fun usernamePassword(
                credentialsId: CredentialsId,
                usernameVariable: String,
                passwordVariable: String,
            ): CredentialsBinding {
                return CredentialsBinding(
                    Kind.USERNAME_PASSWORD,
                    credentialsId,
                    usernameVariable = usernameVariable,
                    passwordVariable = passwordVariable,
                )
            }
        }
    }

    /**
     * Represents a withCredentials block that binds credentials to environment variables.
     *
     * The block desugars at runtime to emit [CredentialBound] events at scope entry,
     * [CredentialUsed] events at each injection, and [CredentialUnbound] at scope exit.
     * Secret values are NEVER included in the params map (preserves Fingerprint.compute invariant).
     *
     * @param credentialsId The primary credentials ID for this block
     * @param purpose The purpose/label for the binding (e.g., variable name)
     * @param bindings The list of credentials bindings
     * @param steps The steps to execute with the credentials bound
     * @param retry Retry policy for this step, or null if no retry.
     * @param timeoutMillis Timeout in milliseconds for this step, or null for no timeout.
     */
    data class WithCredentialsBlock(
        val credentialsId: CredentialsId,
        val purpose: String,
        val bindings: List<CredentialsBinding>,
        val steps: List<StepSpec>,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "withCredentials"
        override val type: String get() = "withCredentials"
    }
}

/**
 * Agent specification for parallel execution.
 */
data class AgentSpec(
    val label: String,
    val remoteUri: String? = null,
)

/**
 * Environment variables block.
 */
data class EnvironmentSpec(
    val values: Map<String, String>,
)

/**
 * Options block for stage-level configuration.
 */
data class OptionsSpec(
    val timeout: Long? = null,
    val retry: RetrySpec? = null,
    val skip: Boolean = false,
)

/**
 * Retry configuration for a step or stage.
 */
data class RetrySpec(
    val count: Int,
    val delaySeconds: Long? = null,
)

/**
 * Timeout configuration.
 */
data class TimeoutSpec(
    val seconds: Long,
    val action: TimeoutAction = TimeoutAction.FAIL,
)

/**
 * Action to take when timeout expires.
 */
enum class TimeoutAction {
    FAIL,
    CONTINUE,
    MARK_UNSTABLE,
}

/**
 * Post conditions for a stage (e.g., always, success, failure).
 */
data class PostConditionSpec(
    val always: List<StepSpec> = emptyList(),
    val success: List<StepSpec> = emptyList(),
    val failure: List<StepSpec> = emptyList(),
)

/**
 * Conditional execution using a when clause.
 */
data class WhenCondition(
    val expression: String,
)

/**
 * Top-level `pipeline { }` DSL entry point.
 *
 * Example:
 * ```
 * pipeline {
 *     stages {
 *         stage("Build") {
 *             echo("hello")
 *             sh("echo from sh")
 *         }
 *     }
 * }
 * ```
 */
fun pipeline(block: PipelineScope.() -> Unit): PipelineSpec {
    val scope = PipelineScope()
    scope.block()
    return PipelineSpec(stages = scope.buildStages())
}

/**
 * Receiver scope for the `stages { }` block inside `pipeline { }`.
 */
class PipelineScope {
    private val stageBuilders = mutableListOf<StageBuilder>()

    fun stages(block: StagesScope.() -> Unit) {
        val scope = StagesScope()
        scope.block()
        scope.buildStageBuilders().forEach { stageBuilders.add(it) }
    }

    fun buildStages(): List<StageSpec> = stageBuilders.map { it.build() }
}

/**
 * Receiver scope for the `stage("name") { }` block inside `stages { }`.
 */
class StagesScope {
    private val stageBuilders = mutableListOf<StageBuilder>()

    fun stage(name: String, block: StageScope.() -> Unit) {
        val scope = StageScope(name)
        scope.block()
        stageBuilders.add(scope.toStageBuilder())
    }

    fun buildStageBuilders(): List<StageBuilder> = stageBuilders
}

/**
 * Receiver scope for the step block inside `stage("name") { }`.
 */
class StageScope(private val stageName: String) {
    private val steps = mutableListOf<StepSpec>()
    private var agent: AgentSpec? = null
    private var environment: EnvironmentSpec? = null
    private var options: OptionsSpec? = null
    private var post: PostConditionSpec? = null

    fun echo(text: String) {
        steps.add(StepSpec.Echo(text))
    }

    fun sh(command: String) {
        steps.add(StepSpec.Shell(command))
    }

    /**
     * Shell step with full options.
     *
     * @param script The shell command to execute.
     * @param returnStdout If true, capture stdout to output.txt for return value access.
     */
    fun sh(script: String, returnStdout: Boolean = false) {
        steps.add(StepSpec.Shell(command = script, returnStdout = returnStdout))
    }

    /**
     * Records an error with the given message.
     */
    fun error(message: String, failureKind: String = "UNKNOWN") {
        steps.add(StepSpec.Error(message, failureKind))
    }

    /**
     * Records a sleep/delay step for the given number of seconds.
     */
    fun sleep(seconds: Long) {
        steps.add(StepSpec.Sleep(seconds))
    }

    /**
     * Agent specification for this stage.
     */
    fun agent(label: String, remoteUri: String? = null) {
        agent = AgentSpec(label, remoteUri)
    }

    /**
     * Environment variables block.
     */
    fun environment(block: EnvironmentScope.() -> Unit) {
        val scope = EnvironmentScope()
        scope.block()
        environment = EnvironmentSpec(scope.build())
    }

    /**
     * Options block for stage-level configuration (timeout, retry, skip).
     */
    fun options(block: OptionsScope.() -> Unit) {
        val scope = OptionsScope()
        scope.block()
        options = scope.build()
    }

    /**
     * Post conditions (always, success, failure blocks).
     */
    fun post(block: PostScope.() -> Unit) {
        val scope = PostScope()
        scope.block()
        post = scope.build()
    }

    /**
     * Parallel execution block.
     */
    fun parallel(block: ParallelScope.() -> Unit) {
        val scope = ParallelScope()
        scope.block()
        val branches = scope.build()
        val branchSpecs = branches.map { StepSpec.BranchSpec(it.name, it.steps) }
        steps.add(StepSpec.Parallel(branchSpecs))
    }

    /**
     * Binds credentials to environment variables for the duration of the block.
     *
     * Mirrors Jenkins `withCredentials { }` DSL. Two binding kinds:
     * - [CredentialsBinding.string] injects the secret as a single env var
     * - [CredentialsBinding.usernamePassword] injects username and password as two env vars
     *
     * Example:
     * ```
     * withCredentials(CredentialsBinding.string(CredentialsId("github"), "API_KEY")) {
     *     sh("curl -H 'Authorization: token $API_KEY' https://api.github.com")
     * }
     * ```
     *
     * @param bindings The credentials bindings to activate
     * @param block The steps to execute with the credentials bound
     * @see CredentialsBinding
     */
    fun withCredentials(bindings: List<StepSpec.CredentialsBinding>, block: StageScope.() -> Unit) {
        val innerScope = StageScope(stageName)
        innerScope.block()
        // The primary credentialsId is the first binding's ID
        val primaryId = bindings.firstOrNull()?.credentialsId ?: CredentialsId("")
        val purpose = bindings.firstOrNull()?.variable
            ?: bindings.firstOrNull()?.usernameVariable
            ?: ""
        steps.add(
            StepSpec.WithCredentialsBlock(
                credentialsId = primaryId,
                purpose = purpose,
                bindings = bindings,
                steps = innerScope.steps.toList(),
            )
        )
    }

    /**
     * Binds a single credential to an environment variable.
     *
     * This is a convenience desugar that calls [withCredentials] internally.
     *
     * Example:
     * ```
     * environment(CredentialsId("github"), "API_KEY") {
     *     sh("curl -H 'Authorization: token $API_KEY' https://api.github.com")
     * }
     * ```
     *
     * @param credentialsId The credentials ID in the store
     * @param variable The environment variable name to inject
     * @param block The steps to execute with the credential bound
     */
    fun environment(credentialsId: CredentialsId, variable: String, block: StageScope.() -> Unit) {
        withCredentials(listOf(StepSpec.CredentialsBinding.string(credentialsId, variable)), block)
    }

    /**
     * Retry configuration for a step.
     */
    fun retry(count: Int, delaySeconds: Long? = null) {
        val currentStep = steps.lastOrNull() ?: return
        val retryPolicy = dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy(
            maxAttempts = count,
            baseMs = (delaySeconds ?: 0L) * 1000L,
            jitterMs = (delaySeconds ?: 0L) * 500L, // 50% jitter
        )
        val index = steps.indexOf(currentStep)
        // Use copy() to set retry on the last step
        steps[index] = when (currentStep) {
            is StepSpec.Echo -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Shell -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Error -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Sleep -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Parallel -> currentStep.copy(retry = retryPolicy)
            is StepSpec.WithCredentialsBlock -> currentStep.copy(retry = retryPolicy)
        }
    }

    /**
     * Conditional execution using a when clause.
     */
    fun whenCondition(expression: String, block: StageScope.() -> Unit) {
        val condition = WhenCondition(expression)
        val tempScope = StageScope(stageName)
        tempScope.block()
        for (step in tempScope.steps) {
            steps.add(step)
        }
    }

    /**
     * Script block (Jenkins-style script { } for inline groovy/kotlin script).
     */
    fun script(block: ScriptScope.() -> Unit) {
        val scope = ScriptScope()
        scope.block()
        val scriptContent = scope.commands.joinToString("\n")
        if (scriptContent.isNotEmpty()) {
            steps.add(StepSpec.Shell(scriptContent, isScriptBlock = true))
        }
    }

    fun toStageBuilder(): StageBuilder = StageBuilder(stageName, steps.toList(), options, agent, environment?.values)
}

/**
 * Environment variables scope.
 */
class EnvironmentScope {
    private val values = mutableMapOf<String, String>()

    fun env(name: String, value: String) {
        values[name] = value
    }

    fun build(): Map<String, String> = values.toMap()
}

/**
 * Options scope for stage configuration.
 */
class OptionsScope {
    var timeout: Long? = null
    var retry: RetrySpec? = null
    var skip: Boolean = false

    fun timeout(seconds: Long) {
        timeout = seconds
    }

    fun retry(count: Int, delaySeconds: Long? = null) {
        retry = RetrySpec(count, delaySeconds)
    }

    fun skip(value: Boolean = true) {
        skip = value
    }

    fun build(): OptionsSpec = OptionsSpec(timeout, retry, skip)
}

/**
 * Post conditions scope.
 */
class PostScope {
    private val alwaysSteps = mutableListOf<StepSpec>()
    private val successSteps = mutableListOf<StepSpec>()
    private val failureSteps = mutableListOf<StepSpec>()

    fun always(block: PostStepsScope.() -> Unit) {
        val scope = PostStepsScope()
        scope.block()
        alwaysSteps.addAll(scope.steps)
    }

    fun success(block: PostStepsScope.() -> Unit) {
        val scope = PostStepsScope()
        scope.block()
        successSteps.addAll(scope.steps)
    }

    fun failure(block: PostStepsScope.() -> Unit) {
        val scope = PostStepsScope()
        scope.block()
        failureSteps.addAll(scope.steps)
    }

    fun build(): PostConditionSpec = PostConditionSpec(alwaysSteps, successSteps, failureSteps)
}

/**
 * Steps within post condition blocks.
 */
class PostStepsScope {
    val steps = mutableListOf<StepSpec>()

    fun echo(text: String) {
        steps.add(StepSpec.Echo(text))
    }

    fun sh(command: String) {
        steps.add(StepSpec.Shell(command))
    }

    fun error(message: String, failureKind: String = "UNKNOWN") {
        steps.add(StepSpec.Error(message, failureKind))
    }

    fun sleep(seconds: Long) {
        steps.add(StepSpec.Sleep(seconds))
    }
}

/**
 * Parallel execution scope.
 */
class ParallelScope {
    private val branches = mutableListOf<StepSpec.BranchSpec>()

    fun branch(name: String, block: BranchScope.() -> Unit) {
        val scope = BranchScope()
        scope.block()
        branches.add(StepSpec.BranchSpec(name, scope.steps))
    }

    fun build(): List<StepSpec.BranchSpec> = branches.toList()
}

/**
 * Branch scope within parallel block.
 */
class BranchScope {
    val steps = mutableListOf<StepSpec>()

    fun echo(text: String) {
        steps.add(StepSpec.Echo(text))
    }

    fun sh(command: String) {
        steps.add(StepSpec.Shell(command))
    }

    fun error(message: String, failureKind: String = "UNKNOWN") {
        steps.add(StepSpec.Error(message, failureKind))
    }

    fun sleep(seconds: Long) {
        steps.add(StepSpec.Sleep(seconds))
    }
}

/**
 * Script scope for inline script blocks.
 */
class ScriptScope {
    val commands = mutableListOf<String>()

    /**
     * Adds a command line to the script.
     */
    fun line(command: String) {
        commands.add(command)
    }
}

/**
 * Builder for a stage, capturing its name, steps, options, agent, and environment.
 */
class StageBuilder(
    private val name: String,
    private val steps: List<StepSpec>,
    private val options: OptionsSpec? = null,
    private val agent: AgentSpec? = null,
    private val environment: Map<String, String>? = null,
) {
    fun build(): StageSpec = StageSpec(name, steps, options, agent, environment)
}
