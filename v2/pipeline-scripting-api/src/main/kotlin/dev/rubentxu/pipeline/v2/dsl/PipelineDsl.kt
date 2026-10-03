package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm

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
    val scope = PipelineScope(currentRuntimeConfig())
    scope.block()
    return PipelineSpec(stages = scope.buildStages())
}

/**
 * Sets the [dev.rubentxu.pipeline.v2.domain.RuntimeConfig] the DSL `pwd()` /
 * `isUnix()` synchronous helpers read from for the current thread.
 *
 * Production adapters (the `pipeline-application` CLI) call this before
 * compiling and executing a user pipeline script so synchronous return
 * values reflect the host environment instead of placeholder values. Tests
 * can call it with a deterministic `MapRuntimeConfig`. Nested calls are
 * supported via a stack; the previous config is restored on `clear()`.
 */
public object DslRuntimeConfigScope {
    private val stack: ThreadLocal<ArrayDeque<dev.rubentxu.pipeline.v2.domain.RuntimeConfig>> =
        ThreadLocal.withInitial { ArrayDeque() }

    @JvmStatic
    fun set(config: dev.rubentxu.pipeline.v2.domain.RuntimeConfig) {
        stack.get().addLast(config)
    }

    @JvmStatic
    fun clear() {
        val q = stack.get()
        if (q.isNotEmpty()) q.removeLast()
        if (q.isEmpty()) stack.remove()
    }

    @JvmStatic
    fun current(): dev.rubentxu.pipeline.v2.domain.RuntimeConfig =
        stack.get().lastOrNull() ?: StubRuntimeConfig
}

internal fun currentRuntimeConfig(): dev.rubentxu.pipeline.v2.domain.RuntimeConfig =
    DslRuntimeConfigScope.current()

/**
 * Variant of [pipeline] that accepts an explicit [RuntimeConfig] so the DSL
 * stays decoupled from global JVM state (see
 * `Lfc0GlobalStateFitnessTest`).
 *
 * Production callers (the `pipeline-application` CLI) should pass the
 * `SystemRuntimeConfig` adapter. Tests can pass a deterministic
 * `MapRuntimeConfig`.
 */
fun pipeline(
    runtimeConfig: dev.rubentxu.pipeline.v2.domain.RuntimeConfig,
    block: PipelineScope.() -> Unit,
): PipelineSpec {
    val scope = PipelineScope(runtimeConfig)
    scope.block()
    return PipelineSpec(stages = scope.buildStages())
}

/**
 * WU-LPR-401 — DSL isolation markers.
 *
 * Each marker declares a separate lexical layer of the DSL. A `@DslMarker`
 * on a receiver type tells the Kotlin compiler to reject implicit `this`
 * from an outer scope when an inner scope is in scope: a method that
 * belongs to [PipelineScope] cannot be called from inside a
 * [StageScope] lambda, and vice versa. The user gets a compile-time
 * error when they accidentally try to nest DSL calls in the wrong scope.
 *
 * The four layers are deliberately separate markers, not one umbrella
 * marker: when [StagesScope] and [PipelineScope] both carried the same
 * marker, a `stages { pipeline { ... } }` mistake would be rejected the
 * same as a `stages { stages { ... } }` mistake — but those are different
 * errors and the user-facing message should reflect that.
 *
 * What these markers do NOT do: they do not change the API surface, do
 * not add runtime checks, and do not affect existing pipelines that use
 * the DSL correctly. The only observable change is that misuse becomes
 * a compile error instead of a silent miscompile.
 */
@DslMarker
annotation class PipelineDslMarker

@DslMarker
annotation class StageDslMarker

@DslMarker
annotation class StepDslMarker

@DslMarker
annotation class PostDslMarker

/**
 * Receiver scope for the `stages { }` block inside `pipeline { }`.
 */
@PipelineDslMarker
class PipelineScope(
    private val runtimeConfig: dev.rubentxu.pipeline.v2.domain.RuntimeConfig =
        currentRuntimeConfig(),
) {
    private val stageBuilders = mutableListOf<StageBuilder>()

    fun stages(block: StagesScope.() -> Unit) {
        val scope = StagesScope(runtimeConfig)
        scope.block()
        scope.buildStageBuilders().forEach { stageBuilders.add(it) }
    }

    fun buildStages(): List<StageSpec> = stageBuilders.map { it.build() }
}

