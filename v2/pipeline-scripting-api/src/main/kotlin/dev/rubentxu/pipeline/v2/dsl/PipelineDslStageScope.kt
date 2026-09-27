package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm

/**
 * Receiver scope for the step block inside `stage("name") { }`.
 */
@StepDslMarker
class StageScope(
    private val stageName: String,
    private val runtimeConfig: dev.rubentxu.pipeline.v2.domain.RuntimeConfig =
        currentRuntimeConfig(),
) {
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
     * @param isScriptBlock Whether the command is the body of a script block.
     * @param returnStdout If true, capture stdout to output.txt for return value access.
     */
    fun sh(
        script: String,
        isScriptBlock: Boolean = false,
        returnStdout: Boolean = false,
    ) {
        steps.add(
            StepSpec.Shell(
                command = script,
                isScriptBlock = isScriptBlock,
                returnStdout = returnStdout,
            ),
        )
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
     * Git checkout using an existing [CheckoutSpec].
     *
     * @param scm The SCM specification (e.g., created via [scmGit])
     */
    fun checkout(scm: Scm) {
        steps.add(StepSpec.Checkout(scm))
    }

    /**
     * Git checkout with explicit SCM parameters.
     *
     * Jenkins parity: 6-param dominant constructor.
     *
     * @param url Repository URL (https or file)
     * @param branch Branch to checkout (default master)
     * @param credentialsId Optional credentials ID for private repos
     * @param changelog Whether to append to changelog.txt (default true)
     * @param poll Whether to poll for changes (default true) — synchronous ls-remote
     * @param relativeTargetDir Workspace-relative checkout directory (default ".")
     */
    fun scmGit(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
        relativeTargetDir: String = ".",
    ): CheckoutSpec {
        // C6: Validate URL non-blank (Jenkins-verbatim error)
        if (url.isBlank()) {
            throw IllegalArgumentException("Missing required parameter: url")
        }
        val spec = CheckoutSpec(GitScm(url, branch, credentialsId, changelog, poll, relativeTargetDir))
        steps.add(StepSpec.Checkout(spec.scm))
        return spec
    }

    /**
     * Git checkout shorthand (5-param).
     *
     * Desugars to `checkout(scmGit(url, branch, credentialsId, changelog, poll, "."))`.
     *
     * @param url Repository URL
     * @param branch Branch (default master)
     * @param credentialsId Optional credentials ID
     * @param changelog Whether to append changelog (default true)
     * @param poll Whether to poll (default true)
     */
    fun git(
        url: String,
        branch: String = "master",
        credentialsId: CredentialsId? = null,
        changelog: Boolean = true,
        poll: Boolean = true,
    ) {
        val spec = scmGit(url, branch, credentialsId, changelog, poll, ".")
        checkout(spec.scm)
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
    /**
     * WU-LPR-071: single-binding overload restored (Jenkins supports both shapes).
     * Desugars to the List form. Required by the withCredentials compile
     * integration tests (IT-001..IT-006) which pass a single binding directly.
     */
    fun withCredentials(binding: StepSpec.CredentialsBinding, block: StageScope.() -> Unit) {
        withCredentials(listOf(binding), block)
    }

    /** WU-LPR-071: vararg form (Jenkins `withCredentials(a, b) { }`). */
    fun withCredentials(
        vararg bindings: StepSpec.CredentialsBinding,
        block: StageScope.() -> Unit,
    ) {
        withCredentials(bindings.toList(), block)
    }

    fun withCredentials(bindings: List<StepSpec.CredentialsBinding>, block: StageScope.() -> Unit) {
        val innerScope = StageScope(stageName, runtimeConfig)
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
     * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
     * @param variable The environment variable name to inject
     * @param block The steps to execute with the credential bound
     */
    fun environment(credentialsId: String, variable: String, block: StageScope.() -> Unit) {
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
            is StepSpec.RegistryStepSpec -> currentStep.copy(retry = retryPolicy)
            is StepSpec.RegistryBlockSpec -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Error -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Sleep -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Parallel -> currentStep.copy(retry = retryPolicy)
            is StepSpec.WithCredentialsBlock -> currentStep.copy(retry = retryPolicy)
            is StepSpec.Checkout -> currentStep.copy(retry = retryPolicy)
            // L7 Jenkins top-steps (ML-R7): retry/timeout not supported per Jenkins catalog
            // These steps use stage-level retry via options { retry(count) }
            is StepSpec.WriteFile -> currentStep
            is StepSpec.ReadFile -> currentStep
            is StepSpec.FileExists -> currentStep
            is StepSpec.WithEnv -> currentStep
            is StepSpec.ArchiveArtifacts -> currentStep
            // ML-R9 workflow-control: step-level retry not supported
            is StepSpec.Dir -> currentStep
            // ML-R9 workspace-cleanup: not retryable at step level
            is StepSpec.DeleteDir -> currentStep
            is StepSpec.CleanWs -> currentStep
            // ML-R9 error-handling: not retryable at step level
            is StepSpec.CatchError -> currentStep
            is StepSpec.WarnError -> currentStep
            is StepSpec.Unstable -> currentStep
            // ML-R9 workflow-utility: not retryable at step level
            is StepSpec.Pwd -> currentStep
            is StepSpec.IsUnix -> currentStep
            is StepSpec.Load -> currentStep
            is StepSpec.WaitUntilBlock -> currentStep
            // ML-R9 T-08 output-decorators: not retryable at step level
            is StepSpec.Timestamps -> currentStep
            is StepSpec.AnsiColor -> currentStep
            is StepSpec.NodeNoOp -> currentStep
            // ML-R9 T-09 milestone: not retryable at step level
            is StepSpec.Milestone -> currentStep
            // ML-R9 T-10 timeout/retry blocks: not retryable at step level
            is StepSpec.TimeoutBlock -> currentStep
            is StepSpec.RetryBlock -> currentStep
            // E1.1 / T7: artifactQuery is read-only — not retryable at step level
            is StepSpec.ArtifactQuery -> currentStep
        }
    }

    /**
     * Conditional execution using a when clause.
     */
    fun whenCondition(expression: String, block: StageScope.() -> Unit) {
        val condition = WhenCondition(expression)
        val tempScope = StageScope(stageName, runtimeConfig)
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

    /**
     * Returns the steps added to this scope (for testing / DSL inspection).
     */
    fun steps(): List<StepSpec> = steps.toList()

    // =============================================================================
    // L7 Jenkins top-steps builders (ML-R7) — ADR-0046 §D2 verbatim DSL
    // =============================================================================

    /**
     * Writes content to a workspace file.
     *
     * Jenkins verbatim: `writeFile(file: String, text: String, encoding: String = "UTF-8")`
     *
     * @param file Workspace-relative file path
     * @param text Content to write
     * @param encoding Character encoding (default UTF-8; "Base64" decodes binary)
     */
    fun writeFile(file: String, text: String, encoding: String = "UTF-8") {
        steps.add(StepSpec.WriteFile(file = file, text = text, encoding = encoding))
    }

    /**
     * Low-level generic primitive for a Step hosted in the open StepRegistry (LB-02 / EP-F2).
     *
     * This is infrastructure: it carries the StepKey, the schema/input-contract version, and the
     * already-encoded input. It does NOT decode, resolve, or execute anything. Plugins SHOULD wrap it
     * in their own typed Kotlin DSL façade (e.g. `uppercase(text)`) so end users never write this
     * directly. Same `steps { }` scope and constraints as every normal Step.
     *
     * @param stepKey the open-registry StepKey (never interpreted by the compiler).
     * @param schemaVersion the CANONICAL ENVELOPE schema (must stay `dsl-v1`; EP-F2.6 conflation
     *   fix — the plugin's own input-contract version is codec-level, never the envelope version).
     * @param encodedInput the plugin-encoded input (produced by the plugin's own `inputCodec`).
     */
    fun registryStep(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        schemaVersion: String = "dsl-v1",
    ) {
        steps.add(StepSpec.RegistryStepSpec(stepKey = stepKey, schemaVersion = schemaVersion, encodedInput = encodedInput))
    }

    /**
     * Low-level generic primitive for a body-owning (Block) Step hosted in the
     * open StepRegistry (WU-RP-033). Mirror of [registryStep] for Steps whose
     * descriptor declares a body (`StepBody.Declared`). Plugins SHOULD wrap it
     * in their own typed Kotlin DSL facade; same `steps { }` scope and
     * constraints as every normal Block Step.
     *
     * The body is captured declaratively from [block] (data construction only)
     * and lowered recursively by the compiler. Runtime admission resolves the
     * key's declared [BodyExecutionPolicy] from the open registry and rejects
     * fail-closed (unknown key, not a body Step, incoherent or unsupported
     * declaration) BEFORE any child runs.
     */
    fun registryBlock(
        stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        schemaVersion: String = "dsl-v1",
        block: StageScope.() -> Unit,
    ) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(
            StepSpec.RegistryBlockSpec(
                stepKey = stepKey,
                schemaVersion = schemaVersion,
                encodedInput = encodedInput,
                body = inner.steps.toList(),
            ),
        )
    }

    /**
     * Reads content from a workspace file.
     *
     * Jenkins verbatim: `readFile(file: String, encoding: String = "UTF-8")`
     *
     * NOTE: No return value — consumers use `sh(returnStdout=true)` for runtime values.
     * Documented limitation per D2; addressed in ML-R8 follow-up.
     *
     * @param file Workspace-relative file path
     * @param encoding Character encoding (default UTF-8)
     */
    fun readFile(file: String, encoding: String = "UTF-8") {
        steps.add(StepSpec.ReadFile(file = file, encoding = encoding))
    }

    /**
     * Checks whether a file exists in the workspace.
     *
     * Jenkins verbatim: `fileExists(file: String)` — returns Boolean.
     *
     * NOTE: No return value — consumers use `sh(returnStdout=true)` for runtime values.
     * Documented limitation per D2; addressed in ML-R8 follow-up.
     *
     * @param file Workspace-relative file path
     */
    fun fileExists(file: String) {
        steps.add(StepSpec.FileExists(file = file))
    }

    /**
     * Sets environment variables for the duration of the nested block.
     *
     * Jenkins verbatim: `withEnv(overrides: List<String>)` (catalog §1.1 line 40).
     * Each entry is `"VAR=value"` or `"PATH+X=/dir"` (PATH prepend per catalog §3).
     *
     * Mirrors [withCredentials] pattern — creates a nested [StepSpec.WithEnv] block
     * carrier that the dispatcher folds into the env model.
     *
     * @param overrides Environment overrides (each `"VAR=value"` or `"PATH+X=/dir"`)
     * @param block Nested steps executed with the overridden environment
     */
    fun withEnv(overrides: List<String>, block: StageScope.() -> Unit) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.WithEnv(overrides = overrides, steps = inner.steps.toList()))
    }

    /**
     * Sets environment variables using an array (Jenkins-faithful overload).
     *
     * @param overrides Environment overrides as an array of strings (each `"VAR=value"` or `"PATH+X=/dir"`)
     * @param block Nested steps executed with the overridden environment
     */
    fun withEnv(overrides: Array<String>, block: StageScope.() -> Unit) {
        withEnv(overrides.toList(), block)
    }

    /**
     * Sets environment variables using a map (convenience overload).
     *
     * @param overrides Environment overrides as Map (converted to `"VAR=value"` strings)
     * @param block Nested steps executed with the overridden environment
     */
    fun withEnv(overrides: Map<String, String>, block: StageScope.() -> Unit) {
        withEnv(overrides.map { "${it.key}=${it.value}" }, block)
    }

    /**
     * Archives artifacts for server-side retention.
     *
     * Jenkins verbatim (catalog §1.1 line 45):
     * `archiveArtifacts(artifacts: String, allowEmptyArchive: Boolean = false,
     *                  excludes: String = "", fingerprint: Boolean = false)`
     *
     * F1: artifacts required. F2: allowEmptyArchive, excludes, fingerprint.
     * F3 (E1.ecosystem-local-first): an optional `name` parameter, when
     * non-null, records the archived handle into the run-scoped
     * [dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability]
     * under that name so a subsequent [artifactQuery] call resolves it.
     * The name is optional; when null, the legacy archive behaviour is
     * preserved verbatim (no index consultation).
     *
     * @param artifacts Ant-style glob patterns (comma-separated)
     * @param allowEmptyArchive If true, empty archive is not a failure (default false)
     * @param excludes Ant-style patterns to exclude from archive
     * @param fingerprint If true, record fingerprints (F2)
     * @param name Optional logical artifact name (E1.1 / T1). When non-null,
     *   the archived handle is recorded in the per-run artifact index under
     *   this name; subsequent [artifactQuery] calls resolve this name to the
     *   archived file set. Backward-compat: when null, the legacy archive-only
     *   behaviour is preserved (no index interaction).
     */
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
            )
        )
    }

    /**
     * Looks up an artifact previously archived with `archiveArtifacts(name = ...)`.
     *
     * E1.1 (ML-R9 local-first ecosystem): the bridge from `core.archiveArtifacts`
     * to a queryable, name-addressable artifact registry. This DSL lowers
     * directly to `StepSpec.RegistryStepSpec` for `core.artifact.query` with the
     * canonical encoded envelope `{"kind":"artifactQuery","name":"<name>"}` —
     * byte-for-byte identical to `CoreArtifactQueryStep.inputCodec.encode()` so
     * the durable fingerprint round-trips through the G5 registry path.
     *
     * Bridge invariant: name must match the one supplied at `archiveArtifacts(name = ...)`.
     * Mismatch is a typed USER failure (`ArtifactQueryFailure.NotFound`) at handler time —
     * not a DSL validation, since names are runtime values (the dynamic part).
     *
     * @param name Artifact logical name (matches `archiveArtifacts(name = "…")`)
     */
    fun artifactQuery(name: String) {
        // Canonical envelope: {"kind":"artifactQuery","name":"<escapeJsonString(name)>"}
        // Matches CoreArtifactQueryStep.inputCodec.encode output (E1.1 / T7).
        val sb = StringBuilder()
        sb.append("{\"kind\":\"artifactQuery\",\"name\":\"")
        sb.append(escapeJsonString(name))
        sb.append("\"}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.artifact.query"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    /**
     * Changes the current working directory for the duration of the nested block.
     *
     * Jenkins verbatim (catalog §1.1/§1.2):
     * `dir(path: String) { ... }`
     *
     * Changes the working directory (cwd) for nested steps. The previous working
     * directory is restored when the block exits (including on exception).
     *
     * @param path Workspace-relative or absolute directory path
     * @param block Nested steps executed in the changed directory
     */
    fun dir(path: String, block: StageScope.() -> Unit) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.Dir(path = path, steps = inner.steps.toList()))
    }

    // =============================================================================
    // ML-R9 workspace-cleanup DSL (T-05)
    // =============================================================================

    /**
     * Deletes the specified directory within the workspace.
     *
     * Jenkins verbatim (catalog §1.1 line 44):
     * `deleteDir()`
     *
     * Idempotent: re-execution on already-deleted path succeeds with deletedCount=0.
     *
     * @param path Workspace-relative path (default ".")
     */
    fun deleteDir(path: String = ".") {
        steps.add(StepSpec.DeleteDir(path = path))
    }

    /**
     * Cleans the workspace with optional Ant-style glob filtering.
     *
     * Jenkins verbatim (catalog §1.1 line 44):
     * `cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null)`
     *
     * S2-A10 / G5 (2026-09-13): this DSL lowers directly to `StepSpec.RegistryStepSpec`
     * (open-world registry path). The payload is encoded inline here to match the canonical
     * codec of `CoreCleanWsStep.inputCodec` byte-for-byte, so the durable fingerprint is
     * preserved across the G5 destructive flip. The legacy `StepSpec.CleanWs` subtype still
     * exists as a sealed-interface member because `CleanWsExecutor` (SDK files) types its
     * parameter against it; this DSL was the only producer that routed through the legacy
     * decoder, and that producer is gone.
     *
     * @param deleteDirs If true, remove empty parent directories after deletion
     * @param patterns Ant-style glob patterns (null = delete all non-.v2 files)
     */
    fun cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null) {
        // Canonical envelope: {"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}
        // Matches CoreCleanWsStep.inputCodec.encode output (S2-A10 / G5).
        val canonicalPatterns: List<String> = patterns ?: emptyList()
        val sb = StringBuilder()
        sb.append("{\"kind\":\"cleanWs\",\"deleteDirs\":").append(deleteDirs).append(",\"patterns\":[")
        canonicalPatterns.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append('"').append(escapeJsonString(p)).append('"')
        }
        sb.append("]}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.cleanWs"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    /**
     * Cleans the workspace with array syntax (Jenkins-faithful overload).
     *
     * S2-A10 / G5 (2026-09-13): same RegistryStepSpec lowering as the primary overload.
     *
     * @param deleteDirs If true, remove empty parent directories after deletion
     * @param patterns Ant-style glob patterns as vararg
     */
    fun cleanWs(deleteDirs: Boolean = true, vararg patterns: String) {
        cleanWs(deleteDirs = deleteDirs, patterns = patterns.toList())
    }

    // =============================================================================
    // ML-R9 error-handling DSL (T-06)
    // =============================================================================

    /**
     * Catches errors from nested steps and optionally downgrades the build result.
     *
     * Jenkins verbatim (catalog §1.1 lines 41-43):
     * `catchError(buildResult: String? = null, stageResult: String? = null, message: String? = null) { ... }`
     *
     * @param buildResult Override build result (null = default Jenkins UNSTABLE)
     * @param stageResult Override stage result (null = use buildResult or default UNSTABLE)
     * @param message User-visible message
     * @param block Nested steps
     */
    @Deprecated(
        message = "LFC1-007: catchError is pre-compiler-rewritten. Use try/catch at the orchestrator level instead.",
        replaceWith = ReplaceWith("catchError(buildResult, stageResult, message, block)"),
    )
    fun catchError(
        buildResult: String? = null,
        stageResult: String? = null,
        message: String? = null,
        block: StageScope.() -> Unit,
    ) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.CatchError(
            buildResult = buildResult,
            stageResult = stageResult,
            message = message,
            steps = inner.steps.toList(),
        ))
    }

    /**
     * Catches errors and forces stage result to UNSTABLE (warnError semantics).
     *
     * Jenkins verbatim:
     * `warnError(message: String, catchInterruptions: Boolean = true) { ... }`
     *
     * @param message User-visible warning message
     * @param catchInterruptions If true, also catch Thread.interrupt()
     * @param block Nested steps
     */
    @Deprecated(
        message = "LFC1-007: warnError is pre-compiler-rewritten. Use try/catch at the orchestrator level instead.",
        replaceWith = ReplaceWith("warnError(message, catchInterruptions, block)"),
    )
    fun warnError(
        message: String,
        catchInterruptions: Boolean = true,
        block: StageScope.() -> Unit,
    ) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.WarnError(
            message = message,
            catchInterruptions = catchInterruptions,
            steps = inner.steps.toList(),
        ))
    }

    /**
     * Marks the current stage as unstable (soft warning, pipeline continues).
     *
     * Jenkins verbatim:
     * `unstable(message: String)`
     *
     * @param message User-visible message describing the instability
     */
    @Deprecated(
        message = "LFC1-007: unstable is pre-compiler-rewritten. Use emitEvent('StageMarkedUnstable', ...) instead.",
        replaceWith = ReplaceWith("unstable(message)"),
    )
    fun unstable(message: String) {
        steps.add(StepSpec.Unstable(message = message))
    }

    /**
     * Prints the current working directory (workspace root).
     *
     * Jenkins verbatim:
     * `pwd()` or `pwd(tmp: Boolean)`
     *
     * **WU-LPR-402 — runtime-returning DSL fun.** This builder lowers to a
     * registry Step that produces the path as a typed runtime value at
     * execution time. It does NOT return the real path synchronously from
     * this DSL call — that would be a fake runtime value (the path belongs
     * to execution, not to IR construction).
     *
     * Supported usage:
     *  - inside a `scriptable` block / a compiled scripted runtime context,
     *    where the façade materialises the value before control returns;
     *  - inside the generator form (`.pipeline.kts` lowered to a
     *    `CompiledScriptedEntryPoint`), where `CorePwdStep` / `CorePwdTmpStep`
     *    are invoked through `ScriptedRegistryInvoker`.
     *
     * Unsupported usage (fail-closed):
     *  - reading the synchronous return value during IR construction (this
     *    method returns the placeholder `<workspace>` for backward
     *    compatibility, but the placeholder MUST NOT be used as if it were
     *    the real runtime path).
     *  - the eager `PipelineSpec` form. Scripts that need the real value
     *    MUST route through the scripted runtime context.
     *
     * `tmp=false` lowers to `core.pwd` (READ_ONLY + MEMOIZED, replay
     * reproduces the persisted path without re-observing the workspace).
     * `tmp=true` lowers to `core.pwd.tmp` (deterministic
     * `tmp-pwd-<sha256(opId)>` path; `tmp` directories persist across
     * resume and are NOT recreated on REUSE).
     *
     * @param tmp If true, the registry Step creates a deterministic temp
     *   subdirectory under the workspace root and returns its absolute path
     */
    fun pwd(tmp: Boolean = false): String {
        // WU-LPR-402 — both branches lower to the registry path. The legacy
        // StepSpec.Pwd / StepSpec.PwdTmp steps were retired at S2-A6/G5
        // (LEGACY_REMOVED) and S2-A6/G3T (deterministic tmp); the registry
        // candidates `core.pwd` and `core.pwd.tmp` are the only production
        // authorities (G8 final certification, see
        // S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md pattern).
        if (tmp) {
            val encoded = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}")
            steps.add(
                StepSpec.RegistryStepSpec(
                    stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.pwd.tmp"),
                    schemaVersion = "dsl-v1",
                    encodedInput = encoded,
                ),
            )
        } else {
            val encoded = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
                """{"kind":"pwd","tmp":false}""",
            )
            steps.add(
                StepSpec.RegistryStepSpec(
                    stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.pwd"),
                    schemaVersion = "dsl-v1",
                    encodedInput = encoded,
                ),
            )
        }
        // Honest return: this DSL fun is data construction. The placeholder
        // is preserved for in-memory scripting hosts (tests, ad-hoc harnesses)
        // that read the return value, but scripts that need the real path
        // MUST route through the scripted runtime context where
        // CorePwdStep/CorePwdTmpStep materialise the typed value.
        return RUNTIME_VALUE_PLACEHOLDER
    }

    /**
     * Checks whether the current system is Unix-like (Linux/macOS).
     *
     * Jenkins verbatim:
     * `isUnix()`
     *
     * **WU-LPR-402 — runtime-returning DSL fun.** This builder lowers to a
     * registry Step that classifies the platform at execution time. It does
     * NOT return the real classification synchronously from this DSL call —
     * that would be a fake runtime value (the classification belongs to
     * execution, not to IR construction).
     *
     * Supported usage:
     *  - inside a `scriptable` block / a compiled scripted runtime context,
     *    where `CoreIsUnixStep` materialises the Boolean through
     *    `ScriptedRegistryInvoker`;
     *  - inside the generator form, where the Boolean reaches the script
     *    as a typed value with full REUSE replay semantics
     *    (LFC-2R_R2_ISUNIX_SCRIPTED_RUNTIME_CONSUMER.md).
     *
     * Unsupported usage (fail-closed):
     *  - reading the synchronous return value during IR construction (this
     *    method returns `RUNTIME_VALUE_PLACEHOLDER_BOOLEAN` to make misuse
     *    visible — see the WU-LPR-402 receipt for the matrix).
     *  - the eager `PipelineSpec` form for branches that depend on the
     *    real value.
     *
     * @return the placeholder sentinel; the real value is materialised by
     *   `core.isUnix` at execution time. Reading this return value as the
     *   real classification is a WU-LPR-402 contract violation.
     */
    fun isUnix(): Boolean {
        val encoded = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("{}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.isUnix"),
                schemaVersion = "dsl-v1",
                encodedInput = encoded,
            ),
        )
        return ISUNIX_PLACEHOLDER
    }

    internal companion object {
        /**
         * Placeholder returned by [pwd] when called in IR-construction context.
         * The real path is produced at execution time by `core.pwd` /
         * `core.pwd.tmp` through the scripted runtime context. Reading this
         * sentinel as if it were the real runtime path is a WU-LPR-402
         * contract violation.
         */
        const val RUNTIME_VALUE_PLACEHOLDER: String = "<workspace>"

        /**
         * Placeholder returned by [isUnix] when called in IR-construction
         * context. Reading this sentinel as if it were the real
         * classification is a WU-LPR-402 contract violation. The choice of
         * `true` (rather than `false`) preserves the previous
         * `StubRuntimeConfig` behaviour so consumers that ignore the
         * placeholder still get a non-error value; the FAIL-CLOSED behaviour
         * lives at the runtime-routing seam, not at the placeholder.
         */
        const val ISUNIX_PLACEHOLDER: Boolean = true
    }

    /**
     * Loads and executes steps from an external pipeline script file.
     *
     * Jenkins verbatim:
     * `load(path: String)`
     *
     * The path is resolved relative to the workspace root. On successful load,
     * the file is compiled and its steps are appended to the current execution scope.
     *
     * @param path Workspace-relative path to the .pipeline.kts file
     */
    fun load(path: String) {
        steps.add(StepSpec.Load(path = path))
    }

    /**
     * Polls a condition closure until it returns true or a deadline elapses.
     *
     * Jenkins verbatim:
     * `waitUntil(initialRecurrencePeriod: Long = 1, quiet: Boolean = false) { condition }`
     *
     * Body is the inner StepSpec list captured at construction time.
     *
     * **Construction-time body execution (WU-LPR-401 finding).** The body
     * lambda is invoked exactly once at DSL construction time, on the
     * `inner` StageScope, to extract its declared `List<StepSpec>` as data.
     * This means the body MUST be pure data construction (calling other
     * DSL builders like `sh("...")` or `echo("...")`); it MUST NOT perform
     * runtime effects such as `pwd().length`, `isUnix()`-driven branches
     * with side-effects, file I/O, network calls, or process execution.
     *
     * For side-effect-bearing predicates, route through the durable
     * runtime predicate contract (the canonical coordinator re-enters the
     * captured body via `BodyInvoker.invoke`, ADR-0073) — the lambda
     * captures the *shape* of the predicate, not its evaluation result.
     *
     * The captured body is structurally equal to what a pure `() -> List<StepSpec>`
     * would yield. If a future WU replaces this pattern with explicit
     * lambda capture (e.g. `body: () -> List<StepSpec>` passed by the
     * compiler after lowering), this comment and the implementation will
     * converge. Until then, callers MUST honour the "pure data
     * construction" rule above.
     *
     * Why this is not a regression: the same eager-evaluation pattern is
     * used by `retry`, `timeout`, `timestamps`, `dir`, `withCredentials`,
     * `script`, etc. WU-LPR-401 documents the pattern; it does not break
     * consistency by fixing one builder.
     *
     * @param initialRecurrencePeriod Initial poll interval in milliseconds (default 1ms)
     * @param quiet If true, suppress output during polling
     * @param body Lambda producing the nested steps whose last step emits
     *   WaitUntilPredicateEvaluated(true/false)
     */
    fun waitUntil(
        initialRecurrencePeriod: Long = 1L,
        quiet: Boolean = false,
        body: StageScope.() -> Unit,
    ) {
        // Construction-time body capture: invoke the lambda once on a
        // fresh StageScope so its declared steps land in `inner.steps`,
        // then snapshot that list as data on the structural StepSpec.
        // This is the same shape used by retry/timeout/timestamps/dir
        // (see AGENTS.md DSL-vs-runtime section + WU-LPR-401 receipt).
        val inner = StageScope(stageName, runtimeConfig)
        inner.body()
        steps.add(StepSpec.WaitUntilBlock(
            initialRecurrencePeriod = initialRecurrencePeriod,
            quiet = quiet,
            body = inner.steps.toList(),
        ))
    }

    // =============================================================================
    // ML-R9 output-decorator DSL (T-08)
    // =============================================================================

    /**
     * Decorates captured stdout/stderr with timestamps.
     *
     * Jenkins verbatim: `timestamps { block }`
     *
     * @param block Nested steps to execute with timestamp decoration
     */
    fun timestamps(block: StageScope.() -> Unit) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.Timestamps(steps = inner.steps.toList()))
    }

    /**
     * Decorates captured stdout/stderr with ANSI color codes.
     *
     * Jenkins verbatim: `ansiColor(colorMapName: String = "xterm") { block }`
     *
     * @param colorMapName Color map name (default "xterm")
     * @param block Nested steps to execute with ANSI color decoration
     */
    fun ansiColor(colorMapName: String = "xterm", block: StageScope.() -> Unit) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.AnsiColor(colorMapName = colorMapName, steps = inner.steps.toList()))
    }

    /**
     * No-op step that emits AgentResolved.
     *
     * Jenkins verbatim: `node(label?: String) { block }`
     *
     * @param label Agent label (optional)
     * @param block Nested steps to execute
     */
    fun node(label: String? = null, block: StageScope.() -> Unit) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.NodeNoOp(label = label, steps = inner.steps.toList()))
    }

    // =============================================================================
    // ML-R9 milestone DSL (T-09)
    // =============================================================================

    /**
     * Records a milestone for cross-build coordination.
     *
     * Jenkins verbatim: `milestone(ordinal: Int, label: String? = null)`
     *
     * S2-A9 / G5: this DSL lowers directly to `StepSpec.RegistryStepSpec` (open-world registry
     * path). The payload is encoded inline here to match the canonical codec of
     * `CoreMilestoneStep.inputCodec` byte-for-byte, so the durable fingerprint is preserved
     * across the G5 destructive flip. The legacy `StepSpec.Milestone` subtype and its compiler
     * branch are removed at G5; this DSL was the only producer.
     *
     * @param ordinal The milestone ordinal (must be monotonically increasing)
     * @param label Optional label for the milestone
     */
    fun milestone(ordinal: Int, label: String? = null) {
        require(ordinal > 0) { "milestone ordinal must be positive: $ordinal" }
        // Canonical envelope: {"kind":"milestone","ordinal":N,"label":...?}
        // Matches CoreMilestoneStep.inputCodec.encode output (S2-A9 / G5).
        val encoded = buildString {
            append("{\"kind\":\"milestone\",\"ordinal\":")
            append(ordinal)
            if (label != null) {
                append(",\"label\":\"")
                append(escapeJsonString(label))
                append("\"")
            }
            append("}")
        }
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.milestone"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(encoded),
            ),
        )
    }

    private fun escapeJsonString(s: String): String {
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
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Copies workspace files matching an Ant-style `includes` pattern into the
     * run-scoped stash directory, so a later stage (within the same run) can
     * restore them via [unstash].
     *
     * WU-LPR-089 (Tier B #1): lowers directly to `StepSpec.RegistryStepSpec`
     * for `core.stash` with the canonical encoded envelope
     * `{"kind":"stash","name":"<name>","includes":"<includes>","excludes":"<excludes>"}` —
     * byte-for-byte identical to `CoreStashStep.inputCodec.encode()` so the
     * durable fingerprint round-trips through the G5 registry path.
     *
     * Jenkins verbatim (catalog §2.x): `stash(name: String, includes: String, excludes: String = "")`.
     *
     * @param name Stash logical name (re-used by [unstash]). Must be non-blank
     *             and contain no path separators / newlines (validated by the
     *             Step's typed input contract at handler time).
     * @param includes Ant-style pattern to match workspace files
     * @param excludes Comma-separated Ant-style patterns to exclude
     */
    fun stash(name: String, includes: String, excludes: String = "") {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"stash\",\"name\":\"").append(escapeJsonString(name)).append("\",")
        sb.append("\"includes\":\"").append(escapeJsonString(includes)).append("\"")
        if (excludes.isNotEmpty()) {
            sb.append(",\"excludes\":\"").append(escapeJsonString(excludes)).append("\"")
        }
        sb.append("}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.stash"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    /**
     * Restores files from a previously-produced stash (same run, any earlier
     * stage) into the current stage workspace.
     *
     * WU-LPR-089 (Tier B #2): lowers directly to `StepSpec.RegistryStepSpec`
     * for `core.unstash` with the canonical encoded envelope
     * `{"kind":"unstash","name":"<name>","into":"<into>"}` — byte-for-byte
     * identical to `CoreUnstashStep.inputCodec.encode()` so the durable
     * fingerprint round-trips through the G5 registry path.
     *
     * Jenkins verbatim: `unstash(name: String)`. Pipeline-K local-first extends
     * with an optional `into` parameter that scopes the restore to a
     * subdirectory of the workspace (must not contain `..` segments — Zip-Slip
     * guard).
     *
     * @param name Stash logical name (must match a previous [stash] in this run)
     * @param into Optional subdirectory of the workspace to restore into. Must
     *             be a relative path; the Step's typed contract forbids `..`
     *             segments at handler time.
     */
    fun unstash(name: String, into: String? = null) {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"unstash\",\"name\":\"").append(escapeJsonString(name)).append("\"")
        if (!into.isNullOrBlank()) {
            sb.append(",\"into\":\"").append(escapeJsonString(into)).append("\"")
        }
        sb.append("}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.unstash"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    // =============================================================================
    // WU-LPR-090 (Tier B #2): core.publishHTML DSL extension
    // =============================================================================

    /**
     * Publishes an HTML report from the stage workspace into the run-scoped
     * reports archive.
     *
     * Jenkins verbatim: `publishHTML(target: HtmlPublisherTarget)` where
     * `target` carries name, reportFiles, reportDir, keepAll, allowMissing,
     * escapeUnderscores. Pipeline-K keeps the public DSL surface narrow and
     * typed (positional + named args) instead of a target POJO.
     *
     * Lowers directly to `StepSpec.RegistryStepSpec` for `core.publishHTML`
     * with the canonical encoded envelope
     * `{"kind":"publishHTML","name":"<n>","reportDir":"<r>","reportFiles":"<f>",...}`
     * — byte-for-byte identical to `CorePublishHtmlStep.inputCodec.encode()`
     * so the durable fingerprint round-trips through the G5 registry path.
     *
     * @param name Logical name of the report (used to derive the archive
     *             subdirectory; sanitised by [dev.rubentxu.pipeline.v2.application.PublishHtmlSanitiser]).
     * @param reportDir Workspace-relative directory containing the report files.
     * @param reportFiles Ant-style glob (default `**` recursive match).
     * @param keepAll Whether to keep historical reports across runs (Pipeline-K
     *                 treats this as a typed hint; v1 always overwrites).
     * @param allowMissing When true, do not fail the Step if the directory or
     *                     glob is empty (emit `HtmlReportSkipped` instead).
     * @param escapeUnderscores When true, escape `_` in the sanitised name (Jenkins-canonical).
     */
    @JvmOverloads
    fun publishHTML(
        name: String,
        reportDir: String,
        reportFiles: String = "**",
        keepAll: Boolean = false,
        allowMissing: Boolean = false,
        escapeUnderscores: Boolean = false,
    ) {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"publishHTML\",\"name\":\"").append(escapeJsonString(name)).append("\",")
        sb.append("\"reportDir\":\"").append(escapeJsonString(reportDir)).append("\",")
        sb.append("\"reportFiles\":\"").append(escapeJsonString(reportFiles)).append("\"")
        if (keepAll) sb.append(",\"keepAll\":true")
        if (allowMissing) sb.append(",\"allowMissing\":true")
        if (escapeUnderscores) sb.append(",\"escapeUnderscores\":true")
        sb.append("}")
        steps.add(
            StepSpec.RegistryStepSpec(
                stepKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.publishHTML"),
                schemaVersion = "dsl-v1",
                encodedInput = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(sb.toString()),
            ),
        )
    }

    // =============================================================================
    // ML-R9 timeout/retry DSL (T-10)
    // =============================================================================

    /**
     * Executes the inner block with a timeout.
     *
     * Jenkins verbatim: `timeout(time: Long, unit: String, activity: String? = null) { block }`
     *
     * @param time Timeout value
     * @param unit Time unit (SECONDS, MINUTES, etc.)
     * @param activity Optional activity description
     * @param block Nested steps to execute with timeout
     */
    fun timeout(time: Long, unit: String, activity: String? = null, block: StageScope.() -> Unit) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.TimeoutBlock(time = time, unit = unit, activity = activity, steps = inner.steps.toList()))
    }

    /**
     * Executes the inner block with retry on failure.
     *
     * Jenkins verbatim: `retry(count: Int, conditions: List<String>? = null) { block }`
     *
     * @param count Maximum retry attempts
     * @param conditions Failure conditions to retry on (null = retry all)
     * @param block Nested steps to execute with retry
     */
    fun retry(count: Int, conditions: List<String>? = null, block: StageScope.() -> Unit) {
        val inner = StageScope(stageName, runtimeConfig)
        inner.block()
        steps.add(StepSpec.RetryBlock(count = count, conditions = conditions, steps = inner.steps.toList()))
    }

    fun toStageBuilder(): StageBuilder {
        // WU-RP-032 / DSL-008: post conditions are accepted DSL surface whose execution
        // semantics are NOT implemented in the compiled path. A declared post block that
        // would silently never run is a fake fallback (forbidden); reject at compile time.
        post?.let {
            throw IllegalStateException(
                "Stage '$stageName': post { } conditions are not supported by the compiled " +
                    "execution path (WU-RP-032). Move the steps into the stage body or use " +
                    "catchError/warnError semantics; refusing to silently ignore post.",
            )
        }
        return StageBuilder(stageName, steps.toList(), options, agent, environment?.values)
    }
}
