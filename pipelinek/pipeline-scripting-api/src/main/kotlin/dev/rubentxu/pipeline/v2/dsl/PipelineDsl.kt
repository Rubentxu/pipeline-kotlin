package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.FailureKind

/**
 * Top-level `pipeline { }` DSL entry point.
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
    return PipelineSpec(stages = scope.buildStages(), parameters = scope.buildParameters())
}

/**
 * Sets the [dev.rubentxu.pipeline.v2.domain.RuntimeConfig] the DSL `pwd()` /
 * `isUnix()` synchronous helpers read from for the current thread.
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
 * Variant of [pipeline] that accepts an explicit `RuntimeConfig` so the DSL
 * stays decoupled from global JVM state.
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
    return PipelineSpec(stages = scope.buildStages(), parameters = scope.buildParameters())
}

/**
 * The marker of the pipeline layer: the scope a `pipeline { }` block opens.
 *
 * A DSL marker hides an outer receiver that carries the SAME marker, so the
 * scopes of one layer do not see each other: a call that would resolve to the
 * outer scope is refused by the compiler instead of running on it.
 *
 * The four markers of the DSL are separate annotations, not a hierarchy, so
 * the layers do not hide each other: the pipeline scope's calls stay
 * reachable from a `stages { }` block, and a `stages { stages { ... } }`
 * nesting compiles. What a block of one layer may use from another layer is
 * decided by the scopes themselves, not by the compiler.
 *
 * What these markers do NOT do: they do not change the API surface and they
 * add no runtime check. Their effect is compile-time: a scope of the same
 * layer cannot be reached by mistake, so a pipeline that relied on an outer
 * scope's call is refused by the compiler and needs the scope's own spelling.
 */
@DslMarker
annotation class PipelineDslMarker

/**
 * The marker of the stage layer: the scope a `stages { }` block opens.
 *
 * It separates the stage layer from itself: a scope carrying this marker
 * nested inside another scope carrying it does not see the outer one, so the
 * compiler refuses a call that would resolve to the outer scope. Only the
 * stages scope carries it today, so the layer has a single scope and the rule
 * has nothing to hide yet.
 */
@DslMarker
annotation class StageDslMarker

/**
 * The marker of the step layer: the scopes a step body opens — the stage
 * scope, the directives scope, the environment and options scopes, the
 * parallel, branch and script scopes.
 *
 * It separates the step layer from itself: inside a branch the parallel
 * scope's own calls are out of reach, and inside a step body the stage
 * scope's own calls are out of reach. The stage scope and the directives
 * scope share it, so a directives block cannot reach the stage scope's calls
 * either.
 */
@DslMarker
annotation class StepDslMarker

/**
 * The marker of the post layer: the scopes a post-condition block opens.
 *
 * It separates the post layer from itself, so the outer post scope's calls
 * are out of reach from inside the steps of a post condition.
 */
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
    private val parameterSpecs = mutableListOf<dev.rubentxu.pipeline.v2.domain.ParameterSpec>()

    fun stages(block: StagesScope.() -> Unit) {
        val scope = StagesScope(runtimeConfig)
        scope.block()
        scope.buildStageBuilders().forEach { stageBuilders.add(it) }
    }

    /**
     * The parameters the pipeline declares it accepts.
     *
     * The block is the author's declaration, and it is carried as data: nothing here decides
     * what a caller may send, and nothing here reads a value. The rules that decide that
     * live in [RunInputs], so the DSL and the checker cannot disagree about what a
     * declaration means.
     */
    fun parameters(block: ParametersScope.() -> Unit) {
        val scope = ParametersScope()
        scope.block()
        parameterSpecs.addAll(scope.build())
    }

    fun buildStages(): List<StageSpec> = stageBuilders.map { it.build() }

    fun buildParameters(): List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> =
        parameterSpecs.toList()
}

/**
 * The marker of the parameters layer: the scope a `parameters { }` block opens.
 *
 * It separates the parameters from the pipeline around it, so a declaration cannot reach
 * the pipeline's own calls and a pipeline cannot reach a declaration's.
 */
@DslMarker
annotation class ParametersDslMarker

/**
 * Receiver scope for the `parameters { }` block inside `pipeline { }`.
 *
 * The scope offers one function per type the product knows, and no other way to declare a
 * parameter: a type the product cannot check a value against is a type a caller could never
 * satisfy, so it is not expressible here.
 *
 * A parameter that declares no default is a parameter a caller has to deliver. That rule is
 * the ONLY one about obligatoriedad, and it is written once, in the absence of the default:
 * there is no flag that says "required" and no flag that says "optional", so the two can
 * never say different things about the same parameter.
 */
@ParametersDslMarker
class ParametersScope {
    private val specs = mutableListOf<dev.rubentxu.pipeline.v2.domain.ParameterSpec>()

    /**
     * A parameter that accepts any text.
     *
     * The [default] is the text a caller receives when it delivers nothing.
     */
    fun string(name: String, default: String? = null) {
        specs.add(
            dev.rubentxu.pipeline.v2.domain.ParameterSpec(
                name = name,
                type = RunInputs.TYPE_STRING,
                defaultValue = default,
            ),
        )
    }

    /**
     * A parameter that accepts `true` or `false`.
     *
     * The [default] is written as the boolean it is, and the text that reaches the contract
     * is the spelling of that boolean: a declaration that carried a free text here would let
     * an author write a default the type cannot hold.
     */
    fun boolean(name: String, default: Boolean? = null) {
        specs.add(
            dev.rubentxu.pipeline.v2.domain.ParameterSpec(
                name = name,
                type = RunInputs.TYPE_BOOLEAN,
                defaultValue = default?.toString(),
            ),
        )
    }