/**
 * Receiver scope for the `stage("name") { }` block inside `stages { }`.
 */
@StageDslMarker
class StagesScope(
    private val runtimeConfig: dev.rubentxu.pipeline.v2.domain.RuntimeConfig =
        currentRuntimeConfig(),
) {
    private val stageBuilders = mutableListOf<StageBuilder>()

    fun stage(name: String, block: StageScope.() -> Unit) {
        val scope = StageScope(name, runtimeConfig)
        scope.block()
        // S0-C1 (Pure Builder Consumption Gate): fail closed HERE, while the
        // pipeline is still being built, so an unconsumed MUST_CONSUME carrier
        // can never reach step admission, event emission or a process. This is
        // the earliest point at which the whole block is known and therefore
        // the last point at which a discard is still recoverable without
        // partial effect.
        scope.rejectUnconsumedCarriers()
        stageBuilders.add(scope.toStageBuilder())
    }

    fun buildStageBuilders(): List<StageBuilder> = stageBuilders
}

/**
 * Stub RuntimeConfig used as a default when the DSL is constructed without
 * one. Returns empty strings for OS-dependent queries so the DSL still
 * compiles but `pwd()` / `isUnix()` will return the placeholder values used
 * pre-v0.33.1. Production callers must pass an explicit config; see
 * [pipeline] overload that accepts a [dev.rubentxu.pipeline.v2.domain.RuntimeConfig].
 */
internal object StubRuntimeConfig : dev.rubentxu.pipeline.v2.domain.RuntimeConfig {
    override fun env(name: String): String? = null
    override fun property(name: String): String? = null
    override fun property(name: String, default: String): String = default
    override fun osName(): String = ""
    override fun userDir(): String = ""
}


/**
 * Environment variables scope.
 */
@StepDslMarker
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
@StepDslMarker
class OptionsScope {
    var timeout: Long? = null

    fun timeout(seconds: Long) {
        // S3.3: author input is validated HERE, at the construction boundary,
        // rather than being carried as a raw Long and rejected later. Two things
        // are decided by doing it now:
        //
        //  - the diagnostic names the author, not an internal carrier;
        //  - `StageOption.Timeout`'s own invariant can then be a plain
        //    precondition, because this is the only way to reach it.
        require(seconds > 0) {
            "options { timeout($seconds) } must be positive. A timeout of $seconds seconds is " +
                "not a deadline: it would never fire. If you meant 'no limit', omit the option " +
                "entirely, which is a different declaration."
        }
        timeout = seconds
    }

    fun build(): OptionsSpec = OptionsSpec(timeout)
}

/**
 * Post conditions scope.
 */
@PostDslMarker
class PostScope {
    private val alwaysSteps = mutableListOf<StepSpec>()
    private val successSteps = mutableListOf<StepSpec>()
    private val failureSteps = mutableListOf<StepSpec>()
    private val unstableSteps = mutableListOf<StepSpec>()
    private val abortedSteps = mutableListOf<StepSpec>()
    private val unsuccessfulSteps = mutableListOf<StepSpec>()
    private val cleanupSteps = mutableListOf<StepSpec>()

    fun always(block: PostStepsScope.() -> Unit) = record(alwaysSteps, block)

    fun success(block: PostStepsScope.() -> Unit) = record(successSteps, block)

    fun failure(block: PostStepsScope.() -> Unit) = record(failureSteps, block)

    fun unstable(block: PostStepsScope.() -> Unit) = record(unstableSteps, block)

    fun aborted(block: PostStepsScope.() -> Unit) = record(abortedSteps, block)

    fun unsuccessful(block: PostStepsScope.() -> Unit) = record(unsuccessfulSteps, block)

    fun cleanup(block: PostStepsScope.() -> Unit) = record(cleanupSteps, block)

    private fun record(into: MutableList<StepSpec>, block: PostStepsScope.() -> Unit) {
        val scope = PostStepsScope()
        scope.block()
        into.addAll(scope.steps)
    }

