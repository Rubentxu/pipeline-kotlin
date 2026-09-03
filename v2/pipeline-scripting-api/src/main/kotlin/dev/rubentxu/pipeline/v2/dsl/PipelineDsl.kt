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
    ) : StepSpec {
        override val name: String get() = "archiveArtifacts"
        override val type: String get() = "archiveArtifacts"
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
     * @param initialRecurrencePeriod Initial poll interval in milliseconds
     * @param quiet If true, suppress output during polling
     */
    data class WaitUntil(
        val initialRecurrencePeriod: Long = 1L,
        val quiet: Boolean = false,
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
            is StepSpec.WaitUntil -> currentStep
            // ML-R9 T-08 output-decorators: not retryable at step level
            is StepSpec.Timestamps -> currentStep
            is StepSpec.AnsiColor -> currentStep
            is StepSpec.NodeNoOp -> currentStep
            // ML-R9 T-09 milestone: not retryable at step level
            is StepSpec.Milestone -> currentStep
            // ML-R9 T-10 timeout/retry blocks: not retryable at step level
            is StepSpec.TimeoutBlock -> currentStep
            is StepSpec.RetryBlock -> currentStep
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
        val inner = StageScope(stageName)
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
     *
     * @param artifacts Ant-style glob patterns (comma-separated)
     * @param allowEmptyArchive If true, empty archive is not a failure (default false)
     * @param excludes Ant-style patterns to exclude from archive
     * @param fingerprint If true, record fingerprints (F2)
     */
    fun archiveArtifacts(
        artifacts: String,
        allowEmptyArchive: Boolean = false,
        excludes: String = "",
        fingerprint: Boolean = false,
    ) {
        steps.add(
            StepSpec.ArchiveArtifacts(
                artifacts = artifacts,
                allowEmptyArchive = allowEmptyArchive,
                excludes = excludes,
                fingerprint = fingerprint,
            )
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
        val inner = StageScope(stageName)
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
     * @param deleteDirs If true, remove empty parent directories after deletion
     * @param patterns Ant-style glob patterns (null = delete all non-.v2 files)
     */
    fun cleanWs(deleteDirs: Boolean = true, patterns: List<String>? = null) {
        steps.add(StepSpec.CleanWs(deleteDirs = deleteDirs, patterns = patterns))
    }

    /**
     * Cleans the workspace with array syntax (Jenkins-faithful overload).
     *
     * @param deleteDirs If true, remove empty parent directories after deletion
     * @param patterns Ant-style glob patterns as vararg
     */
    fun cleanWs(deleteDirs: Boolean = true, vararg patterns: String) {
        steps.add(StepSpec.CleanWs(deleteDirs = deleteDirs, patterns = patterns.toList()))
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
    fun catchError(
        buildResult: String? = null,
        stageResult: String? = null,
        message: String? = null,
        block: StageScope.() -> Unit,
    ) {
        val inner = StageScope(stageName)
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
    fun warnError(
        message: String,
        catchInterruptions: Boolean = true,
        block: StageScope.() -> Unit,
    ) {
        val inner = StageScope(stageName)
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
    fun unstable(message: String) {
        steps.add(StepSpec.Unstable(message = message))
    }

    /**
     * Prints the current working directory (workspace root).
     *
     * Jenkins verbatim:
     * `pwd()` or `pwd(tmp: Boolean)`
     *
     * @param tmp If true, creates a temp subdirectory and returns its path
     */
    fun pwd(tmp: Boolean = false): String {
        val step = StepSpec.Pwd(tmp = tmp)
        steps.add(step)
        // Return value is set by the executor; for now return workspace root placeholder
        return "<workspace>"
    }

    /**
     * Checks whether the current system is Unix-like (Linux/macOS).
     *
     * Jenkins verbatim:
     * `isUnix()`
     *
     * @return true on Linux or macOS, false otherwise
     */
    fun isUnix(): Boolean {
        steps.add(StepSpec.IsUnix())
        // Return value is set by the executor
        return true
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
     * @param initialRecurrencePeriod Initial poll interval in milliseconds (default 1ms)
     * @param quiet If true, suppress output during polling
     * @param condition Lambda that returns true when the wait should stop
     * @throws WaitUntilDeadlineExceededException if deadline elapses before condition returns true
     */
    fun waitUntil(
        initialRecurrencePeriod: Long = 1L,
        quiet: Boolean = false,
        condition: () -> Boolean,
    ) {
        steps.add(StepSpec.WaitUntil(
            initialRecurrencePeriod = initialRecurrencePeriod,
            quiet = quiet,
        ))
        // Note: condition is currently not journaled; waitUntil uses ReplayPolicy.NEVER
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
        val inner = StageScope(stageName)
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
        val inner = StageScope(stageName)
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
        val inner = StageScope(stageName)
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
     * @param ordinal The milestone ordinal (must be monotonically increasing)
     * @param label Optional label for the milestone
     */
    fun milestone(ordinal: Int, label: String? = null) {
        steps.add(StepSpec.Milestone(ordinal = ordinal, label = label))
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
        val inner = StageScope(stageName)
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
        val inner = StageScope(stageName)
        inner.block()
        steps.add(StepSpec.RetryBlock(count = count, conditions = conditions, steps = inner.steps.toList()))
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
