package dev.rubentxu.pipeline.model.scm

/**
 * Represents a generic Source Control Management (SCM) configuration.
 */
sealed class Scm {
    /** The repository URL. */
    abstract val url: String

    /** The credentials ID for accessing the repository, if required. */
    abstract val credentialsId: String?
}

/**
 * Represents a Git SCM configuration.
 *
 * @property url The Git repository URL.
 * @property branch The branch to use (default is "main").
 * @property credentialsId The credentials ID for repository access.
 * @property doGenerateSubmoduleConfigurations Whether to generate submodule configurations.
 * @property extensions List of Git-specific extensions.
 */
data class GitScm(
    override val url: String,
    val branch: String = "main",
    override val credentialsId: String? = null,
    val doGenerateSubmoduleConfigurations: Boolean = false,
    val extensions: List<GitExtension> = emptyList()
) : Scm()

/**
 * Base class for Git-specific SCM extensions.
 */
sealed class GitExtension

/**
 * Extension for configuring clone options in Git.
 *
 * @property shallow Whether to perform a shallow clone.
 * @property noTags Whether to exclude tags.
 * @property reference Reference repository path.
 * @property timeout Timeout in minutes.
 */
data class CloneOption(
    val shallow: Boolean = false,
    val noTags: Boolean = false,
    val reference: String? = null,
    val timeout: Int = 10
) : GitExtension()

/**
 * Extension for configuring checkout options in Git.
 *
 * @property timeout Timeout in minutes.
 */
data class CheckoutOption(
    val timeout: Int = 10
) : GitExtension()

/**
 * Extension for configuring submodule options in Git.
 *
 * @property disableSubmodules Whether to disable submodules.
 * @property recursiveSubmodules Whether to update submodules recursively.
 * @property trackingSubmodules Whether to track submodules.
 * @property reference Reference repository path.
 * @property timeout Timeout in minutes.
 */
data class SubmoduleOption(
    val disableSubmodules: Boolean = false,
    val recursiveSubmodules: Boolean = false,
    val trackingSubmodules: Boolean = false,
    val reference: String? = null,
    val timeout: Int = 10
) : GitExtension()

/**
 * Extension for specifying user identity in Git operations.
 *
 * @property name The user's name.
 * @property email The user's email.
 */
data class UserIdentity(
    val name: String,
    val email: String
) : GitExtension()

/**
 * Extension for enabling Git LFS (Large File Storage).
 *
 * @property gitLfsExtension Whether to enable Git LFS.
 */
data class GitLfsExtension(
    val gitLfsExtension: Boolean = true
) : GitExtension()

/**
 * Extension for wiping the workspace before checkout.
 *
 * @property wipeWorkspace Whether to wipe the workspace.
 */
data class WipeWorkspace(
    val wipeWorkspace: Boolean = true
) : GitExtension()