    /**
     * The declared blocks, keyed by the closed [PostCondition] set.
     *
     * Multiple blocks under the same condition CONCATENATE in declaration
     * order, which is the author's intent; the execution ORDER across different
     * conditions is not decided here but by the pure
     * [dev.rubentxu.pipeline.v2.domain.post.PostPlanner].
     *
     * Conditions with no declared block are omitted rather than stored empty, so
     * an empty `post { }` produces no entry at all.
     */
    fun build(): PostConditionSpec {
        val conditions = buildMap {
            putIfNotEmpty(dev.rubentxu.pipeline.v2.domain.post.PostCondition.ALWAYS, alwaysSteps)
            putIfNotEmpty(dev.rubentxu.pipeline.v2.domain.post.PostCondition.SUCCESS, successSteps)
            putIfNotEmpty(dev.rubentxu.pipeline.v2.domain.post.PostCondition.FAILURE, failureSteps)
            putIfNotEmpty(dev.rubentxu.pipeline.v2.domain.post.PostCondition.UNSTABLE, unstableSteps)
            putIfNotEmpty(dev.rubentxu.pipeline.v2.domain.post.PostCondition.ABORTED, abortedSteps)
            putIfNotEmpty(dev.rubentxu.pipeline.v2.domain.post.PostCondition.UNSUCCESSFUL, unsuccessfulSteps)
            putIfNotEmpty(dev.rubentxu.pipeline.v2.domain.post.PostCondition.CLEANUP, cleanupSteps)
        }
        return PostConditionSpec(conditions)
    }

    private fun MutableMap<dev.rubentxu.pipeline.v2.domain.post.PostCondition, List<StepSpec>>.putIfNotEmpty(
        condition: dev.rubentxu.pipeline.v2.domain.post.PostCondition,
        steps: List<StepSpec>,
    ) {
        if (steps.isNotEmpty()) put(condition, steps.toList())
    }
}

/**
 * Steps within post condition blocks.
 */
@PostDslMarker
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
@StepDslMarker
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
@StepDslMarker
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

    /**
     * Changes the current working directory for the duration of the nested
     * block within this branch (B1 / WU-RP-053R; the operator's composed
     * property test for parallel { left -> dir("a") -> effect; right -> ... }
     * requires [dir] to be available on [BranchScope], not only on
     * [StageScope]). The nested [block] is itself a [BranchScope] body, so the
     * user may write `branch("left") { dir("a") { sh("...") } }`.
     */
    fun dir(path: String, block: BranchScope.() -> Unit) {
        val inner = BranchScope()
        inner.block()
        steps.add(StepSpec.Dir(path = path, steps = inner.steps.toList()))
    }

    /**
     * `lock` inside a parallel branch (RP6-A / WU-091). The composed scenario
     * `branch("left") { lock("res") { ... } }` vs a sibling branch contending
     * for the same resource is exactly the case the durable execution lane
     * (`ExecutionLaneId = runId + parallelLineage`) was introduced for: two
     * sibling branches are DIFFERENT lanes and must contend; a nested `lock`
     * on the SAME lane re-enters. Same declarative shape as [dir].
     */
    fun lock(
        resource: String,
        timeoutSeconds: Int? = null,
        reason: String? = null,
        skipIfLocked: Boolean = false,
        block: BranchScope.() -> Unit,
    ) {
        val inner = BranchScope()
        inner.block()
        steps.add(
            StepSpec.Lock(
                resource = resource,
                timeoutSeconds = timeoutSeconds,
                reason = reason,
                skipIfLocked = skipIfLocked,
                steps = inner.steps.toList(),
            ),
        )
    }
}

/**
 * Script scope for inline script blocks.
 */
@StepDslMarker
class ScriptScope {
    val commands = mutableListOf<String>()

    /**
     * Adds a command line to the script.
     */
    fun line(command: String) {
        commands.add(command)
    }

