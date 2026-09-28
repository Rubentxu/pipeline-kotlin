package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm

/** Shared mutable state for the stage DSL builders. */
open class StageScopeCore(
    protected val stageName: String,
    protected val runtimeConfig: RuntimeConfig,
) {
    protected val steps = mutableListOf<StepSpec>()

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
        return CheckoutSpec(GitScm(url, branch, credentialsId, changelog, poll, relativeTargetDir))
    }

    fun git(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
    ) {
        checkout(scmGit(url, branch, credentialsId, changelog, poll, ".").scm)
    }

    fun agent(label: String, remoteUri: String? = null) {
        stageAgent = AgentSpec(label, remoteUri)
    }

    protected var stageAgent: AgentSpec? = null

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
     * Attaches a retry policy to the step that was just declared.
     *
     * FAIL-CLOSED for the two cases that used to be silent (Semantic
     * Conservation Law, TRAIN-DSL-HONESTY). This overload was
     * `MUTATE_IF_POSSIBLE_ELSE_IGNORE`:
     *
     * ```kotlin
     * val currentStep = steps.lastOrNull() ?: return   // nothing declared -> nothing happened
     * steps[index] = if (currentStep.supportsStepLevelRetry) currentStep.withRetry(policy)
     *                else currentStep                  // policy silently dropped
     * ```
     *
     * Both paths left a valid-looking [StageScope] and a green build, so
     * `StepSpecRetryCapabilityTest` ended up *certifying* the silence with cases
     * named "retry before any step is a no-op" and "a non-retryable step is left
     * untouched by retry". Those two cases now assert the rejection instead.
     *
     * Prefer the block form for anything that cannot carry a policy:
     * `retry(n) { sh("./flaky") }`.
     *
     * @throws IllegalArgumentException when there is no preceding step, or when
     *   that step cannot carry a step-level retry policy.
     */
    fun retry(count: Int, delaySeconds: Long? = null) {
        val currentStep = steps.lastOrNull()
        require(currentStep != null) {
            "retry(count = $count) retrofits the step declared immediately before it, but no step " +
                "has been declared yet. Use the block form retry($count) { ... } to wrap the steps " +
                "that should be retried."
        }
        require(count > 0) { "retry(count) requires count > 0, got $count" }
        require(currentStep.supportsStepLevelRetry) {
            "${currentStep::class.simpleName} does not support step-level retry, so retry(count = $count) " +
                "cannot be applied to it. Use the block form retry($count) { ... } around the step instead."
        }
        val retryPolicy = RuntimeRetryPolicy(
            maxAttempts = count,
            baseMs = (delaySeconds ?: 0L) * 1000L,
            jitterMs = (delaySeconds ?: 0L) * 500L,
        )
        val index = steps.indexOf(currentStep)
        steps[index] = currentStep.withRetry(retryPolicy)
    }

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

private typealias RuntimeRetryPolicy = dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy
