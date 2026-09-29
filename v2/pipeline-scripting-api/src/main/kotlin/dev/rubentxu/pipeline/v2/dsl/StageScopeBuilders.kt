package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm

/**
 * Shared mutable state for the stage DSL builders.
 *
 * S0-C1 (Pure Builder Consumption Gate): `[MustUseReturnValues]` is applied at
 * CLASS level, not per function, because Kotlin 2.4 restricts the annotation to
 * targets `file` and `class` (OBSERVED: compiling it on a member function fails
 * with "not applicable to target 'member function'").
 *
 * Class level is also the semantically right granularity here: it covers every
 * pure builder declared on this scope, so a future `PURE_BUILDER` added to this
 * class inherits the gate without anyone having to remember to annotate it. The
 * class is annotated rather than the file because the file also declares the
 * top-step scope, whose functions are a different concern.
 *
 * The annotation constrains the FATE OF A RETURNED CARRIER, not effects: the
 * `PURE_BUILDER`s here still emit no step and no event. Functions returning
 * `Unit` are unaffected, as are fail-closed stubs returning `Nothing` (a
 * `Nothing` result is never discarded — it never returns).
 *
 * SCOPE HONESTY: this annotation is inherited by the whole scope hierarchy
 * (`StageScope` -> `StageScopeTopSteps` -> this), which also covers
 * `SCRIPTED_RUNTIME_CALL` builders such as `pwd(..)` and `isUnix()` declared in
 * `StageScope`. Those are NOT MUST_CONSUME: each call already emits a step, so
 * discarding the returned value loses nothing. The manifest records them as
 * MAY_DISCARD, and the compile-time checker cannot be scoped per-builder — it is
 * all-or-nothing per compilation, so it stays silent for them.
 *
 * The real, enforced gate is the admission-time carrier registry below, which
 * classifies each carrier individually and therefore CANNOT be over-applied.
 * The annotation is retained as a free first line of defence that starts
 * biting the day the scripting host honours `-Xreturn-value-checker=check`.
 */
