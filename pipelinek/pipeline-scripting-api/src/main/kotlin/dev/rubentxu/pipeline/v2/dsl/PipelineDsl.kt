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
    return PipelineSpec(
        stages = scope.buildStages(),
        parameters = scope.buildParameters(),
        environment = scope.buildEnvironment(),
        post = scope.buildPost(),
    )
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
    return PipelineSpec(
        stages = scope.buildStages(),
        parameters = scope.buildParameters(),
        environment = scope.buildEnvironment(),
        post = scope.buildPost(),
    )
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

    /**
     * The variables the pipeline declares, and the default the stages that
     * declare none run with.
     *
     * The variables are handed to every stage the pipeline opens, and a stage
     * that declares its own keeps it: the pipeline's variables are the DEFAULT
     * of a stage.
     */
    private var pipelineEnvironment: EnvironmentSpec? = null
    private var pipelinePost: PostConditionSpec = PostConditionSpec()

    fun stages(block: StagesScope.() -> Unit) {
        val scope = StagesScope(runtimeConfig)
        // The VARIABLES the pipeline carries are handed to the stages as they
        // are KNOWN at this point. A declaration written AFTER this block is
        // applied to the stages anyway, by [buildStages], but a READ inside a
        // stage cannot see it: the read happens while the plan is built, and the
        // plan is built while this block runs. That is the same order rule the
        // stage level already states — the `environment { }` block has to be
        // written BEFORE the step that reads the variable.
        scope.pipelineEnvironment = pipelineEnvironment
        scope.block()
        scope.buildStageBuilders().forEach { stageBuilders.add(it) }
    }

    /**
     * The variables the pipeline declares for its stages.
     *
     * The block is the same one a stage writes, with the same
     * [PipelineEnvironmentScope.env]: `environment { env("TARGET", "staging") }`.
     *
     * A stage that declares a variable of its own keeps its own value — the
     * precedence is the one the gate already uses, a declaration beats a value
     * that arrives from outside — and a stage that declares none runs with the
     * pipeline's variables. The precedence is decided NAME BY NAME, so a stage
     * that declares `TARGET` still inherits the pipeline's `REGISTRY`.
     *
     * A second block ADDS to the first: the variables of both reach the stages,
     * and the name both declare is the one of the LATER block. The stage level
     * REPLACES instead, so the two levels differ here, and the difference is
     * a decision: a pipeline that declares a variable twice in two blocks has
     * asked for two things, and the one that answers is the last one said.
     *
     * The declaration is read as the script runs, so the `environment { }` block
     * has to be written BEFORE the `stages { }` block for a READ inside a stage
     * to see it. The variables themselves reach the stages either way: they are
     * the environment the steps of those stages run with.
     *
     * @param block the declaration, written with [PipelineEnvironmentScope.env]
     */
    fun environment(block: PipelineEnvironmentScope.() -> Unit) {
        val scope = PipelineEnvironmentScope()
        scope.block()
        val declared = scope.build()
        val previous = pipelineEnvironment?.values
        pipelineEnvironment = EnvironmentSpec(
            if (previous == null) declared else previous + declared,
        )
    }

    /**
     * The `post` blocks the pipeline declares, which run when the PIPELINE
     * closes.
     *
     * The block is the SAME one a stage writes, on the SAME [PostScope], with
     * the same seven conditions: `always`, `success`, `failure`, `unstable`,
     * `aborted`, `unsuccessful` and `cleanup`. There is no second vocabulary
     * for the pipeline level, so what an author knows about a stage's `post`
     * is exactly what an author knows about the pipeline's.
     *
     * The difference is the OUTCOME the conditions are decided over. A stage's
     * `post` reacts to how ITS OWN stage finished; the pipeline's reacts to
     * how the WHOLE RUN finished, so `success` means that every stage
     * succeeded and `failure` that the run failed.
     *
     * A second `post { }` block REPLACES the first, exactly as a stage's does:
     * the pipeline declares one block, and the second one is the declaration.
     *
     * @param block the declaration, written with the seven conditions of
     *   [PostScope]
     */
    fun post(block: PostScope.() -> Unit) {
        val scope = PostScope()
        scope.block()
        pipelinePost = scope.build()
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

    /**
     * The value of the environment variable named [name].
     *
     * The value is the one the pipeline declares with `environment { }` when it
     * declares one, and the one the PROCESS that builds the plan has when it
     * declares none. A stage that declares `environment { env("TARGET", "staging") }`
     * does not change what this reads: the declaration belongs to the stage, and
     * it is the stage that reads it.
     *
     * The read happens WHILE THE PLAN IS BUILT, so the value travels inside the
     * plan and a replay reproduces it. It is not read again when the step runs.
     *
     * A variable that is not defined answers null and not an empty text: "not
     * declared" and "declared as empty" are two different statements, and only
     * the second one is a value.
     *
     * What it cannot answer is the material of a credential. A credential reaches
     * the step through the environment of the child process, and the environment
     * of this process never holds it.
     *
     * @param name the name of the variable, as the operating system spells it
     */
    fun env(name: String): String? {
        val declared = pipelineEnvironment
        if (declared != null) {
            val value = declared.values[name]
            if (value != null) return value
        }
        return runtimeConfig.env(name)
    }

    /**
     * The stages of the pipeline, with the declarations of the pipeline applied
     * to every stage that declares none of its own.
     *
     * The application happens HERE, at the end of the pipeline's block, so a
     * declaration written after the `stages { }` block still reaches the
     * stages. The stage's own declaration wins over the pipeline's, name by
     * name.
     *
     * It is the ONE place where the pipeline's declarations reach a stage, and
     * the rule of the variables is the one of [DeclaredVariables] — the same
     * rule the READ inside a stage follows, so the two views cannot disagree
     * about which declaration wins.
     */
    fun buildStages(): List<StageSpec> = stageBuilders.map { builder ->
        val stage = builder.build()
        stage.copy(
            environment = DeclaredVariables.merged(pipelineEnvironment?.values, stage.environment),
        )
    }

    fun buildParameters(): List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> =
        parameterSpecs.toList()

    /**
     * The `post` the pipeline declares, or an empty declaration when it
     * declares none.
     */
    fun buildPost(): PostConditionSpec = pipelinePost

    /**
     * The variables the pipeline declares, or `null` when it declares none.
     *
     * `null` and an empty map are different statements: the first says the
     * pipeline declared no variables, and the second says it declared a
     * variable whose name is empty — which the declaration itself cannot
     * express, so the distinction is kept.
     */
    fun buildEnvironment(): Map<String, String>? = pipelineEnvironment?.values
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
     * The [defaultValue] is the text a caller receives when it delivers nothing, and it is
     * the name Jenkins gives it. The historical spelling (`default`) is still accepted and
     * still declares the same value; it carries a deprecation notice that names this one.
     */
    fun string(name: String, defaultValue: String? = null) {
        declare(
            name = name,
            type = RunInputs.TYPE_STRING,
            defaultValue = defaultValue,
        )
    }

    /**
     * The ALIAS of [string] that keeps the historical spelling of the value by default.
     *
     * It exists so a script that writes `default = ` still compiles, and it says so: the
     * notice it carries names the spelling that replaces it. When both spellings arrive, the
     * new one wins.
     */
    @Deprecated(
        message = "`default` is the historical spelling of the value by default: use `defaultValue`.",
        replaceWith = ReplaceWith("string(name, defaultValue = defaultValue ?: default)"),
    )
    fun string(name: String, default: String?, defaultValue: String? = null) {
        declare(
            name = name,
            type = RunInputs.TYPE_STRING,
            defaultValue = defaultValue ?: default,
        )
    }

    /**
     * A parameter that accepts `true` or `false`.
     *
     * The [defaultValue] is written as the boolean it is, and the text that reaches the
     * contract is the spelling of that boolean: a declaration that carried a free text here
     * would let an author write a default the type cannot hold. The historical spelling
     * (`default`) is still accepted and still declares the same value; it carries a
     * deprecation notice that names this one.
     */
    fun boolean(name: String, defaultValue: Boolean? = null) {
        declare(
            name = name,
            type = RunInputs.TYPE_BOOLEAN,
            defaultValue = defaultValue?.toString(),
        )
    }

    /**
     * The ALIAS of [boolean] that keeps the historical spelling of the value by default.
     *
     * It exists so a script that writes `default = ` still compiles, and it says so: the
     * notice it carries names the spelling that replaces it. When both spellings arrive, the
     * new one wins.
     */
    @Deprecated(
        message = "`default` is the historical spelling of the value by default: use `defaultValue`.",
        replaceWith = ReplaceWith("boolean(name, defaultValue = defaultValue ?: default)"),
    )
    fun boolean(name: String, default: Boolean?, defaultValue: Boolean? = null) {
        declare(
            name = name,
            type = RunInputs.TYPE_BOOLEAN,
            defaultValue = (defaultValue ?: default)?.toString(),
        )
    }

    /**
     * A parameter that accepts one of the [values].
     *
     * The set is closed: a caller that delivers a text outside it is refused, and the
     * refusal names the parameter. The [defaultValue] has to be one of the texts of the set,
     * and the checker that decides it is the one that checks every value. The historical
     * spelling (`default`) is still accepted and still declares the same value; it carries a
     * deprecation notice that names this one.
     */
    fun choice(name: String, values: List<String>, defaultValue: String? = null) {
        declare(
            name = name,
            type = RunInputs.TYPE_CHOICE,
            defaultValue = defaultValue,
            choices = values.toList(),
        )
    }

    /**
     * The ALIAS of [choice] that keeps the historical spelling of the value by default.
     *
     * It exists so a script that writes `default = ` still compiles, and it says so: the
     * notice it carries names the spelling that replaces it. When both spellings arrive, the
     * new one wins.
     */
    @Deprecated(
        message = "`default` is the historical spelling of the value by default: use `defaultValue`.",
        replaceWith = ReplaceWith("choice(name, values, defaultValue = defaultValue ?: default)"),
    )
    fun choice(name: String, values: List<String>, default: String?, defaultValue: String? = null) {
        declare(
            name = name,
            type = RunInputs.TYPE_CHOICE,
            defaultValue = defaultValue ?: default,
            choices = values.toList(),
        )
    }

    /**
     * The declaration every spelling of a parameter ends in.
     *
     * The spellings differ in what the author writes, not in what the run receives: one
     * declaration, one parameter, one value.
     */
    private fun declare(
        name: String,
        type: String,
        defaultValue: String?,
        choices: List<String>? = null,
    ) {
        specs.add(
            dev.rubentxu.pipeline.v2.domain.ParameterSpec(
                name = name,
                type = type,
                defaultValue = defaultValue,
                choices = choices.orEmpty(),
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

    /**
     * The VARIABLES of the PIPELINE, handed to every stage this scope opens.
     *
     * They are the DEFAULT of a stage: a stage that declares a variable of its
     * own keeps it. What the pipeline declares is applied at the end of the
     * pipeline's block, so the place the declaration is written does not
     * decide which variables a stage runs with.
     */
    internal var pipelineEnvironment: EnvironmentSpec? = null

    fun stage(name: String, block: StageScope.() -> Unit) {
        val scope = StageScope(name, runtimeConfig)
        scope.pipelineEnvironment = pipelineEnvironment
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
 * one. It answers nothing it does not know: no variable, no property, and no
 * platform.
 *
 * The platform it reports is the EMPTY name, and the canonical classification
 * of an empty name is "not a Unix-like platform": a port that has no platform
 * to report cannot answer that the machine is one. So a script built without
 * a port behind it takes the branch that is not the Unix one, and `pwd()`
 * answers the sentinel `<workspace>` as it always does.
 *
 * Production callers must pass an explicit config; see the [pipeline]
 * overload that accepts a [dev.rubentxu.pipeline.v2.domain.RuntimeConfig].
*/
internal object StubRuntimeConfig : dev.rubentxu.pipeline.v2.domain.RuntimeConfig {
    override fun env(name: String): String? = null
    override fun property(name: String): String? = null
    override fun property(name: String, default: String): String = default
    override fun osName(): String = ""
    override fun userDir(): String = ""
}


/**
 * Environment variables scope: the one a STAGE opens.
 *
 * ## Why it carries the marker of the STEP layer
 *
 * The scope is the receiver of an `environment { }` block, and the block DECLARES
 * variables: it says `env("TARGET", "staging")`. The stage's own `env(name)`
 * READS a variable, so the two spellings differ in their ARITY and not in their
 * intent — and a block that declared `env("TARGET")` would read a variable
 * instead of declaring one, silently, with nothing to say so.
 *
 * The stage layer refuses that shape: the scope that opens the block carries
 * [StepDslMarker] and hides the stage's own `env(name)`, so the one-argument
 * call has no receiver to reach and the compiler refuses it.
 *
 * ## Why this scope keeps the reach it was published with
 *
 * The block of a STAGE is published surface, and what is reachable inside it
 * is part of that surface: a script that wrote a call of the pipeline inside a
 * stage's `environment { }` block compiles today. The pipeline level, which is
 * NEW, has a scope of its own — [PipelineEnvironmentScope] — and it is there
 * that the pipeline's calls are kept out of reach. Sharing one scope for the
 * two levels would have taken a published reach away for a change that did
 * not need it.
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
 * The scope of the variables a PIPELINE declares.
 *
 * ## Why its OWN scope and not the one of a stage
 *
 * The block that opens at the pipeline level is a NEW surface, and a new
 * surface can have a receiver of its own. [EnvironmentScope] is published
 * since the version 0.36.0 of this module, and what is reachable inside the
 * block of a stage is part of that publication; a scope of its own leaves
 * that reach exactly as it is.
 *
 * ## What the marker of the pipeline layer refuses
 *
 * The block DECLARES variables, and the pipeline's own `env(name)` READS one:
 * the two spellings differ in their arity, and a block that wrote
 * `env("TARGET")` would read a variable instead of declaring one, silently,
 * with nothing to say so. The marker of the pipeline layer keeps that read
 * out of reach here, and it also keeps the pipeline's `stages { }`,
 * `parameters { }`, `buildStages()` and `buildParameters()` out of reach: the
 * block declares variables, and nothing else.
 */
@StepDslMarker
@PipelineDslMarker
class PipelineEnvironmentScope {
    private val values = mutableMapOf<String, String>()

    /**
     * The variable named [name], declared with the value [value].
     */
    fun env(name: String, value: String) {
        values[name] = value
    }

    /**
     * The variables the block declares.
     */
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
    private val alwaysSteps = StepSequence()
    private val successSteps = StepSequence()
    private val failureSteps = StepSequence()
    private val unstableSteps = StepSequence()
    private val abortedSteps = StepSequence()
    private val unsuccessfulSteps = StepSequence()
    private val cleanupSteps = StepSequence()

    fun always(block: PostStepsScope.() -> Unit) = record(alwaysSteps, block)

    fun success(block: PostStepsScope.() -> Unit) = record(successSteps, block)

    fun failure(block: PostStepsScope.() -> Unit) = record(failureSteps, block)

    fun unstable(block: PostStepsScope.() -> Unit) = record(unstableSteps, block)

    fun aborted(block: PostStepsScope.() -> Unit) = record(abortedSteps, block)

    fun unsuccessful(block: PostStepsScope.() -> Unit) = record(unsuccessfulSteps, block)

    fun cleanup(block: PostStepsScope.() -> Unit) = record(cleanupSteps, block)

    private fun record(into: StepSequence, block: PostStepsScope.() -> Unit) {
        val scope = PostStepsScope()
        scope.block()
        into.declareAll(scope.capture())
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
        steps: StepSequence,
    ) {
        val captured = steps.capture()
        if (captured.isNotEmpty()) put(condition, captured)
    }
}

/**
 * Steps within post condition blocks.
*/
@PostDslMarker
class PostStepsScope : StepEmitter<PostStepsScope> {
    /**
     * The owner of this scope's sequence of steps.
     *
     * The list it writes into is the one this scope publishes: the scope and
     * the owner hold ONE list, and the scope declares into it through the
     * owner.
     */
    private val sequence = StepSequence()

    /**
     * The steps this scope declares.
     *
     * The list belongs to the OWNER and this scope publishes it, so the two are
     * one list and not two.
     */
    val steps: MutableList<StepSpec> get() = sequence.published()

    /** The steps declared so far, as they are: a snapshot. */
    internal fun capture(): List<StepSpec> = sequence.capture()

    fun echo(text: String) {
        sequence.echo(text)
    }

    fun sh(command: String) {
        sequence.shell(command)
    }

    fun error(message: String, failureKind: FailureKind = FailureKind.USER) {
        sequence.error(message, failureKind)
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
        sequence.error(message, LegacyResultVocabulary.failureKind(failureKind))
    }

    fun sleep(seconds: Long) {
        sequence.sleep(seconds)
    }

    /**
     * REFUSED: a post condition hosts no step of a plugin.
     *
     * @throws IllegalArgumentException always, and the message names the
     *   restriction and the step it refused.
     */
    override fun registryStep(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
    ) {
        throw IllegalArgumentException(refusesAPluginStep(stepKey))
    }

    /**
     * REFUSED: a post condition hosts no block of a plugin.
     *
     * @throws IllegalArgumentException always, and the message names the
     *   restriction and the step it refused.
     */
    override fun registryBlock(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        block: PostStepsScope.() -> Unit,
    ) {
        throw IllegalArgumentException(refusesAPluginStep(stepKey))
    }

    /**
     * The refusal, and it says WHAT was refused and WHY.
     */
    private fun refusesAPluginStep(stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId): String =
        "a post condition hosts no step of a plugin: '$stepKey' was refused. The conditions " +
            "that close a stage run the steps the DSL provides (echo, sh, error, sleep) and " +
            "not the steps a plugin contributes."
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
        branches.add(StepSpec.BranchSpec(name, scope.capture()))
    }

    fun build(): List<StepSpec.BranchSpec> = branches.toList()
}

/**
 * Branch scope within parallel block.
 *
 * The scope implements the [StepEmitter] contract: a branch is a body of
 * steps of its own, and a plugin's step belongs in a branch as much as it
 * belongs in a stage.
*/
@StepDslMarker
class BranchScope : StepEmitter<BranchScope> {
    /**
     * The owner of this scope's sequence of steps.
     *
     * The list it writes into is the one this scope publishes: the scope and
     * the owner hold ONE list, and the scope declares into it through the
     * owner.
     */
    private val sequence = StepSequence()

    /**
     * The steps this scope declares.
     *
     * The list belongs to the OWNER and this scope publishes it, so the two are
     * one list and not two.
     */
    val steps: MutableList<StepSpec> get() = sequence.published()

    /** The steps declared so far, as they are: a snapshot. */
    internal fun capture(): List<StepSpec> = sequence.capture()

    fun echo(text: String) {
        sequence.echo(text)
    }

    fun sh(command: String) {
        sequence.shell(command)
    }

    fun error(message: String, failureKind: FailureKind = FailureKind.USER) {
        sequence.error(message, failureKind)
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
        sequence.error(message, LegacyResultVocabulary.failureKind(failureKind))
    }

    fun sleep(seconds: Long) {
        sequence.sleep(seconds)
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
        sequence.dir(path, inner.capture())
    }

    /**
     * Declares a step the plugin's own handler runs.
     *
     * @param stepKey the key the plugin registered its step under.
     * @param encodedInput the step's input, in the plugin's own encoding.
     */
    override fun registryStep(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
    ) {
        sequence.declare(
            StepSpec.RegistryStepSpec(stepKey, "dsl-v1", encodedInput),
        )
    }

    /**
     * Declares a step that carries a body of steps.
     *
     * The body is written in the branch, and the branch is the scope that
     * hosts it: the body of a plugin's block inside a branch is a body of
     * steps of that branch.
     *
     * @param stepKey the key the plugin registered its step under.
     * @param encodedInput the step's input, in the plugin's own encoding.
     * @param block the steps the block governs.
     */
    override fun registryBlock(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        block: BranchScope.() -> Unit,
    ) {
        val inner = BranchScope()
        inner.block()
        sequence.declare(
            StepSpec.RegistryBlockSpec(stepKey, "dsl-v1", encodedInput, inner.capture()),
        )
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
        sequence.lock(
            resource = resource,
            timeoutSeconds = timeoutSeconds,
            reason = reason,
            skipIfLocked = skipIfLocked,
            body = inner.capture(),
        )
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
