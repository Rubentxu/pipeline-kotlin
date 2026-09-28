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

    fun scmGit(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
        relativeTargetDir: String = ".",
    ): CheckoutSpec {
        require(url.isNotBlank()) { "Missing required parameter: url" }
        val spec = CheckoutSpec(GitScm(url, branch, credentialsId, changelog, poll, relativeTargetDir))
        steps.add(StepSpec.Checkout(spec.scm))
        return spec
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

    fun retry(count: Int, delaySeconds: Long? = null) {
        val currentStep = steps.lastOrNull() ?: return
        val retryPolicy = RuntimeRetryPolicy(
            maxAttempts = count,
            baseMs = (delaySeconds ?: 0L) * 1000L,
            jitterMs = (delaySeconds ?: 0L) * 500L,
        )
        val index = steps.indexOf(currentStep)
        steps[index] = if (currentStep.supportsStepLevelRetry) {
            currentStep.withRetry(retryPolicy)
        } else {
            currentStep
        }
    }

    fun whenCondition(expression: String, block: StageScope.() -> Unit) {
        WhenCondition(expression)
        val tempScope = nestedScope()
        tempScope.block()
        steps.addAll(tempScope.steps())
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