@MustUseReturnValues
open class StageScopeCore(
    protected val stageName: String,
    protected val runtimeConfig: RuntimeConfig,
) {
    protected val steps = mutableListOf<StepSpec>()

    // ------------------------------------------------------------------
    // S0-C1 (Pure Builder Consumption Gate) — admission-time enforcement
    // ------------------------------------------------------------------
    //
    // WHY THIS EXISTS NEXT TO `@MustUseReturnValues`
    // ================================================
    // The annotation is the FIRST line of defence and it is not sufficient
    // on its own. OBSERVED: `compilerOptions`/`-Xreturn-value-checker=check`
    // is referenced by exactly one class in the Kotlin 2.4.10 distribution,
    // `org.jetbrains.kotlin.scripting.definitions.ScriptDefinition` in
    // `kotlin-scripting-compiler-impl-embeddable`, and by nothing in
    // `kotlin-scripting-jvm` or `kotlin-scripting-jvm-host`. The scripting
    // host in use therefore never applies the flag: a discarded
    // `@MustUseReturnValues` result still compiles and the run still
    // succeeds with an empty stage. The annotation is kept because it
    // costs nothing and starts working the day the host does; this
    // registry is what makes the gate REAL today.
    //
    // The rule is expressed as DATA, not as a per-builder name check:
    // a PURE_BUILDER registers the carrier it hands out, and a consumer
    // marks that exact carrier as consumed. Anything registered and never
    // marked is rejected. Adding a new MUST_CONSUME builder requires no
    // change here, and no builder is named in this file.
    private val unconsumedCarriers = LinkedHashMap<Any, String>()

    /**
     * Records that [carrier] was produced by a pure builder and must be
     * consumed by the end of the stage.
     */
    protected fun <T : Any> registerMustConsume(carrier: T, builderName: String): T {
        unconsumedCarriers[carrier] = builderName
        return carrier
    }

    /**
     * Marks [carrier] as consumed. A carrier is marked by IDENTITY, not by
     * "some builder was called": `checkout(scmGit(..).scm)` reads a
     * PROPERTY of the carrier, so the carrier object itself is what has to
     * be marked, not the `scm` value inside it.
     */
    protected fun markCarrierConsumed(carrier: Any) {
        unconsumedCarriers.remove(carrier)
    }

    /**
     * Fails closed when a MUST_CONSUME carrier was produced and discarded.
     * Called at stage construction, which is before any step, event or
     * process is admitted — so the gate cannot produce a partial effect.
     */
    internal fun rejectUnconsumedCarriers() {
        if (unconsumedCarriers.isEmpty()) return
        val detail = unconsumedCarriers.entries.joinToString(", ") { (carrier, name) ->
            "$name returned a value that was never used (discarded); its return value must be used, " +
                "e.g. by passing it to a consuming builder, or the builder must be called in statement position " +
                "for a builder that declares MAY_DISCARD"
        }
        error("Pure builder consumption gate: $detail")
    }

    protected fun nestedScope(): StageScope = StageScope(stageName, runtimeConfig)

    fun echo(text: String) {
        steps.add(StepSpec.Echo(text))
    }

    fun sh(command: String) {
        steps.add(StepSpec.Shell(command))
    }

    fun sh(script: String, isScriptBlock: Boolean = false, returnStdout: Boolean = false) {
        steps.add(
            StepSpec.Shell(
                command = script,
                isScriptBlock = isScriptBlock,
                returnStdout = returnStdout,
            ),
        )
    }

    fun error(message: String, failureKind: String = "UNKNOWN") {
        steps.add(StepSpec.Error(message, failureKind))
    }

    fun sleep(seconds: Long) {
        steps.add(StepSpec.Sleep(seconds))
    }

    fun checkout(scm: Scm) {
        steps.add(StepSpec.Checkout(scm))
    }

    /**
     * S0-C1 consuming overload: takes the whole [CheckoutSpec] so the carrier
     * itself can be marked consumed, not just the `scm` inside it.
     *
     * The existing `checkout(scm: Scm)` stays because it is part of the public
     * surface and a caller may legitimately own a bare [Scm] with no carrier to
     * track. Keeping both is why the gate needs no migration of existing
     * scripts.
     */
    fun checkout(spec: CheckoutSpec) {
        markCarrierConsumed(spec)
        checkout(spec.scm)
    }
    /**
     * PURE_CONSTRUCTOR (Semantic Conservation Law): builds the [CheckoutSpec] and
     * emits nothing.
     *
     * It used to `steps.add(StepSpec.Checkout(spec.scm))` as well, which made
     * `git(..)` — defined as `checkout(scmGit(..).scm)` — emit TWO `Checkout`
     * steps for one checkout. OBSERVED before the fix: a single `git(..)` call
     * produced two identical `Checkout` rows, and three calls produced six.
     *
     * A side effect here is invisible to the `non-canonical plugins` bridge gate
     * because every emitted step is canonical, which is how the duplicate
     * survived an audit that recorded the surface as gated.
     *
     * S0-C1 (Pure Builder Consumption Gate): purity constrains EFFECTS, not the
     * fate of the carrier. `[MustUseReturnValues]` makes discarding the
     * `[CheckoutSpec]` a compile error inside `.pipeline.kts` (the scripting host
     * enables `-Xreturn-value-checker=check`). OBSERVED before the gate: a
     * statement-position `scmGit(..)` compiled and produced a `success` run with
     * an empty stage and zero checkouts — the author asked for a checkout and
     * silently got nothing. The annotation does not change purity: this still
     * returns a value and still emits no step and no event.
     */
    fun scmGit(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
        relativeTargetDir: String = ".",
    ): CheckoutSpec {
        require(url.isNotBlank()) { "Missing required parameter: url" }
        // S0-C1: the carrier is registered as MUST_CONSUME. `checkout(..)` marks
        // it consumed. Nothing else in this file names a builder, so a future
        // PURE_BUILDER inherits the gate by calling this one method.
        return registerMustConsume(
            CheckoutSpec(GitScm(url, branch, credentialsId, changelog, poll, relativeTargetDir)),
            "scmGit",
        )
    }

    fun git(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
    ) {
        // `git(..)` is the consuming shape: it builds the carrier AND routes it
        // to `checkout`, so the carrier is marked consumed here. Reading
        // `..scm` is the consumption, not a separate statement.
        checkout(scmGit(url, branch, credentialsId, changelog, poll, "."))
    }

    fun environment(block: EnvironmentScope.() -> Unit) {
        val scope = EnvironmentScope()
        scope.block()
        stageEnvironment = EnvironmentSpec(scope.build())
    }

    protected var stageEnvironment: EnvironmentSpec? = null

    fun options(block: OptionsScope.() -> Unit) {
        val scope = OptionsScope()
        scope.block()
        stageOptions = scope.build()
    }

    protected var stageOptions: OptionsSpec? = null

    fun post(block: PostScope.() -> Unit) {
        val scope = PostScope()
        scope.block()
        stagePost = scope.build()
    }

    protected var stagePost: PostConditionSpec? = null

    fun parallel(block: ParallelScope.() -> Unit) {
        val scope = ParallelScope()
        scope.block()
        steps.add(StepSpec.Parallel(scope.build().map { StepSpec.BranchSpec(it.name, it.steps) }))
    }

    fun withCredentials(binding: StepSpec.CredentialsBinding, block: StageScope.() -> Unit) {
        withCredentials(listOf(binding), block)
    }

    fun withCredentials(
        vararg bindings: StepSpec.CredentialsBinding,
        block: StageScope.() -> Unit,
    ) {
        withCredentials(bindings.toList(), block)
    }

    fun withCredentials(bindings: List<StepSpec.CredentialsBinding>, block: StageScope.() -> Unit) {
        val innerScope = nestedScope()
        innerScope.block()
        val primaryId = bindings.firstOrNull()?.credentialsId ?: CredentialsId("")
        val purpose = bindings.firstOrNull()?.variable
            ?: bindings.firstOrNull()?.usernameVariable
            ?: ""
        steps.add(
            StepSpec.WithCredentialsBlock(
                credentialsId = primaryId,
                purpose = purpose,
                bindings = bindings,
                steps = innerScope.steps(),
            ),
        )
    }

    fun environment(credentialsId: String, variable: String, block: StageScope.() -> Unit) {
        withCredentials(listOf(StepSpec.CredentialsBinding.string(credentialsId, variable)), block)
    }

    /**
     * REMOVED (S0 Semantic Honesty Gate / Semantic Conservation Law).
     *
     * The step-retrofit overload projected a full
     * [dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy] (maxAttempts +
     * baseMs + jitterMs) onto the preceding step, but NO runtime consumer ever
     * read that policy: the canonical coordinator projects only `maxAttempts`
     * from the `core.retry` block-step payload, and baseMs/jitterMs were never
     * executed by any code path. Declaring a delay was a silent lie, and a
     * policy nobody reads is metadata-without-an-interpreter.
     *
     * The supported surface is the block form `retry(n) { ... }`, which lowers
     * to a `core.retry` Block Step and is honoured by the coordinator.
     *
     * @throws IllegalArgumentException always.
     */
    @Suppress("UnusedParameter") // parameters retained so a rejected call fails
    // with THIS diagnostic instead of a bare Kotlin signature error.
    fun retry(count: Int, delaySeconds: Long? = null): Nothing = throw IllegalArgumentException(
        "retry(count = $count) at step level was removed: the projected retry policy had no " +
            "runtime consumer (the compiled path reads only the maxAttempts of the core.retry " +
            "block step; delaySeconds was never executed). Use the block form retry($count) { ... }.",
    )

    /**
     * Conditional execution, Jenkins `when { }` style.
     *
     * NOT SUPPORTED. The IR has no conditional step and no field to carry the
     * expression, so a `when` block can only ever be flattened into the stage
     * unconditionally. Observed on the installed distribution: a body guarded by
     * `whenCondition("1 == 2")` still ran and the run reported success, so the
     * predicate was silently discarded while the script believed it was gating
     * execution. Appending the body would be a fake fallback, so the call is
     * rejected instead (same law as `post { }` in [StageScope.toStageBuilder]
     * and `retry(conditions)`).
     *
     * @throws IllegalArgumentException always. Remove the `whenCondition` wrapper
     *   and express the gating outside the stage, or implement conditional steps.
     */
    @Suppress("UnusedParameter") // `block` is retained so a rejected call still fails with THIS
    // diagnostic instead of a bare Kotlin signature error; the body is never invoked.
    fun whenCondition(expression: String, block: StageScope.() -> Unit) {
        throw IllegalArgumentException(
            "whenCondition(\"$expression\") is not supported: the compiled execution path has no " +
                "conditional step, so the expression cannot be carried into the IR and the block would " +
                "run unconditionally. Declaring a predicate that is then ignored is a silent lie; " +
                "remove the whenCondition wrapper instead.",
        )
    }

    /**
     * REMOVED (S0 Semantic Honesty Gate / Semantic Conservation Law).
     *
     * `agent(label)` used to project an [AgentSpec] into `CompiledPipeline.agent` and
     * `StageNode.agent`, but NO runtime component ever read it: there is no agent
     * distributor or scheduler, and the label never reached any event. Metadata
     * without an interpreter is a silent lie, so the DSL refuses the call instead of
     * storing it. Reinstate only together with a real interpreter that consumes the
     * label end-to-end.
     *
     * @throws IllegalArgumentException always.
     */
    @Suppress("UnusedParameter") // parameters retained so a rejected call fails
    // with THIS diagnostic instead of a bare Kotlin signature error.
    fun agent(label: String, remoteUri: String? = null): Nothing = throw IllegalArgumentException(
        "agent(\"$label\") is not supported: the compiled definition used to store the label but " +
            "no runtime component ever read it (no distributor, no scheduler; it never reached an " +
            "event). Metadata without an interpreter is a silent lie; remove the agent() call.",
    )

    fun script(block: ScriptScope.() -> Unit) {
        val scope = ScriptScope()
        scope.block()
        val scriptContent = scope.commands.joinToString("\n")
        if (scriptContent.isNotEmpty()) {
            steps.add(StepSpec.Shell(scriptContent, isScriptBlock = true))
        }
    }

    fun steps(): List<StepSpec> = steps.toList()

}

