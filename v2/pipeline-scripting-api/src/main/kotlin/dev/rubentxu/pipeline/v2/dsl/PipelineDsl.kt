package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm

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

    /**
     * Generic structural form for a Step hosted in the open StepRegistry (LB-02 / EP-F2).
     *
     * The structural DSL/IR stays CLOSED: external plugins cannot add arbitrary StepSpec subtypes.
     * Instead this ONE form carries only the structural data needed to produce a canonical registry
     * invocation: the StepKey, the schema/input-contract version, and the already-encoded input.
     *
     * It deliberately contains NO StepDefinition, handler, codec object, plugin class, capability or
     * registry reference. The compiler lowers it to a `StructuralRegistry` invocation without knowing
     * the concrete StepKey, and never decodes/re-encodes the typed input (that belongs to Execute).
     */
    data class RegistryStepSpec(
        val stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        val schemaVersion: String = "dsl-v1",
        val encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "registryStep"
        override val type: String get() = "registry"
    }

    /**
     * Generic structural form for a body-owning (Block) Step hosted in the open
     * StepRegistry (WU-RP-033 / RP-3 exit criterion).
     *
     * Mirror of [RegistryStepSpec] for Steps whose descriptor declares a body
     * (`StepBody.Declared`): the plugin owns the StepKey, the encoded input and
     * the typed DSL facade; the canonical child sequence is structural data the
     * compiler lowers recursively. The runtime rejects fail-closed via the
     * declared [BodyExecutionPolicy] resolution when the key's descriptor does
     * not declare a body, when the declaration is incoherent, or when the engine
     * does not support the shape.
     *
     * It deliberately contains NO StepDefinition, handler, codec object,
     * capability or registry reference. The compiler lowers it to a
     * [dev.rubentxu.pipeline.v2.domain.BlockStepNode] without knowing the
     * concrete StepKey.
     */
    data class RegistryBlockSpec(
        val stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        val schemaVersion: String = "dsl-v1",
        val encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        val body: List<StepSpec>,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "registryBlock"
        override val type: String get() = "registry"
    }

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
     * Seven binding kinds are supported (per ADR-0051 §D4 Jenkins verbatim signatures):
     * - [Kind.STRING]: injects a plaintext value as an environment variable
     * - [Kind.USERNAME_PASSWORD]: injects two env vars (username and password)
     * - [Kind.SSH_USER_PRIVATE_KEY]: injects SSH key file path + optional passphrase/user
     * - [Kind.FILE]: injects secret file path
     * - [Kind.CERTIFICATE]: injects certificate keystore path + optional alias/password
     * - [Kind.ZIP]: injects ZIP archive extraction directory path
     * - [Kind.USERNAME_COLON_PASSWORD]: injects colon-joined user:pass env var
     *
     * @see WithCredentialsBlock
     * @see <https://wiki.jenkins.io/display/JENKINS/Credentials+Binding+Plugin>
     */
    data class CredentialsBinding(
        val kind: Kind,
        val credentialsId: CredentialsId,
        val variable: String? = null,
        val usernameVariable: String? = null,
        val passwordVariable: String? = null,
        val keyFileVariable: String? = null,
        val passphraseVariable: String? = null,
        val keystoreVariable: String? = null,
        val aliasVariable: String? = null,
    ) {
        enum class Kind {
            STRING,
            USERNAME_PASSWORD,
            SSH_USER_PRIVATE_KEY,
            FILE,
            CERTIFICATE,
            ZIP,
            USERNAME_COLON_PASSWORD
        }

        companion object {
            /**
             * Creates a STRING binding: the secret value is injected as a single env var.
             *
             * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
             * @param variable The environment variable name
             */
            fun string(credentialsId: String, variable: String): CredentialsBinding {
                return CredentialsBinding(Kind.STRING, CredentialsId(credentialsId), variable = variable)
            }

            /**
             * Creates a USERNAME_PASSWORD binding: two env vars are injected.
             *
             * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
             * @param usernameVariable The username environment variable name
             * @param passwordVariable The password environment variable name
             */
            fun usernamePassword(
                credentialsId: String,
                usernameVariable: String,
                passwordVariable: String,
            ): CredentialsBinding {
                return CredentialsBinding(
                    Kind.USERNAME_PASSWORD,
                    CredentialsId(credentialsId),
                    usernameVariable = usernameVariable,
                    passwordVariable = passwordVariable,
                )
            }

            /**
             * Creates an SSH_USER_PRIVATE_KEY binding: injects key file path + optional passphrase/user.
             *
             * Jenkins verbatim signature: credentialsId, keyFileVariable, passphraseVariable?, usernameVariable?
             *
             * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
             * @param keyFileVariable The SSH key file environment variable name
             * @param passphraseVariable The passphrase environment variable name (optional)
             * @param usernameVariable The username environment variable name (optional)
             */
            fun sshUserPrivateKey(
                credentialsId: String,
                keyFileVariable: String,
                passphraseVariable: String? = null,
                usernameVariable: String? = null,
            ): CredentialsBinding {
                return CredentialsBinding(
                    Kind.SSH_USER_PRIVATE_KEY,
                    CredentialsId(credentialsId),
                    keyFileVariable = keyFileVariable,
                    passphraseVariable = passphraseVariable,
                    usernameVariable = usernameVariable,
                )
            }

            /**
             * Creates a FILE binding: injects secret file path.
             *
             * Jenkins verbatim signature: credentialsId, variable
             *
             * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
             * @param variable The file path environment variable name
             */
            fun file(credentialsId: String, variable: String): CredentialsBinding {
                return CredentialsBinding(Kind.FILE, CredentialsId(credentialsId), variable = variable)
            }

            /**
             * Creates a CERTIFICATE binding: injects keystore path + optional alias/password.
             *
             * Jenkins verbatim signature: keystoreVariable, credentialsId, aliasVariable?, passwordVariable?
             *
             * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
             * @param keystoreVariable The keystore file environment variable name
             * @param aliasVariable The alias environment variable name (optional)
             * @param passwordVariable The password environment variable name (optional)
             */
            fun certificate(
                credentialsId: String,
                keystoreVariable: String,
                aliasVariable: String? = null,
                passwordVariable: String? = null,
            ): CredentialsBinding {
                return CredentialsBinding(
                    Kind.CERTIFICATE,
                    CredentialsId(credentialsId),
                    keystoreVariable = keystoreVariable,
                    aliasVariable = aliasVariable,
                    passwordVariable = passwordVariable,
                )
            }

            /**
             * Creates a ZIP binding: injects ZIP archive extraction directory path.
             *
             * Jenkins verbatim signature: variable, credentialsId
             *
             * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
             * @param variable The ZIP path environment variable name
             */
            fun zip(credentialsId: String, variable: String): CredentialsBinding {
                return CredentialsBinding(Kind.ZIP, CredentialsId(credentialsId), variable = variable)
            }

            /**
             * WU-LPR-071: zip binding with password variable (Jenkins
             * `zip zipCredentialsId: '...', variable: '...', zipVariable: '...'` shape).
             */
            fun zip(credentialsId: String, variable: String, passwordVariable: String): CredentialsBinding {
                return CredentialsBinding(
                    Kind.ZIP,
                    CredentialsId(credentialsId),
                    variable = variable,
                    passwordVariable = passwordVariable,
                )
            }

            /**
             * Creates a USERNAME_COLON_PASSWORD binding: injects colon-joined user:pass env var.
             *
             * Jenkins verbatim signature: variable, credentialsId
             *
             * @param credentialsId The credentials ID in the store (string — CredentialsId is constructed internally)
             * @param variable The user:pass environment variable name
             */
            fun usernameColonPassword(credentialsId: String, variable: String): CredentialsBinding {
                return CredentialsBinding(Kind.USERNAME_COLON_PASSWORD, CredentialsId(credentialsId), variable = variable)
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

    /**
     * Git checkout step using Jenkins parity checkout/scmGit/git DSL.
     *
     * Wraps [CheckoutSpec] from the domain layer.
     *
     * @param scm The SCM specification (must be [GitScm] at L5)
     */
    data class Checkout(
        val scm: Scm,
        override val retry: dev.rubentxu.pipeline.v2.domain.durable.RetryPolicy? = null,
        override val timeoutMillis: Long? = null,
    ) : StepSpec {
        override val name: String get() = "checkout"
        override val type: String get() = "checkout"
    }

    // =============================================================================
    // L7 Jenkins top-steps (ML-R7) — ADR-0046 §D2 verbatim signatures
    // =============================================================================

    /**
     * Writes content to a file in the workspace.
     *
     * Jenkins verbatim signature (catalog §1.1 line 35):
     * `writeFile(file: String, text: String, encoding: String = "UTF-8")`
     *
     * F1: file, text required. F2: encoding (UTF-8 default; "Base64" decodes binary).
     *
     * @param file Workspace-relative file path
     * @param text Content to write
     * @param encoding Character encoding (default UTF-8; use "Base64" for binary)
     */
    data class WriteFile(
        val file: String,
        val text: String,
        val encoding: String = "UTF-8",
    ) : StepSpec {
        override val name: String get() = "writeFile"
        override val type: String get() = "writeFile"
    }

    /**
     * Reads content from a file in the workspace.
     *
     * Jenkins verbatim signature (catalog §1.1 line 36):
     * `readFile(file: String, encoding: String = "UTF-8")`
     *
     * NOTE: No returnValue field — consumers use `sh(returnStdout=true)` for runtime
     * values. Documented limitation per D2; addressed in ML-R8 follow-up.
     *
     * @param file Workspace-relative file path
     * @param encoding Character encoding (default UTF-8)
     */
    data class ReadFile(
        val file: String,
        val encoding: String = "UTF-8",
    ) : StepSpec {
        override val name: String get() = "readFile"
        override val type: String get() = "readFile"
    }

    /**
     * Checks whether a file exists in the workspace.
     *
     * Jenkins verbatim signature (catalog §1.1 line 37):
     * `fileExists(file: String)` — returns Boolean.
     *
     * NOTE: No returnValue field — consumers use `sh(returnStdout=true)` for runtime
     * values. Documented limitation per D2; addressed in ML-R8 follow-up.
     *
     * @param file Workspace-relative file path
     */
    data class FileExists(
        val file: String,
    ) : StepSpec {
        override val name: String get() = "fileExists"
        override val type: String get() = "fileExists"
    }

    /**
     * Sets environment variables for the duration of a nested block.
     *
     * Jenkins verbatim signature (catalog §1.1 line 40):
     * `withEnv(overrides: List<String>)`
     *
     * Each entry is `"VAR=value"` or `"PATH+X=/dir"` (PATH prepend per catalog §3 lines 261-291).
     * Nested block mirrors [WithCredentialsBlock] pattern — [steps] carries the desugared
     * inner scope's emitted StepSpecs.
     *
     * @param overrides Environment variable overrides (each `"VAR=value"` or `"PATH+X=/dir"`)
     * @param steps Nested block payload — steps executed with the overridden env
     */
    data class WithEnv(
        val overrides: List<String>,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "withEnv"
        override val type: String get() = "withEnv"
    }

    // =============================================================================
    // ML-R9 workflow-control step kinds
    // =============================================================================

    /**
     * Changes the current working directory for the duration of a nested block.
     *
     * Jenkins verbatim signature (catalog §1.1/§1.2):
     * `dir(path: String) { ... }`
     *
     * Changes the working directory (cwd) for nested steps. The previous working
     * directory is restored when the block exits (including on exception).
     *
     * @param path Workspace-relative or absolute directory path
     * @param steps Nested block payload
     */
    data class Dir(
        val path: String,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "dir"
        override val type: String get() = "dir"
    }

    /**
     * Archives artifacts for retention (server-side artifact storage).
     *
     * Jenkins verbatim signature (catalog §1.1 line 45):
     * `archiveArtifacts(artifacts: String, allowEmptyArchive: Boolean = false,
     *                  fingerprint: Boolean = false, onlyIfSuccessful: Boolean = false)`
     *
     * F1: artifacts (required). F2: allowEmptyArchive, fingerprint, onlyIfSuccessful.
     * The `excludes` parameter is deferred to L7.1 per spec.
     *
     * @param artifacts Ant-style glob patterns (comma-separated)
     * @param allowEmptyArchive If true, empty archive is not a failure (default false)
     * @param fingerprint If true, record fingerprints (F2/deferred to L7.1)
     * @param onlyIfSuccessful If true, archive only on successful build (F2/deferred to L7.1)
     */
    data class ArchiveArtifacts(
        val artifacts: String,
        val allowEmptyArchive: Boolean? = false,
        val excludes: String = "",
        val fingerprint: Boolean? = false,
        val artifactName: String? = null,
    ) : StepSpec {
        override val name: String get() = "archiveArtifacts"
        override val type: String get() = "archiveArtifacts"
    }

    // =============================================================================
    // E1.1 / core.artifact.query (T7) — DSL lowering form
    // =============================================================================

    /**
     * DSL data form for `core.artifact.query`. The runtime registered Step
     * accepts [ArtifactQueryInput] in domain; the DSL façade lowers to
     * `StepSpec.RegistryStepSpec` with the canonical encoded envelope so the
     * durable fingerprint matches `CoreArtifactQueryStep.definition.inputCodec`.
     *
     * Kept as a sealed data class so ScriptCompiler visibility tests can
     * pattern-match without coupling to the registry transport.
     */
    data class ArtifactQuery(
        val artifactName: String,
        override val name: String = "artifactQuery",
    ) : StepSpec {
        override val type: String get() = "artifactQuery"
    }

    // =============================================================================
    // ML-R9 workspace-cleanup step kinds (T-05)
    // =============================================================================

    /**
     * Deletes the specified directory within the workspace (default: current workspace).
     *
     * Jenkins verbatim (catalog §1.1 line 44):
     * `deleteDir()`
     *
     * Idempotent: re-execution on already-deleted path emits DirDeleted with deletedCount=0.
     *
     * @param path Workspace-relative path (default ".")
     */
    data class DeleteDir(
        val path: String = ".",
    ) : StepSpec {
        override val name: String get() = "deleteDir"
        override val type: String get() = "deleteDir"
    }

    /**
     * Cleans the workspace with optional Ant-style glob filtering.
     *
     * Jenkins verbatim (catalog §1.1 line 44):
     * `cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null)`
     *
     * @param deleteDirs If true, remove empty parent directories after deletion
     * @param patterns Ant-style glob patterns (null = delete all non-.v2 files)
     */
    data class CleanWs(
        val deleteDirs: Boolean = true,
        val patterns: List<String>? = null,
    ) : StepSpec {
        override val name: String get() = "cleanWs"
        override val type: String get() = "cleanWs"
    }

    // =============================================================================
    // ML-R9 error-handling step kinds (T-06)
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
     * @param steps Nested steps
     */
    @Deprecated(
        message = "LFC1-007: catchError is pre-compiler-rewritten to core.emit.event + core.sh. " +
                "The canonical IR models this as a linear sequence of marker events + shell script. " +
                "DSL use is deprecated; this class is retained for deserialization of legacy fixtures only.",
        replaceWith = ReplaceWith("StepSpec.CatchError"),
    )
    data class CatchError(
        val buildResult: String? = null,
        val stageResult: String? = null,
        val message: String? = null,
        val steps: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "catchError"
        override val type: String get() = "catchError"
    }

    /**
     * Catches errors and forces stage result to UNSTABLE (warnError semantics).
     *
     * Jenkins verbatim:
     * `warnError(message: String, catchInterruptions: Boolean = true) { ... }`
     *
     * Equivalent to `catchError(message, buildResult="UNSTABLE")`.
     *
     * @param message User-visible warning message
     * @param catchInterruptions If true, also catch Thread.interrupt()
     * @param steps Nested steps
     */
    @Deprecated(
        message = "LFC1-007: warnError is pre-compiler-rewritten to core.emit.event + core.sh. " +
                "The canonical IR models this as a linear sequence of marker events + shell script. " +
                "DSL use is deprecated; this class is retained for deserialization of legacy fixtures only.",
        replaceWith = ReplaceWith("StepSpec.WarnError"),
    )
    data class WarnError(
        val message: String,
        val catchInterruptions: Boolean = true,
        val steps: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "warnError"
        override val type: String get() = "warnError"
    }

    /**
     * Marks the current stage as unstable (soft warning, pipeline continues).
     *
     * Jenkins verbatim:
     * `unstable(message: String)`
     *
     * Pipeline-level exit code remains 0 (Jenkins soft-warning semantics).
     *
     * @param message User-visible message describing the instability
     */
    @Deprecated(
        message = "LFC1-007: unstable is pre-compiler-rewritten to core.emit.event(StageMarkedUnstable) + core.sh(exit 0). " +
                "The canonical IR models this as two nodes. DSL use is deprecated; " +
                "this class is retained for deserialization of legacy fixtures only.",
        replaceWith = ReplaceWith("StepSpec.Unstable"),
    )
    data class Unstable(
        val message: String,
    ) : StepSpec {
        override val name: String get() = "unstable"
        override val type: String get() = "unstable"
    }

    /**
     * Prints the current working directory (workspace root).
     *
     * Jenkins verbatim:
     * `pwd()` or `pwd(tmp: Boolean)`
     *
     * @param tmp If true, creates a temp subdirectory and returns its path
     */
    data class Pwd(
        val tmp: Boolean = false,
    ) : StepSpec {
        override val name: String get() = "pwd"
        override val type: String get() = "pwd"
    }

    /**
     * Checks whether the current system is Unix-like (Linux/macOS).
     *
     * Jenkins verbatim:
     * `isUnix()`
     */
    class IsUnix : StepSpec {
        override val name: String get() = "isUnix"
        override val type: String get() = "isUnix"
    }

    /**
     * Loads and executes steps from an external pipeline script file.
     *
     * Jenkins verbatim:
     * `load(path: String)`
     *
     * The path is resolved relative to the workspace root. On successful
     * load, the file is compiled via Kotlin24ScriptingHost and its steps
     * are appended to the current execution scope.
     *
     * Re-entrancy: if the same (path, sha256) is loaded twice in one run,
     * the second load is a no-op with stepCount=0.
     *
     * @param path Workspace-relative path to the .pipeline.kts file
     */
    data class Load(
        val path: String,
    ) : StepSpec {
        override val name: String get() = "load"
        override val type: String get() = "load"
    }

    /**
     * Polls a condition closure until it returns true or a deadline elapses.
     *
     * Jenkins verbatim:
     * `waitUntil(initialRecurrencePeriod: Long = 1, quiet: Boolean = false) { condition }`
     *
     * Body-capturing variant (wu-g5-restore): the body lambda is captured as a
     * `List<StepSpec>` via the `StageScope` mechanism. Eager evaluation of the
     * condition is removed (AGENTS.md §10: DSL describes; interpreters execute —
     * no runtime effects at construction time).
     *
     * The body is re-entered through the canonical `dispatchRepeatUntilBody`
     * coordinator path (ADR-0073, BodyInvoker re-entry). The predicate outcome
     * is emitted as typed events and folded by `WaitUntilReconciler`.
     *
     * @param initialRecurrencePeriod Initial poll interval in milliseconds
     * @param quiet If true, suppress output during polling
     * @param body Nested steps whose last step emits WaitUntilPredicateEvaluated
     */
    data class WaitUntilBlock(
        val initialRecurrencePeriod: Long = 1L,
        val quiet: Boolean = false,
        val body: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "waitUntil"
        override val type: String get() = "waitUntil"
    }

    // =============================================================================
    // ML-R9 output-decorator step kinds (T-08) — NO new DomainEvent variants (marker reuse)
    // =============================================================================

    /**
     * Decorates captured stdout/stderr with timestamps.
     *
     * Jenkins verbatim: `timestamps { block }`
     *
     * Wraps the captured output with a SimpleFormatter that prepends HH:mm:ss.SSS
     * to each line. Pure log-rewriter orchestrator — reuses StepStarted/StepFinished
     * with stepType="timestamps" (marker-event reuse per ADR-0052 §D5).
     *
     * Effect.READ_ONLY, ReplayPolicy.MEMOIZED.
     */
    data class Timestamps(
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "timestamps"
        override val type: String get() = "timestamps"
    }

    /**
     * Decorates captured stdout/stderr with ANSI color codes.
     *
     * Jenkins verbatim: `ansiColor(colorMapName: String = "xterm") { block }`
     *
     * The colorMapName selects the color palette mapping (e.g., "xterm", "vga").
     * Tee-passes ANSI escape codes through unchanged. Pure log-rewriter orchestrator —
     * reuses StepStarted/StepFinished with stepType="ansiColor" (marker-event reuse).
     *
     * Effect.READ_ONLY, ReplayPolicy.MEMOIZED.
     *
     * @param colorMapName Color map name (default "xterm" per Jenkins catalog §1.13 line 194)
     * @param steps Nested block payload
     */
    data class AnsiColor(
        val colorMapName: String = "xterm",
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "ansiColor"
        override val type: String get() = "ansiColor"
    }

    /**
     * No-op step that emits AgentResolved.
     *
     * Jenkins verbatim: `node(label?: String) { block }`
     *
     * In local execution model, node is a no-op that emits AgentResolved event.
     * The block is executed as-is. Reuses existing AgentResolved event per ADR-0052 §D5.
     *
     * Effect.EXECUTES_SUBPROCESS, ReplayPolicy.RERUN.
     *
     * @param label Agent label (optional)
     * @param steps Nested block payload
     */
    data class NodeNoOp(
        val label: String? = null,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "node"
        override val type: String get() = "node"
    }

    // =============================================================================
    // ML-R9 milestone step kind (T-09) — NEW events: MilestoneReached, MilestoneAborted
    // =============================================================================

    /**
     * Milestone step for cross-build coordination.
     *
     * Jenkins verbatim: `milestone(ordinal: Int, label: String? = null)`
     *
     * Records a milestone reached for coordinating concurrent builds.
     * In local single-run model, emits MilestoneReached or MilestoneAborted events
     * but does NOT abort inner steps (single-run semantics per ADR-0046 §ML).
     *
     * Effect.EXECUTES_SUBPROCESS, ReplayPolicy.RERUN.
     *
     * @param ordinal The milestone ordinal (must be monotonically increasing within a run)
     * @param label Optional label for the milestone
     */
    data class Milestone(
        val ordinal: Int,
        val label: String? = null,
    ) : StepSpec {
        override val name: String get() = "milestone"
        override val type: String get() = "milestone"
    }

    // =============================================================================
    // ML-R9 timeout/retry block steps (T-10) — NEW event: TimeoutTriggered
    // =============================================================================

    /**
     * Timeout block with wall-clock deadline.
     *
     * Jenkins verbatim: `timeout(time: Long, unit: String, activity: String? = null) { block }`
     *
     * Executes the inner block with a deadline. If the deadline elapses before
     * completion, emits TimeoutTriggered and throws FlowInterruptedException.
     * The inner durable sh subprocess is destroyedForcibly() per ADR-0046 §D2.
     *
     * Effect.EXECUTES_SUBPROCESS, ReplayPolicy.RERUN.
     *
     * @param time Timeout value
     * @param unit Time unit (SECONDS, MINUTES, etc.)
     * @param activity Optional activity description
     * @param steps Nested block payload
     */
    data class TimeoutBlock(
        val time: Long,
        val unit: String,
        val activity: String? = null,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "timeout"
        override val type: String get() = "timeout"
    }

    /**
     * Retry block that re-executes on failure.
     *
     * Jenkins verbatim: `retry(count: Int, conditions: List<String>? = null) { block }`
     *
     * Executes the inner block up to `count` times on failure.
     * Emits RetryAttemptStarted/RetryAttemptFinished per attempt (M2-R1 events, reused).
     * The `conditions` list filters which failures trigger retry (null = retry all).
     *
     * Effect.EXECUTES_SUBPROCESS, ReplayPolicy.RERUN.
     *
     * @param count Maximum retry attempts
     * @param conditions Failure conditions to retry on (null = retry all)
     * @param steps Nested block payload
     */
    data class RetryBlock(
        val count: Int,
        val conditions: List<String>? = null,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "retry"
        override val type: String get() = "retry"
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
 *
 * WU-RP-032: only `timeout` is in the surface — it is the only stage option with
 * a runtime interpreter (projectShellOptions -> ShOptions.timeoutMs). Stage-level
 * `retry`/`skip` were removed (WU-RP-032): retry semantics live in the retry
 * Block Step (durable control row, ADR-0075); skip is not a durable-engine concept.
 * Invalid surface is unrepresentable instead of accepted-and-dropped.
 */
data class OptionsSpec(
    val timeout: Long? = null,
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
