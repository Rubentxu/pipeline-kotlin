package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS
import dev.rubentxu.pipeline.v2.domain.scm.Scm

/**
 * Sealed hierarchy of steps that a stage can contain.
 *
 * This interface extends [dev.rubentxu.pipeline.v2.domain.durable.StepSpec] to enable
 * the domain layer's [dev.rubentxu.pipeline.v2.domain.durable.BranchSpec] to reference
 * DSL steps without creating a domain→DSL dependency.
 */
sealed interface StepSpec : dev.rubentxu.pipeline.v2.domain.durable.StepSpec {
    override val name: String
    override val type: String

    /**
     * A step addressed by the key of an installed plugin, with its input already
     * encoded in that plugin's own vocabulary.
     *
     * The key, the schema version and the encoded input reach the compiled node
     * untouched: the compiler resolves no definition, decodes no input and
     * encodes no payload, so the plugin's own codec stays the only authority
     * over those bytes. Admission resolves the key against the registry, and a
     * key that no registered definition owns fails the run closed.
     */
    data class RegistryStepSpec(
        val stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        val schemaVersion: String = "dsl-v1",
        val encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
    ) : StepSpec {
        override val name: String get() = "registryStep"
        override val type: String get() = "registry"
    }

    /**
     * A body-bearing step addressed by the key of an installed plugin.
     *
     * The key and the encoded input reach the compiled node untouched, and the
     * children are lowered with the same machinery every other body family uses,
     * so each of them keeps a durable row of its own. The plugin declares the
     * policy the body runs under; admission reads that policy from the registry
     * and refuses a definition that declares none, before any child runs.
     */
    data class RegistryBlockSpec(
        val stepKey: dev.rubentxu.pipeline.v2.domain.PluginStepId,
        val schemaVersion: String = "dsl-v1",
        val encodedInput: dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue,
        val body: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "registryBlock"
        override val type: String get() = "registry"
    }

    /**
     * Writes one line to the run log and hands that line back as the step's
     * runtime value.
     *
     * The step touches nothing in the workspace, and the text it prints cannot
     * fail the stage: the line is carried as it is.
     */
    data class Echo(
        val text: String,
    ) : StepSpec {
        override val name: String get() = "echo"
        override val type: String get() = "echo"
    }

    /**
     * Runs a command in the stage's shell.
     *
     * The command is handed to the shell as it stands: [isScriptBlock] decides
     * whether the shell receives it as a script block or as a single line, and
     * [returnStdout] asks for the command's standard output to be published as
     * the step's runtime value. The two flags are the only choices the DSL
     * shape offers.
     *
     * A command that ends with a non-zero status fails the step.
     */
    data class Shell(
        val command: String,
        val isScriptBlock: Boolean = false,
        val returnStdout: Boolean = false,
    ) : StepSpec {
        override val name: String get() = "sh"
        override val type: String get() = "sh"
    }

    /**
     * [failureKind] defaults to [FailureKind.USER], not `UNKNOWN`.
     *
     * `error("msg")` is a deliberate, human-authored abort: somebody wrote it, on purpose,
     * with a message. `USER` is what that is. `UNKNOWN` means "this runtime could not
     * classify the failure" — which is what a decoder returns for a token it does not
     * recognise, a materially different epistemic state.
     *
     * The old default made every un-annotated `error()` indistinguishable in the durable
     * stream from a fault nobody understood, which is precisely the distinction an
     * external observer needs (E1). Narrowing it also removes a fail-open edge:
     * the default used to be the one value that could only ever have come from nobody
     * choosing.
     *
     * [failureKind] is now [FailureKind], not a [String].
     *
     * As a String, a typo compiled. `error("boom", "USR")` produced a pipeline that ran, and
     * only failed when `CoreErrorStep`'s decoder reached a token outside the vocabulary — a
     * failure discovered at admission, mid-run, about a decision the author had already made
     * and believed was accepted. The author was wrong about the world and had no way to learn
     * so until something executed.
     *
     * Typed, that is a compile error against the vocabulary itself, which is the only place it
     * can be caught without running anything. This closes the last `String` in this module that
     * a authored semantic decision passed through.
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

    /**
     * Pauses the run for the given number of seconds.
     *
     * The pause happens inside the step, so the stage waits for it. A duration
     * below zero is refused when the step is decoded, before the pause begins.
     */
    data class Sleep(
        val seconds: Long,
    ) : StepSpec {
        override val name: String get() = "sleep"
        override val type: String get() = "sleep"
    }