/** Jenkins top-step builders kept separate from the core stage DSL. */
open class StageScopeTopSteps(
    stageName: String,
    runtimeConfig: RuntimeConfig,
) : StageScopeCore(stageName, runtimeConfig) {
    fun writeFile(file: String, text: String, encoding: String = "UTF-8") {
        steps.add(StepSpec.WriteFile(file = file, text = text, encoding = encoding))
    }

    fun registryStep(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        schemaVersion: String = "dsl-v1",
    ) {
        steps.add(StepSpec.RegistryStepSpec(stepKey, schemaVersion, encodedInput))
    }

    fun registryBlock(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        schemaVersion: String = "dsl-v1",
        block: StageScope.() -> Unit,
    ) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.RegistryBlockSpec(stepKey, schemaVersion, encodedInput, inner.steps()))
    }

    fun readFile(file: String, encoding: String = "UTF-8") {
        steps.add(StepSpec.ReadFile(file = file, encoding = encoding))
    }

    fun fileExists(file: String) {
        steps.add(StepSpec.FileExists(file = file))
    }

    fun withEnv(overrides: List<String>, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.WithEnv(overrides = overrides, steps = inner.steps()))
    }

    fun withEnv(overrides: Array<String>, block: StageScope.() -> Unit) {
        withEnv(overrides.toList(), block)
    }

    fun withEnv(overrides: Map<String, String>, block: StageScope.() -> Unit) {
        withEnv(overrides.map { "${it.key}=${it.value}" }, block)
    }

    fun archiveArtifacts(
        artifacts: String,
        allowEmptyArchive: Boolean = false,
        excludes: String = "",
        fingerprint: Boolean = false,
        name: String? = null,
    ) {
        steps.add(
            StepSpec.ArchiveArtifacts(
                artifacts = artifacts,
                allowEmptyArchive = allowEmptyArchive,
                excludes = excludes,
                fingerprint = fingerprint,
                artifactName = name,
            ),
        )
    }

    fun artifactQuery(name: String) {
        val encoded = "{\"kind\":\"artifactQuery\",\"name\":\"${escapeJsonString(name)}\"}"
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.artifact.query"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(encoded),
            ),
        )
    }

    fun dir(path: String, block: StageScope.() -> Unit) {
        val inner = nestedScope()
        inner.block()
        steps.add(StepSpec.Dir(path = path, steps = inner.steps()))
    }

    protected fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
