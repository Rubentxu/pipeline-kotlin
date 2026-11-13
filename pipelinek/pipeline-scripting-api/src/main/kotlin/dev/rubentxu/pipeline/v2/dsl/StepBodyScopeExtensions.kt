package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS

/**
 * The forms of the operations of a body that OMIT a value by default.
 *
 * ## Why these are EXTENSIONS and not members of the contract
 *
 * A value by default lives in ONE place: in the interface or in the class,
 * never in both. The Kotlin compiler refuses the two at once ("More than one
 * function overridden by 'x' declares a default value for 'y'"), and the
 * synthetic `$default` that carries a value by default is bound to the CLASS
 * that declares it: when the value moves to the interface, the synthetic
 * leaves the class and a consumer compiled against the old class fails with
 * `NoSuchMethodError`.
 *
 * The published surface of the stage scope already carries those values, and
 * the published surface does not move. So the CONTRACT ([StepBodyScope])
 * declares every operation with its WHOLE parameter list and without a value
 * by default, and the forms that omit a value are offered HERE.
 *
 * An extension is the form that does not touch the ABI. A MEMBER always wins
 * over an extension, so:
 *
 * - at stage level, `writeFile(file, text)` still resolves to the member that
 *   declares the encoding, and the plan does not move;
 * - inside a body, the member is not applicable — the narrow receiver declares
 *   no value by default — and the extension answers, with the SAME value the
 *   member would have used.
 *
 * ## Why every extension repeats the WHOLE parameter list
 *
 * Because the extension has to answer for EVERY shape the member answers for:
 * `writeFile(file, text)`, `writeFile(file, text, encoding)` and
 * `writeFile(file = ..., text = ..., encoding = ...)` are the same operation,
 * and a body that writes the full form must not change behaviour when the
 * contract moves. One extension per operation, with the same parameter names
 * and the same values by default, answers all of them at once.
 *
 * The parameter NAMES are part of the contract: the named spelling of a
 * Jenkins pipeline (`sh(script = "...")`, `writeFile(file = ..., text = ...)`)
 * resolves by name, and a body that writes it must keep resolving to the same
 * operation.
 *
 * ## The values by default live in two places, and a row holds them together
 *
 * The value an extension supplies is the same value the member of the stage
 * scope supplies, and the two are written in two files. The parity row
 * (`StepBodyScopeParityTest`) writes the same operation at stage level and
 * inside a body and compares the STEPS both produce: a value that drifts
 * makes the row red instead of making the plan differ in silence.
 *
 * The DSL keeps the shape of Jenkins: `writeFile file: 'x', text: 'y'` is
 * `writeFile(file, text)` here, in a body as much as at stage level.
 */

/**
 * DECLARES the failure of the body.
 *
 * @param message the human-readable reason of the failure
 * @param failureKind the typed kind of the failure
 */
fun StepBodyScope.error(message: String, failureKind: FailureKind = FailureKind.USER): Unit =
    error(message, failureKind)

/**
 * DECLARES the shell step that runs a script.
 *
 * @param script the script the shell runs
 * @param isScriptBlock whether the script is a block of shell lines
 * @param returnStdout whether the step asks for the captured standard output
 */
fun StepBodyScope.sh(
    script: String,
    isScriptBlock: Boolean = false,
    returnStdout: Boolean = false,
): Unit = sh(script, isScriptBlock, returnStdout)

/**
 * DECLARES the checkout of a git remote.
 *
 * @param url the git URL
 * @param branch the branch to check out
 * @param credentialsId the credentials entry of the remote
 * @param changelog whether a changelog is written
 * @param poll whether the remote is probed before the checkout
 */
fun StepBodyScope.git(
    url: String,
    branch: String = "master",
    credentialsId: CredentialsId? = null,
    changelog: Boolean = true,
    poll: Boolean = true,
): Unit = git(url, branch, credentialsId, changelog, poll)

