package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.Scm

/**
 * The OPERATIONS a body of steps may declare: the steps that act on their
 * own, and the steps that touch the workspace.
 *
 * ## Why this type exists
 *
 * The contract of the receiver of a body ([StepBodyScope]) declares one
 * member per operation, and a member per operation is a wide contract. The
 * stage scope answers the same width the same way: it is split into
 * `StageScopeCore`, `StageScopeTopSteps` and `StageScope`, and each part
 * stays inside the width the `TooManyFunctions` rule allows the facades of
 * this product.
 *
 * This part is the OPERATIONS: the counterpart of `StageScopeCore` and
 * `StageScopeTopSteps` together, without the configuration of the stage.
 *
 * ## Why the members here have NO value by default
 *
 * A value by default lives in ONE place: in the interface or in the class,
 * never in both. The Kotlin compiler refuses the two at once ("More than one
 * function overridden by 'x' declares a default value for 'y'"), and the
 * synthetic `$default` that carries a value by default is bound to the CLASS
 * that declares it: when the value moves to the interface, the synthetic
 * leaves the class and a consumer compiled against the old class fails with
 * `NoSuchMethodError`.
 *
 * So the values by default STAY in the concrete scopes, where the published
 * surface already carries them, and the forms that OMIT them are offered as
 * extensions over the contract: the package [dev.rubentxu.pipeline.v2.dsl]
 * declares them.
 */
interface StepBodyScopeOperations {

    // ================================================
    // The CORE operations
    // ================================================

    /** DECLARES the echo step. */
    fun echo(text: String)

    /** DECLARES the shell step that runs a command. */
    fun sh(command: String)

    /**
     * DECLARES the shell step that runs a script.
     *
     * @param script the shell lines to run
     * @param isScriptBlock whether the lines are a script block
     * @param returnStdout whether the captured output comes back to the script
     */
    fun sh(script: String, isScriptBlock: Boolean, returnStdout: Boolean)

    /**
     * DECLARES the failure of the body.
     *
     * @param message the human-readable reason of the failure
     * @param failureKind the typed kind of the failure
     */
    fun error(message: String, failureKind: FailureKind)

    /**
     * The STABLE historical spelling of [error], validated at construction.
     *
     * @param message the human-readable reason of the failure
     * @param failureKind the token of the kind of the failure
     */
    fun error(message: String, failureKind: String)

    /** DECLARES the step that sleeps. */
    fun sleep(seconds: Long)

    /** DECLARES the checkout of a source. */
    fun checkout(scm: Scm)

    /** DECLARES the checkout of a source, from its spec. */
    fun checkout(spec: CheckoutSpec)

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
    fun scmGit(
        url: String,
        branch: String,
        credentialsId: CredentialsId?,
        changelog: Boolean,
        poll: Boolean,
        relativeTargetDir: String,
    ): CheckoutSpec

    /**
     * DECLARES the checkout of a git remote.
     *
     * @param url the git URL
     * @param branch the branch to check out
     * @param credentialsId the credentials entry of the remote
     * @param changelog whether a changelog is written
     * @param poll whether the remote is probed before the checkout
     */
    fun git(
        url: String,
        branch: String,
        credentialsId: CredentialsId?,
        changelog: Boolean,
        poll: Boolean,
    )

    /**
     * READS the value of an environment variable.
     *
     * @param name the name of the variable
     */
    fun env(name: String): String?

    /** DECLARES the step that marks the body as unstable. */
    fun unstable(message: String)

    /**
     * READS the current directory of the workspace.
     *
     * @param tmp whether the temporary directory is read instead
     */
    fun pwd(tmp: Boolean): String

    /** READS whether the platform is unix. */
    fun isUnix(): Boolean

    // ================================================
    // The STEPS that touch the workspace
    // ================================================

    /**
     * DECLARES the write of a file of the workspace.
     *
     * @param file the workspace-relative path
     * @param text the content to write
     * @param encoding the encoding of the content
     */
    fun writeFile(file: String, text: String, encoding: String)

    /**
     * DECLARES the read of a file of the workspace.
     *
     * @param file the workspace-relative path
     * @param encoding the encoding of the content
     */
    fun readFile(file: String, encoding: String)

    /** DECLARES the check of the existence of a file of the workspace. */
    fun fileExists(file: String)

    /** DECLARES the read of a parameter of the pipeline. */
    fun param(name: String)

    /** DECLARES the query of the artifacts of the pipeline. */
    fun artifactQuery(name: String)

    /**
     * DECLARES the archive of the artifacts of the workspace.
     *
     * @param artifacts the patterns of the artifacts
     * @param allowEmptyArchive whether an empty archive is accepted
     * @param excludes the patterns that are excluded
     * @param fingerprint whether the archive is fingerprinted
     * @param name the name of the archive
     */
    fun archiveArtifacts(
        artifacts: String,
        allowEmptyArchive: Boolean,
        excludes: String,
        fingerprint: Boolean,
        name: String?,
    )

    /**
     * DECLARES the removal of a directory of the workspace.
     *
     * @param path the workspace-relative path
     */
    fun deleteDir(path: String)

    /**
     * DECLARES the cleaning of the workspace.
     *
     * @param deleteDirs whether the directories are removed too
     * @param patterns the patterns of the files that are removed
     */
    fun cleanWs(deleteDirs: Boolean, patterns: List<String>?)

    /**
     * DECLARES the cleaning of the workspace.
     *
     * @param deleteDirs whether the directories are removed too
     * @param patterns the patterns of the files that are removed
     */
    fun cleanWs(deleteDirs: Boolean, vararg patterns: String)

    /**
     * DECLARES the marker of the progress of the body.
     *
     * @param ordinal the ordinal of the marker
     * @param label the label of the marker
     */
    fun milestone(ordinal: Int, label: String?)

    /**
     * DECLARES the stash of the files of the workspace.
     *
     * @param name the name of the stash
     * @param includes the patterns of the files that are stashed
     * @param excludes the patterns of the files that are not stashed
     */
    fun stash(name: String, includes: String, excludes: String)

    /**
     * DECLARES the restore of a stash.
     *
     * @param name the name of the stash
     * @param into the workspace-relative directory the files land in
     */
    fun unstash(name: String, into: String?)

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
    fun publishHTML(
        name: String,
        reportDir: String,
        reportFiles: String,
        keepAll: Boolean,
        allowMissing: Boolean,
        escapeUnderscores: Boolean,
    )
}