    /**
     * A stage body whose branches run at the same time.
     *
     * The branches share the stage's workspace and its environment, and the
     * stage's result is a fold over theirs. A parallel body is the only step of
     * its stage: a stage that mixes one with sibling steps is refused when the
     * pipeline is compiled.
     */
    data class Parallel(
        val branches: List<BranchSpec>,
    ) : StepSpec {
        override val name: String get() = "parallel"
        override val type: String get() = "parallel"
    }

    /**
     * One named branch of a parallel body.
     *
     * The name is part of the branch's durable identity: the compiler mints it
     * from the name, normalising it, so two branches of the same parallel body
     * that share a name mint the same identity and their rows cannot be told
     * apart. Two DIFFERENT names that normalise to the same identity are
     * refused when the pipeline is compiled.
     */
    data class BranchSpec(
        val name: String,
        val steps: List<StepSpec>,
    )

    /**
     * How one credential is bound to the environment of a body that needs it.
     *
     * The kind decides which of the variable fields the binding uses and which
     * ones are meaningless for it. The binding names a credential and never
     * carries its material: the secret is resolved when the body starts, and
     * every value it produces is masked.
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
        /**
         * The shape of a credential binding: what the stored material is and which
         * variables it occupies.
         *
         * Each kind names one shape, and the fields that shape does not use stay
         * empty. The kind decides which variables the projection reads: a binding
         * that does not name the variables its kind requires is refused when it is
         * projected, and the fields another kind would use are ignored.
         */
        enum class Kind {
            /**
             * One opaque text value, published in the variable the binding names.
             */
            STRING,
            /**
             * A username and a password, published in two separate variables.
             */
            USERNAME_PASSWORD,
            /**
             * An SSH private key, materialised as a file whose path is published.
             */
            SSH_USER_PRIVATE_KEY,
            /**
             * A file, published in the variable the binding names.
             */
            FILE,
            /**
             * A certificate keystore, published with its optional alias and password.
             */
            CERTIFICATE,
            /**
             * A zip archive, published in the variable the binding names.
             */
            ZIP,
            /**
             * A `username:password` pair, published as one variable.
             */
            USERNAME_COLON_PASSWORD,
        }

        /**
         * The factories that build a binding for one credential shape.
         *
         * Each one names the credential and the variables that shape occupies, so a
         * caller writes the variables the shape requires instead of leaving the
         * fields the shape ignores empty.
         */
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

    /**
     * A body that runs with credentials bound to the environment.
     *
     * The bindings say which credentials are resolved and which variables they
     * occupy. The material is resolved when the body starts and lives only for
     * the length of the body; the values it publishes are masked.
     */
    data class WithCredentialsBlock(
        val credentialsId: CredentialsId,
        val purpose: String,
        val bindings: List<CredentialsBinding>,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "withCredentials"
        override val type: String get() = "withCredentials"
    }

    /**
     * Checks an SCM source into the stage workspace.
     *
     * The SCM states the remote, the branch, and whether the checkout records a
     * changelog or polls for new commits; an SCM that is not a git one carries
     * none of those fields, and the compiled payload carries the empty
     * defaults. Credentials are named by their id and resolved when the step
     * runs; their material never reaches the IR.
     */
    data class Checkout(
        val scm: Scm,
    ) : StepSpec {
        override val name: String get() = "checkout"
        override val type: String get() = "checkout"
    }

    /**
     * Writes text to a file of the stage workspace.
     *
     * The write replaces what the file held and creates the directories the
     * path needs. The path is resolved against the workspace, and one that
     * leaves it is refused. [encoding] names the character set the text is
     * written in.
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
     * Reads a file of the stage workspace and hands its content back as the
     * step's runtime value.
     *
     * The path is resolved against the workspace, and one that leaves it is
     * refused. A file that is not there is not a failure: the result says that
     * it does not exist and carries no content.
     */
    data class ReadFile(
        val file: String,
        val encoding: String = "UTF-8",
    ) : StepSpec {
        override val name: String get() = "readFile"
        override val type: String get() = "readFile"
    }