/**
 * BUILDS the carrier of a git checkout and returns it.
 *
 * @param url the git URL
 * @param branch the branch to check out
 * @param credentialsId the credentials entry of the remote
 * @param changelog whether a changelog is written
 * @param poll whether the remote is probed before the checkout
 * @param relativeTargetDir the workspace-relative target directory
 */
fun StepBodyScope.scmGit(
    url: String,
    branch: String = "master",
    credentialsId: CredentialsId? = null,
    changelog: Boolean = true,
    poll: Boolean = true,
    relativeTargetDir: String = ".",
): CheckoutSpec = scmGit(url, branch, credentialsId, changelog, poll, relativeTargetDir)

/**
 * DECLARES the archive of the artifacts of the workspace.
 *
 * @param artifacts the patterns of the artifacts
 * @param allowEmptyArchive whether an empty archive is accepted
 * @param excludes the patterns that are excluded
 * @param fingerprint whether the archive is fingerprinted
 * @param name the name of the archive
 */
fun StepBodyScope.archiveArtifacts(
    artifacts: String,
    allowEmptyArchive: Boolean = false,
    excludes: String = "",
    fingerprint: Boolean = false,
    name: String? = null,
): Unit = archiveArtifacts(artifacts, allowEmptyArchive, excludes, fingerprint, name)

/**
 * DECLARES the wrapper that asks the author.
 *
 * @param message the question
 * @param ok the label of the affirmative answer
 * @param submitter who may answer
 * @param id the identifier of the question
 * @param timeoutSeconds the bound on the wait
 * @param block the steps that run after the answer
 */
fun StepBodyScope.input(
    message: String,
    ok: String = "Proceed",
    submitter: String? = null,
    id: String? = null,
    timeoutSeconds: Int? = null,
    block: StepBodyScope.() -> Unit,
): Unit = input(message, ok, submitter, id, timeoutSeconds, block)

/**
 * DECLARES the wrapper that holds a named resource.
 *
 * @param resource the name of the resource
 * @param timeoutSeconds the bound on the wait
 * @param reason the human-readable reason of the wait
 * @param skipIfLocked whether the body runs when the resource is taken
 * @param block the steps that run while the resource is held
 */
fun StepBodyScope.lock(
    resource: String,
    timeoutSeconds: Int? = null,
    reason: String? = null,
    skipIfLocked: Boolean = false,
    block: StepBodyScope.() -> Unit,
): Unit = lock(resource, timeoutSeconds, reason, skipIfLocked, block)

/**
 * DECLARES the wrapper that folds the failure of the body.
 *
 * @param buildResult the result the body must reach
 * @param stageResult the result of the stage
 * @param message the human-readable reason of the fold
 * @param block the steps whose failure is folded
 */
fun StepBodyScope.catchError(
    buildResult: CatchErrorBuildResult,
    stageResult: CatchErrorBuildResult? = null,
    message: String? = null,
    block: StepBodyScope.() -> Unit,
): Unit = catchError(buildResult, stageResult, message, block)

/**
 * The STABLE historical spelling of [catchError], validated at construction.
 *
 * @param buildResult the token of the result the body must reach
 * @param stageResult the token of the result of the stage
 * @param message the human-readable reason of the fold
 * @param block the steps whose failure is folded
 */
fun StepBodyScope.catchError(
    buildResult: String?,
    stageResult: String? = null,
    message: String? = null,
    block: StepBodyScope.() -> Unit,
): Unit = catchError(buildResult, stageResult, message, block)

/**
 * DECLARES the wrapper that folds the failure of the body, with no result
 * named: the fold accepts every result the body may reach.
 *
 * @param block the steps whose failure is folded
 */
fun StepBodyScope.catchError(block: StepBodyScope.() -> Unit): Unit =
    catchError(null, null, null, block)

/**
 * DECLARES the wrapper that warns without failing.
 *
 * @param message the human-readable warning
 * @param catchInterruptions whether an interruption is caught too
 * @param block the steps whose failure is warned
 */
