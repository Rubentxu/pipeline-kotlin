package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm

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

    data class RegistryStepSpec(
        val stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        val schemaVersion: String = "dsl-v1",
        val encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
    ) : StepSpec {
        override val name: String get() = "registryStep"
        override val type: String get() = "registry"
    }

    data class RegistryBlockSpec(
        val stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        val schemaVersion: String = "dsl-v1",
        val encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        val body: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "registryBlock"
        override val type: String get() = "registry"
    }

    data class Echo(
        val text: String,
    ) : StepSpec {
        override val name: String get() = "echo"
        override val type: String get() = "echo"
    }

    data class Shell(
        val command: String,
        val isScriptBlock: Boolean = false,
        val returnStdout: Boolean = false,
    ) : StepSpec {
        override val name: String get() = "sh"
        override val type: String get() = "sh"
    }

    /**
     * P3-E E4 — [failureKind] defaults to [FailureKind.USER], not `UNKNOWN`.
     *
     * `error("msg")` is a deliberate, human-authored abort: somebody wrote it, on purpose,
     * with a message. `USER` is what that is. `UNKNOWN` means "this runtime could not
     * classify the failure" — which is what a decoder returns for a token it does not
     * recognise, a materially different epistemic state.
     *
     * The old default made every un-annotated `error()` indistinguishable in the durable
     * stream from a fault nobody understood, which is precisely the distinction an
     * external observer needs (P3-E E1, §5.7). Narrowing it also removes a fail-open edge:
     * the default used to be the one value that could only ever have come from nobody
     * choosing.
     *
     * P3-E E6 — [failureKind] is now [FailureKind], not a [String].
     *
     * As a String, a typo compiled. `error("boom", "USR")` produced a pipeline that ran, and
     * only failed when `CoreErrorStep`'s decoder reached a token outside the vocabulary — a
     * failure discovered at admission, mid-run, about a decision the author had already made
     * and believed was accepted. The author was wrong about the world and had no way to learn
     * so until something executed.
     *
     * Typed, that is a compile error against the vocabulary itself, which is the only place it
     * can be caught without running anything. This closes the last `String` in this module that
     * a P3-E authored semantic decision passed through.
     *
     * Two things this deliberately does NOT change:
     *
     *  - The wire. `D3`/the IR encoder projects `failureKind.name`, and every `FailureKind`
     *    case's `name` is its historical token, so `dsl-v1` payloads are byte-identical.
     *  - The authoring ceremony. `error("msg")` — the overwhelming majority — still needs no
     *    import, no vocabulary and no ceremony, because the default carries it. Only the
     *    explicit non-default case names `FailureKind.X`, and that case is the one where a
     *    compile-time check is worth an import.
     *
     * Recorded as a deliberate binary break in `published-contract-exceptions.json`.
     */
    data class Error(
        val message: String,
        val failureKind: FailureKind = FailureKind.USER,
    ) : StepSpec {
        override val name: String get() = "error"
        override val type: String get() = "error"
    }

    data class Sleep(
        val seconds: Long,
    ) : StepSpec {
        override val name: String get() = "sleep"
        override val type: String get() = "sleep"
    }

    data class Parallel(
        val branches: List<BranchSpec>,
    ) : StepSpec {
        override val name: String get() = "parallel"
        override val type: String get() = "parallel"
    }

    data class BranchSpec(
        val name: String,
        val steps: List<StepSpec>,
    )

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
            USERNAME_COLON_PASSWORD,
        }

        companion object {
            fun string(credentialsId: String, variable: String): CredentialsBinding =
                CredentialsBinding(Kind.STRING, CredentialsId(credentialsId), variable = variable)

            fun usernamePassword(
                credentialsId: String,
                usernameVariable: String,
                passwordVariable: String,
            ): CredentialsBinding = CredentialsBinding(
                Kind.USERNAME_PASSWORD,
                CredentialsId(credentialsId),
                usernameVariable = usernameVariable,
                passwordVariable = passwordVariable,
            )

            fun sshUserPrivateKey(
                credentialsId: String,
                keyFileVariable: String,
                passphraseVariable: String? = null,
                usernameVariable: String? = null,
            ): CredentialsBinding = CredentialsBinding(
                Kind.SSH_USER_PRIVATE_KEY,
                CredentialsId(credentialsId),
                keyFileVariable = keyFileVariable,
                passphraseVariable = passphraseVariable,
                usernameVariable = usernameVariable,
            )

            fun file(credentialsId: String, variable: String): CredentialsBinding =
                CredentialsBinding(Kind.FILE, CredentialsId(credentialsId), variable = variable)

            fun certificate(
                credentialsId: String,
                keystoreVariable: String,
                aliasVariable: String? = null,
                passwordVariable: String? = null,
            ): CredentialsBinding = CredentialsBinding(
                Kind.CERTIFICATE,
                CredentialsId(credentialsId),
                keystoreVariable = keystoreVariable,
                aliasVariable = aliasVariable,
                passwordVariable = passwordVariable,
            )

            fun zip(credentialsId: String, variable: String): CredentialsBinding =
                CredentialsBinding(Kind.ZIP, CredentialsId(credentialsId), variable = variable)

            fun zip(credentialsId: String, variable: String, passwordVariable: String): CredentialsBinding =
                CredentialsBinding(
                    Kind.ZIP,
                    CredentialsId(credentialsId),
                    variable = variable,
                    passwordVariable = passwordVariable,
                )

            fun usernameColonPassword(credentialsId: String, variable: String): CredentialsBinding =
                CredentialsBinding(Kind.USERNAME_COLON_PASSWORD, CredentialsId(credentialsId), variable = variable)
        }
    }

    data class WithCredentialsBlock(
        val credentialsId: CredentialsId,
        val purpose: String,
        val bindings: List<CredentialsBinding>,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "withCredentials"
        override val type: String get() = "withCredentials"
    }

    data class Checkout(
        val scm: Scm,
    ) : StepSpec {
        override val name: String get() = "checkout"
        override val type: String get() = "checkout"
    }

    data class WriteFile(
        val file: String,
        val text: String,
        val encoding: String = "UTF-8",
    ) : StepSpec {
        override val name: String get() = "writeFile"
        override val type: String get() = "writeFile"
    }

    data class ReadFile(
        val file: String,
        val encoding: String = "UTF-8",
    ) : StepSpec {
        override val name: String get() = "readFile"
        override val type: String get() = "readFile"
    }

    data class FileExists(
        val file: String,
    ) : StepSpec {
        override val name: String get() = "fileExists"
        override val type: String get() = "fileExists"
    }

    data class WithEnv(
        val overrides: List<String>,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "withEnv"
        override val type: String get() = "withEnv"
    }

    data class Dir(
        val path: String,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "dir"
        override val type: String get() = "dir"
    }

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

    data class ArtifactQuery(
        val artifactName: String,
        override val name: String = "artifactQuery",
    ) : StepSpec {
        override val type: String get() = "artifactQuery"
    }

    data class DeleteDir(
        val path: String = ".",
    ) : StepSpec {
        override val name: String get() = "deleteDir"
        override val type: String get() = "deleteDir"
    }

    data class CleanWs(
        val deleteDirs: Boolean = true,
        val patterns: List<String>? = null,
    ) : StepSpec {
        override val name: String get() = "cleanWs"
        override val type: String get() = "cleanWs"
    }

    @Deprecated(
        message = "LFC1-007: catchError is rewritten at compile time by rewriteWorkflowControl " +
            "into core.emit.event + core.sh (the rewrite IS implemented and runs). This spec type " +
            "remains as the rewrite's input shape; do not introduce new direct uses.",
    )
    data class CatchError(
        // P3-E D3: typed at authoring. `FailureKind` on Error did the same for error handling;
        // catchError had the identical shape of defect — a free string that only a runtime
        // `else` ever read — and it was the worse of the two, because that `else` SUPPRESSED
        // the failure. No String overload is kept on purpose: two spellings for one decision,
        // only one of them checked, is the ambiguity this migration exists to remove.
        val buildResult: CatchErrorBuildResult? = null,
        val stageResult: CatchErrorBuildResult? = null,
        val message: String? = null,
        val steps: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "catchError"
        override val type: String get() = "catchError"
    }

    @Deprecated(
        message = "LFC1-007: warnError is rewritten at compile time by rewriteWorkflowControl " +
            "into core.emit.event + core.sh (the rewrite IS implemented and runs). This spec type " +
            "remains as the rewrite's input shape; do not introduce new direct uses.",
    )
    data class WarnError(
        val message: String,
        val catchInterruptions: Boolean = true,
        val steps: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "warnError"
        override val type: String get() = "warnError"
    }

    @Deprecated(
        message = "LFC1-007: unstable is rewritten at compile time by rewriteWorkflowControl into " +
            "core.emit.event(StageMarkedUnstable) + core.sh(exit 0) (the rewrite IS implemented " +
            "and runs). This spec type remains as the rewrite's input shape; do not introduce " +
            "new direct uses.",
    )
    data class Unstable(
        val message: String,
    ) : StepSpec {
        override val name: String get() = "unstable"
        override val type: String get() = "unstable"
    }

    data class Pwd(
        val tmp: Boolean = false,
    ) : StepSpec {
        override val name: String get() = "pwd"
        override val type: String get() = "pwd"
    }

    class IsUnix : StepSpec {
        override val name: String get() = "isUnix"
        override val type: String get() = "isUnix"
    }

    data class Load(
        val path: String,
    ) : StepSpec {
        override val name: String get() = "load"
        override val type: String get() = "load"
    }

    data class WaitUntilBlock(
        val initialRecurrencePeriod: Long = 1L,
        val quiet: Boolean = false,
        val body: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "waitUntil"
        override val type: String get() = "waitUntil"
    }

    data class Timestamps(
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "timestamps"
        override val type: String get() = "timestamps"
    }

    data class AnsiColor(
        val colorMapName: String = "xterm",
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "ansiColor"
        override val type: String get() = "ansiColor"
    }

    data class NodeNoOp(
        val label: String? = null,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "node"
        override val type: String get() = "node"
    }

    data class Milestone(
        val ordinal: Int,
        val label: String? = null,
    ) : StepSpec {
        override val name: String get() = "milestone"
        override val type: String get() = "milestone"
    }

    data class TimeoutBlock(
        val time: Long,
        val unit: String,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "timeout"
        override val type: String get() = "timeout"
    }

    data class RetryBlock(
        val count: Int,
        val conditions: List<String>? = null,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "retry"
        override val type: String get() = "retry"
    }

    /**
     * `lock(resource) { ... }` — hold a named resource while the enclosed body
     * runs (RP6-A / WU-091). declarative structural IR only: the compiler
     * lowers this variant through [dev.rubentxu.pipeline.v2.application.CoreLockInput]
     * and the single wire authority `CoreLockWireCodec`; this type carries no
     * wire vocabulary of its own.
     *
     * Source-compatibility note (G3.6 classification, SPEC_WU091_LOCK.md):
     * adding a case to this sealed hierarchy is binary-compatible but is
     * SOURCE-ADDITIVE for consumers holding an exhaustive `when(step)` without
     * an `else` branch. In this repository the only concrete-case consumer is
     * the DSL compiler itself, whose dispatch is guarded by
     * `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` and the lock
     * wire-authority fitness.
     */
    data class Lock(
        val resource: String,
        val timeoutSeconds: Int? = null,
        val reason: String? = null,
        val skipIfLocked: Boolean = false,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "lock"
        override val type: String get() = "lock"
    }

    /**
     * `input(message) { ... }` — ask a human and continue only if they say yes
     * (RP6-B / WU-092). declarative structural IR only: the compiler lowers this
     * variant through
     * [dev.rubentxu.pipeline.v2.application.CoreInputInput] and the single wire
     * authority `CoreInputWireCodec`; this type carries no wire vocabulary of its
     * own.
     */
    data class Input(
        val message: String,
        val ok: String = "Proceed",
        val submitter: String? = null,
        val id: String? = null,
        val timeoutSeconds: Int? = null,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "input"
        override val type: String get() = "input"
    }
}