    /**
     * Reports whether a path of the stage workspace exists, as the step's
     * runtime value.
     *
     * A path that leaves the workspace is reported as not existing, because
     * the substrate's path guard refuses to probe outside the workspace, so
     * nothing outside it can be probed through this step.
     */
    data class FileExists(
        val file: String,
    ) : StepSpec {
        override val name: String get() = "fileExists"
        override val type: String get() = "fileExists"
    }

    /**
     * A body that runs with environment variables replaced.
     *
     * Each override is a `NAME=VALUE` line, and it holds for the length of the
     * body. The variables the run had before are restored when the body ends.
     */
    data class WithEnv(
        val overrides: List<String>,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "withEnv"
        override val type: String get() = "withEnv"
    }

    /**
     * A body that runs with the working directory set to a path.
     *
     * The path is resolved against the stage workspace, and the working
     * directory the run had before is restored when the body ends.
     */
    data class Dir(
        val path: String,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "dir"
        override val type: String get() = "dir"
    }

    /**
     * Copies the workspace files a pattern selects into the store of this run,
     * so a later stage of the same run can retrieve them.
     *
     * [artifacts] is the Ant-style pattern that selects the files and
     * [excludes] removes some of them. [allowEmptyArchive] decides whether a
     * selection that matches nothing fails the step, [fingerprint] asks for the
     * digest of the archived content to be recorded, and [artifactName] names
     * the archive.
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

    /**
     * Asks the run for the archive it published under a name.
     *
     * The answer is a read of the run's own index of archives; a name no
     * archive answers is a typed `USER` failure rather than a thrown
     * error.
     *
     * The registered definition answers under the key `core.artifact.query`, and
     * this shape carries no canonical payload of its own, so the compiler refuses
     * it; the authoring helper lowers to the registry shape instead.
     */
    data class ArtifactQuery(
        val artifactName: String,
        override val name: String = "artifactQuery",
    ) : StepSpec {
        override val type: String get() = "artifactQuery"
    }

    /**
     * Deletes the contents of a directory of the stage workspace.
     *
     * The directory itself survives the deletion, and the step leaves a marker
     * that lets a replay reproduce the same result without deleting again.
     */
    data class DeleteDir(
        val path: String = ".",
    ) : StepSpec {
        override val name: String get() = "deleteDir"
        override val type: String get() = "deleteDir"
    }

    /**
     * Removes the files of the stage workspace a selection matches.
     *
     * [patterns] are Ant-style globs. A pattern-less selection removes every
     * file the workspace holds except the reserved `.v2/artifacts` subtree, and
     * only on a workspace the product owns; on any other workspace the
     * pattern-less form is refused. [deleteDirs] decides whether the
     * directories the removal left empty are deleted too.
     */
    data class CleanWs(
        val deleteDirs: Boolean = true,
        val patterns: List<String>? = null,
    ) : StepSpec {
        override val name: String get() = "cleanWs"
        override val type: String get() = "cleanWs"
    }

    @Deprecated(
        message = "catchError is compiled into a canonical body-bearing Step: the scope's " +
            "declared contract travels in the block payload and its children travel in the " +
            "block body, so the scope is executed by the canonical body engine and its " +
            "outcome is decided by the scope itself. This spec type remains as the " +
            "authoring shape behind the StageScope.catchError helper; do not introduce " +
            "new direct uses.",
    )
    /**
     * A scope that contains the failure of its body and states what the scope
     * reports.
     *
     * The scope decides its own outcome: a body that fails does not fail the
     * stage unless the declared results say so. [buildResult] is what the scope
     * reports when its body fails and [stageResult] is what the stage reports;
     * a scope that declares no stage result inherits the build result, and a
     * scope that declares neither absorbs the failure as
     * [CatchErrorBuildResult.Unstable].
     */
    data class CatchError(
        // typed at authoring. `FailureKind` on Error did the same for error handling;
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
        message = "warnError is compiled into a canonical body-bearing Step: the scope's " +
            "declared contract travels in the block payload and its children travel in the " +
            "block body, so the scope is executed by the canonical body engine and its " +
            "outcome is decided by the scope itself. This spec type remains as the " +
            "authoring shape behind the StageScope.warnError helper; do not introduce " +
            "new direct uses.",
    )
    /**
     * A scope that absorbs the failure of its body and reports the stage as
     * unstable.
     *
     * The two results are fixed by the shape: the scope absorbs the failure as
     * unstable and the stage is reported as unstable, whatever the body did.
     * [message] is what the reader is told, and [catchInterruptions] decides
     * whether an interruption of the body is absorbed too.
     */
    data class WarnError(
        val message: String,
        val catchInterruptions: Boolean = true,
        val steps: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "warnError"
        override val type: String get() = "warnError"
    }