fun StepBodyScope.warnError(
    message: String,
    catchInterruptions: Boolean = true,
    block: StepBodyScope.() -> Unit,
): Unit = warnError(message, catchInterruptions, block)

/**
 * DECLARES the wrapper that waits for a condition.
 *
 * @param initialRecurrencePeriod the first period of the poll
 * @param maxBackoffMs the bound of the backoff
 * @param body the condition that is polled
 */
fun StepBodyScope.waitUntil(
    initialRecurrencePeriod: Long = DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS,
    maxBackoffMs: Long = DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS,
    body: StepBodyScope.() -> Unit,
): Unit = waitUntil(initialRecurrencePeriod, maxBackoffMs, body)

/**
 * DECLARES the publication of the reports of the workspace.
 *
 * @param name the name of the publication
 * @param reportDir the workspace-relative directory of the reports
 * @param reportFiles the patterns of the files that are published
 * @param keepAll whether every run keeps its reports
 * @param allowMissing whether a missing report is accepted
 * @param escapeUnderscores whether the underscores are escaped
 */
fun StepBodyScope.publishHTML(
    name: String,
    reportDir: String,
    reportFiles: String = "**",
    keepAll: Boolean = false,
    allowMissing: Boolean = false,
    escapeUnderscores: Boolean = false,
): Unit = publishHTML(name, reportDir, reportFiles, keepAll, allowMissing, escapeUnderscores)

/**
 * DECLARES the cleaning of the workspace.
 *
 * @param deleteDirs whether the directories are removed too
 * @param patterns the patterns of the files that are removed
 */
fun StepBodyScope.cleanWs(
    deleteDirs: Boolean = true,
    patterns: List<String>? = null,
): Unit = cleanWs(deleteDirs, patterns)

/**
 * DECLARES the removal of a directory of the workspace.
 *
 * @param path the workspace-relative path
 */
fun StepBodyScope.deleteDir(path: String = "."): Unit = deleteDir(path)

/**
 * DECLARES the marker of the progress of the body.
 *
 * @param ordinal the ordinal of the marker
 * @param label the label of the marker
 */
fun StepBodyScope.milestone(ordinal: Int, label: String? = null): Unit = milestone(ordinal, label)

/**
 * DECLARES the stash of the files of the workspace.
 *
 * @param name the name of the stash
 * @param includes the patterns of the files that are stashed
 * @param excludes the patterns of the files that are not stashed
 */
fun StepBodyScope.stash(name: String, includes: String, excludes: String = ""): Unit =
    stash(name, includes, excludes)

/**
 * DECLARES the restore of a stash.
 *
 * @param name the name of the stash
 * @param into the workspace-relative directory the files land in
 */
fun StepBodyScope.unstash(name: String, into: String? = null): Unit = unstash(name, into)

/**
 * READS the current directory of the workspace.
 *
 * @param tmp whether the temporary directory is read instead
 */
fun StepBodyScope.pwd(tmp: Boolean = false): String = pwd(tmp)

/**
 * DECLARES the read of a file of the workspace.
 *
 * @param file the workspace-relative path
 * @param encoding the encoding of the content
 */
fun StepBodyScope.readFile(file: String, encoding: String = "UTF-8"): Unit = readFile(file, encoding)

/**
 * DECLARES the write of a file of the workspace.
 *
 * @param file the workspace-relative path
 * @param text the content to write
 * @param encoding the encoding of the content
 */
fun StepBodyScope.writeFile(file: String, text: String, encoding: String = "UTF-8"): Unit =
    writeFile(file, text, encoding)

/**
 * The REFUSAL of a retry with a delay.
 *
 * The runtime does not offer the retry with a delay, and the scope refuses it
 * where it is written. The body refuses it the same way: the extension
 * carries the refusal, so a body that asks for it fails with the same
 * diagnostic a stage fails with, and not with a missing symbol.
 *
 * @param count the number of attempts
 * @param delaySeconds the delay between the attempts
 */
fun StepBodyScope.retry(count: Int, delaySeconds: Long? = null): Nothing = retry(count, delaySeconds)