    // WU-LPR-071 (fixture 05-scripted-if): the @DslMarker hierarchy (LPR-401) correctly
    // blocks implicit outer receivers inside `script { }`, so script bodies can no longer
    // resolve `echo`/`sh` from [StageScope]. Jenkins-familiar script blocks still expect
    // these step verbs, so the scope carries its own step shims that record the
    // equivalent shell command. Kotlin control flow (if/when/loops) around them is real
    // code evaluated at DSL-construction time — only the chosen branch is recorded.
    fun echo(text: String) {
        commands.add("echo \"${text.replace("\"", "\\\"")}\"")
    }

    fun sh(command: String) {
        commands.add(command)
    }

    fun error(message: String) {
        commands.add("echo \"$message\" >&2; exit 1")
    }
}

/**
 * Builder for a stage, capturing its name, steps, options, environment, and
 * declared directives (S1-B).
 */
class StageBuilder(
    private val name: String,
    private val steps: List<StepSpec>,
    private val options: OptionsSpec? = null,
    private val environment: Map<String, String>? = null,
    private val directives: List<dev.rubentxu.pipeline.v2.domain.StageDirective> = emptyList(),
    private val post: PostConditionSpec = PostConditionSpec(),
) {
    fun build(): StageSpec = StageSpec(name, steps, options, environment, directives, post)
}

/**
 * LF-0401 conversion: turn the DSL flat [StepSpec.CredentialsBinding] into
 * the sealed-typed `:pipeline-domain` [dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec].
 *
 * The DSL type stays as-is (scripts and the binding-factory tests still
 * produce it), but at the executor call site the conversion is performed
 * once. This is the inversion that lets `:pipeline-credentials-executor`
 * depend on `:pipeline-domain` (typed) instead of the DSL flat shape.
 */
fun StepSpec.CredentialsBinding.toSpec():
    dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec =
    when (kind) {
        StepSpec.CredentialsBinding.Kind.STRING ->
            dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec(
                credentialsId = credentialsId,
                variable = variable
                    ?: throw IllegalArgumentException("STRING binding requires a variable name"),
            )
        StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD ->
            dev.rubentxu.pipeline.v2.domain.credentials.UsernamePasswordBindingSpec(
                credentialsId = credentialsId,
                usernameVariable = usernameVariable
                    ?: throw IllegalArgumentException("USERNAME_PASSWORD binding requires usernameVariable"),
                passwordVariable = passwordVariable
                    ?: throw IllegalArgumentException("USERNAME_PASSWORD binding requires passwordVariable"),
            )
        StepSpec.CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY ->
            dev.rubentxu.pipeline.v2.domain.credentials.SshUserPrivateKeyBindingSpec(
                credentialsId = credentialsId,
                keyFileVariable = keyFileVariable
                    ?: throw IllegalArgumentException("SSH_USER_PRIVATE_KEY binding requires keyFileVariable"),
                passphraseVariable = passphraseVariable,
                usernameVariable = usernameVariable,
            )
        StepSpec.CredentialsBinding.Kind.FILE ->
            dev.rubentxu.pipeline.v2.domain.credentials.FileBindingSpec(
                credentialsId = credentialsId,
                variable = variable
                    ?: throw IllegalArgumentException("FILE binding requires a variable name"),
            )
        StepSpec.CredentialsBinding.Kind.CERTIFICATE ->
            dev.rubentxu.pipeline.v2.domain.credentials.CertificateBindingSpec(
                keystoreVariable = keystoreVariable
                    ?: throw IllegalArgumentException("CERTIFICATE binding requires keystoreVariable"),
                credentialsId = credentialsId,
                aliasVariable = aliasVariable,
                passwordVariable = passwordVariable,
            )
        StepSpec.CredentialsBinding.Kind.ZIP ->
            dev.rubentxu.pipeline.v2.domain.credentials.ZipBindingSpec(
                variable = variable
                    ?: throw IllegalArgumentException("ZIP binding requires a variable name"),
                credentialsId = credentialsId,
            )
        StepSpec.CredentialsBinding.Kind.USERNAME_COLON_PASSWORD ->
            dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPasswordBindingSpec(
                variable = variable
                    ?: throw IllegalArgumentException("USERNAME_COLON_PASSWORD binding requires a variable name"),
                credentialsId = credentialsId,
            )
    }