    @Deprecated(
        message = "unstable is rewritten at compile time into " +
            "core.emit.event(StageMarkedUnstable) + core.sh(exit 0) (the rewrite IS implemented " +
            "and runs). This spec type remains as the rewrite's input shape; do not introduce " +
            "new direct uses.",
    )
    /**
     * Marks the stage as unstable and lets the run go on.
     *
     * The message says why. The stage continues, so the steps that follow it
     * still run, and the stage reports an unstable result when it ends.
     */
    data class Unstable(
        val message: String,
    ) : StepSpec {
        override val name: String get() = "unstable"
        override val type: String get() = "unstable"
    }

    /**
     * Hands back the absolute path of the stage workspace as the step's runtime
     * value.
     *
     * The path is observed when the step runs, and a replay reproduces that
     * observation instead of reading the workspace again. [tmp] asks for a
     * temporary directory under the workspace; the codec of the workspace step
     * refuses that flag, so the temporary form has a key of its own.
     */
    data class Pwd(
        val tmp: Boolean = false,
    ) : StepSpec {
        override val name: String get() = "pwd"
        override val type: String get() = "pwd"
    }

    /**
     * Classifies the execution environment as unix-like and hands the answer
     * back as the step's runtime value.
     *
     * The classification is made when the step runs, and a replay reproduces
     * that observation instead of asking the platform again.
     */
    class IsUnix : StepSpec {
        override val name: String get() = "isUnix"
        override val type: String get() = "isUnix"
    }

    /**
     * Names an external pipeline script whose steps would join the body that
     * declares it.
     *
     * The path is resolved against the stage workspace. No registered
     * definition owns the key this shape lowers to, so admission refuses the
     * step instead of running the file.
     *
     * The language does not AUTHOR this shape any more: the DSL retired the
     * `load(path)` form, because a form the run always refuses is not a form
     * an author can use. What stays published is the REPRESENTATION, so a
     * plan composed before the retirement keeps decoding and keeps being
     * refused at admission.
     */
    data class Load(
        val path: String,
    ) : StepSpec {
        override val name: String get() = "load"
        override val type: String get() = "load"
    }

    /**
     * A body the engine polls until its condition holds.
     *
     * The condition is the OUTCOME of the steps the body declares: the engine
     * waits one interval, runs the body, and doubles the interval after every
     * attempt whose condition did not hold, up to the ceiling.
     * [maxBackoffMs] is that ceiling, and it is the deadline of the wait: the
     * attempt that has reached it is the last one, and a condition that still
     * does not hold ends the wait with a timeout failure.
     */
    data class WaitUntilBlock(
        val initialRecurrencePeriod: Long = DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS,
        val maxBackoffMs: Long = DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS,
        val body: List<StepSpec> = emptyList(),
    ) : StepSpec {
        override val name: String get() = "waitUntil"
        override val type: String get() = "waitUntil"
    }

    /**
     * A body whose scope is announced with the pair of bookend events.
     *
     * The shape does NOT decorate the output: the body runs as it would
     * outside the scope, and the captured output travels as the process
     * produced it. What the scope adds is the OBSERVATION — the
     * `TimestampsEntered` and `TimestampsExited` events of the scope.
     */
    data class Timestamps(
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "timestamps"
        override val type: String get() = "timestamps"
    }

    /**
     * Declares a body whose output is decorated with ANSI colour codes.
     *
     * [colorMapName] names the colour map the codes are chosen from, and
     * `xterm` is the default. Only the colour map reaches the compiled step,
     * and no registered definition owns the key it lowers to, so admission
     * refuses the step.
     *
     * The language does not AUTHOR this shape any more: the DSL retired the
     * `ansiColor(...) { ... }` form, because a form the run always refuses is
     * not a form an author can use. What stays published is the
     * REPRESENTATION, so a plan composed before the retirement keeps decoding
     * and keeps being refused at admission.
     */
    data class AnsiColor(
        val colorMapName: String = "xterm",
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "ansiColor"
        override val type: String get() = "ansiColor"
    }

