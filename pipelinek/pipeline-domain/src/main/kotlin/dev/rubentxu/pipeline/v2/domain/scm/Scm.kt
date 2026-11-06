package dev.rubentxu.pipeline.v2.domain.scm

import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Sealed root for SCM types.
 * Only GitScm is defined; SubversionScm/GithubScm deferred to
*/
sealed interface Scm

/**
 * Git SCM implementation.
 * Maps to Jenkins' checkout scmGit / git steps.
 * @param url Repository URL (https or file)
 * @param branch Branch to checkout (default master)
 * @param credentialsId Optional credentials ID for private repos
 * @param changelog Whether to append to changelog.txt (default true)
 * @param poll Whether to poll for changes (default true) — no daemon, synchronous ls-remote
 * @param relativeTargetDir Workspace-relative checkout directory (default ".")
*/
data class GitScm(
    val url: String,
    val branch: String = "master",
    val credentialsId: CredentialsId? = null,
    val changelog: Boolean = true,
    val poll: Boolean = true,
    val relativeTargetDir: String = ".",
) : Scm

/**
 * Wrapper for checkout step spec.
*/
data class CheckoutSpec(val scm: Scm)