    /**
     * A parameter that accepts one of the [values].
     *
     * The set is closed: a caller that delivers a text outside it is refused, and the
     * refusal names the parameter. The [default] has to be one of the texts of the set, and
     * the checker that decides it is the one that checks every value.
     */
    fun choice(name: String, values: List<String>, default: String? = null) {
        specs.add(
            dev.rubentxu.pipeline.v2.domain.ParameterSpec(
                name = name,
                type = RunInputs.TYPE_CHOICE,
                defaultValue = default,
                choices = values.toList(),
            ),
        )
    }

    fun build(): List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> = specs.toList()
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
        // Fail closed HERE, while the
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
    /**
     * The declared stage-wide timeout, in seconds.
     * Public, and VALIDATING, which is a correction rather than a detail. This was
     * a bare `var` with no setter, sitting beside `fun timeout(seconds)`, so inside
     * `options { … }` — where this scope is the receiver — `timeout = 5` was a
     * second way in that no diagnostic guarded. That was not hypothetical: two timeout
     * UATs failed to compile when the first fix made the property private, which is the
     * failure recorded two paragraphs down. The fixture that wrote `timeout = 2L` is not in
     * this tree, so the fact is kept and the citation is not.
     * Making the property private was the first fix and it was the WRONG one. It
     * removes the bypass, and it also breaks every existing `.pipeline.kts` that
     * assigns the property, which is a source break in a published DSL surface
     * disguised as a hardening. The full gate caught exactly that: two timeout
     * UATs failed to compile with "Cannot access 'var timeout': it is private".
     * So the property stays and the SETTER validates. That keeps the two syntaxes
     * (`timeout(30)` and `timeout = 30`) while making the unvalidated door
     * impossible rather than merely unused — and the setter and the function
     * delegate to [StageTimeout], so they cannot drift apart, which is what
     * produced the overflow in the first place.
*/
    var timeout: Long? = null
        set(value) {
            require(value == null || StageTimeout.isValid(value)) {
                "options { timeout } must be between 1 and ${StageTimeout.MAX_SECONDS} seconds, " +
                    "was $value. Two separate things are wrong with that: a timeout of zero or " +
                    "less is not a deadline, and a value this large has no millisecond form that " +
                    "fits a Long, so it could never be projected into one. If you meant " +
                    "'no limit', omit the option entirely, which is a different declaration."
            }
            field = value
        }

    fun timeout(seconds: Long) {
        // Author input is validated HERE, at the construction boundary,
        // rather than being carried as a raw Long and rejected later. Two things
        // are decided by doing it now:
        //  - the diagnostic names the author, not an internal carrier;
        //  - `StageOption.Timeout`'s own invariant can then be a plain
        //    precondition, because this is the only way to reach it.
        // Delegates to the validating setter rather than repeating the
        // check, so the function and the property have ONE authority between them.
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
     * The declared blocks, keyed by the closed `PostCondition` set.
     * Multiple blocks under the same condition CONCATENATE in declaration
     * order, which is the author's intent; the execution ORDER across different
     * conditions is not decided here but by the pure
     * [dev.rubentxu.pipeline.v2.domain.post.PostPlanner].
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

    fun error(message: String, failureKind: FailureKind = FailureKind.USER) {
        steps.add(StepSpec.Error(message, failureKind))
    }

    /**
     * The STABLE historical spelling, kept as a validated adapter.
     * `error` is STABLE. E6b typed the representation
     * (`StepSpec.Error.failureKind`) but this signature is the published authoring surface,
     * so `error("boom", "USER")` keeps compiling and is validated IMMEDIATELY at script
     * construction — see [LegacyResultVocabulary].
     * `failureKind` has NO default here on purpose. With one, `error("boom")` would be
     * ambiguous between the two overloads; without it, the bare call resolves to the typed
     * one and the legacy spelling is only reachable by actually passing a token.
*/
    fun error(message: String, failureKind: String) {
        steps.add(StepSpec.Error(message, LegacyResultVocabulary.failureKind(failureKind)))
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

    fun error(message: String, failureKind: FailureKind = FailureKind.USER) {
        steps.add(StepSpec.Error(message, failureKind))
    }

    /**
     * The STABLE historical spelling, kept as a validated adapter.
     * `error` is STABLE. E6b typed the representation
     * (`StepSpec.Error.failureKind`) but this signature is the published authoring surface,
     * so `error("boom", "USER")` keeps compiling and is validated IMMEDIATELY at script
     * construction — see [LegacyResultVocabulary].
     * `failureKind` has NO default here on purpose. With one, `error("boom")` would be
     * ambiguous between the two overloads; without it, the bare call resolves to the typed
     * one and the legacy spelling is only reachable by actually passing a token.
*/
    fun error(message: String, failureKind: String) {
        steps.add(StepSpec.Error(message, LegacyResultVocabulary.failureKind(failureKind)))
    }

    fun sleep(seconds: Long) {
        steps.add(StepSpec.Sleep(seconds))
    }

    /**
     * Changes the current working directory for the duration of the nested
     * Block within this branch (/; the operator's composed
     * property test for parallel { left -> dir("a") -> effect; right ->... }
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
     * `lock` inside a parallel branch. The composed scenario
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

    // The @DslMarker hierarchy correctly
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
 * declared directives.
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
 * Conversion: turn the DSL flat [StepSpec.CredentialsBinding] into
 * the sealed-typed `:pipeline-domain` [dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec].
 * Scripts produce the flat [StepSpec.CredentialsBinding]; at the executor call
 * site the conversion is performed once, so `:pipeline-credentials-executor`
 * depends on `:pipeline-domain` (typed) instead of the DSL flat shape.
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