    /**
     * Declares a body that asks for an execution target.
     *
     * The label names the target the body asks for, and a body with no label
     * asks for any target the run has. Only the label reaches the compiled
     * step, and no registered definition owns the key it lowers to, so
     * admission refuses the step.
     *
     * The language does not AUTHOR this shape any more: the DSL retired the
     * `node(...) { ... }` form, because a form the run always refuses is not a
     * form an author can use. What stays published is the REPRESENTATION, so
     * a plan composed before the retirement keeps decoding and keeps being
     * refused at admission.
     *
     * What remains of the agent is the `core.agent` DIRECTIVE of the stage: a
     * plan that carries the requirement has it resolved before its body runs,
     * and it is a declaration of the stage and not a block that wraps one. The
     * language offers no way to author it —the requirement reaches the plan
     * through the generic seam— but the runtime still decides it.
     */
    data class NodeNoOp(
        val label: String? = null,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "node"
        override val type: String get() = "node"
    }

    /**
     * Records a point of the run that a later build can coordinate against.
     *
     * The ordinal orders the milestones of a run and has to rise: a milestone
     * whose ordinal does not exceed the last one reached is recorded as
     * aborted, and the step's outcome is unstable without ending the run. The
     * label names the point for the reader.
     */
    data class Milestone(
        val ordinal: Int,
        val label: String? = null,
    ) : StepSpec {
        override val name: String get() = "milestone"
        override val type: String get() = "milestone"
    }

    /**
     * A body that runs under a deadline.
     *
     * [time] and [unit] state the deadline; the unit is `SECONDS`, `MINUTES`
     * or `HOURS`, or the abbreviated `SECOND`, `MINUTE` or `HOUR`, and any other
     * unit is refused when the pipeline is compiled. A body that outlives the
     * deadline fails the step.
     */
    data class TimeoutBlock(
        val time: Long,
        val unit: String,
        val steps: List<StepSpec>,
    ) : StepSpec {
        override val name: String get() = "timeout"
        override val type: String get() = "timeout"
    }

    /**
     * A body that runs again when it fails.
     *
     * [count] is the number of attempts the body is given, the last one
     * included. Every attempt is a distinct run of the body with a durable
     * identity of its own.
     *
     * [conditions] has no reader: the compiler does not lower it. A failed
     * attempt is retried, and an unstable outcome ends the retry instead.
     */
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
     * runs. declarative structural IR only: the compiler
     * lowers this variant through [dev.rubentxu.pipeline.v2.runtime.CoreLockInput]
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
    /**
     * `slot("name") { ... }` — a step of a body that the plugin gave a NAME.
     *
     * The name is the ADDRESS the plugin will use to ask for this step from its own
     * handler. It is NOT the step's kind: the kind stays what runs, and the name says
     * which of the plugin's own bodies this is.
     *
     * It exists because a body that cannot name its children cannot hold two children of
     * the same kind: the engine would find two of them and refuse to guess. A plugin
     * that chains N actions of one kind would then need 2N kinds of its own, which is an
     * apaño of the fixture and not a pattern a consumer can copy.
     *
     * The wrapper is authoring sugar: the compiler lowers it away and puts the name on
     * the compiled node, where the engine reads it.
     *
     * Source-compatibility note: same classification as [Lock] — adding a case to this
     * sealed hierarchy is binary-compatible and source-additive for consumers holding an
     * exhaustive `when(step)` without an `else` branch.
     */
    data class Slot(
        val slotName: String,
        val step: StepSpec,
    ) : StepSpec {
        init {
            require(slotName.isNotBlank()) { "StepSpec.Slot.slotName must not be blank" }
        }

        override val name: String get() = "slot"
        override val type: String get() = "slot"
    }

    /**
     * A body that holds a named resource while it runs.
     *
     * The resource is a name the run agrees on, and the body runs while this
     * run holds it. [timeoutSeconds] bounds the wait for a resource another
     * run holds, and [reason] is the motive the lock events carry.
     *
     * [skipIfLocked] asks for the body to be skipped and the step to succeed
     * when the resource is already held; asking for that and asking for a
     * timeout at the same time is a contradiction, and the step refuses it as
     * typed input when it runs.
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
     * . declarative structural IR only: the compiler lowers this
     * variant through
     * `CoreInputInput` and the single wire
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
