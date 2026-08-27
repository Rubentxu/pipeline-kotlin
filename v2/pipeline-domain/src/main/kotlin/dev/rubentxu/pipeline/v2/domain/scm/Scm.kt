package dev.rubentxu.pipeline.v2.domain.scm

import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Sealed root for SCM types.
 * L5: only GitScm is defined; SubversionScm/GithubScm deferred to ML-R5.1
 */
sealed interface Scm

/**
 * Git SCM implementation.
 * Maps to Jenkins' checkout scmGit / git steps.
 *
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

/**
 * Typed carrier for secret handle reference.
 * INV-L5-CR-005: credentials carried as typed refs, never Map<String,String>.
 *
 * @param id The CredentialsId referencing the secret in SecretStore
 * @param kind Optional kind hint ("string", "usernamePassword", etc.)
 */
data class SecretHandleRef(
    val id: CredentialsId,
    val kind: String? = null,
)

/**
 * Git credentials — two-channel auth.
 * INV-L5-CR-005: typed carriers only, no Map<String,String>.
 * INV-L5-CR-004: credentials NEVER enter argv — temp files + GIT_CONFIG_GLOBAL only.
 *
 * @param string SecretHandleRef for API token (string channel)
 * @param user SecretHandleRef for username (usernamePassword channel)
 * @param pass SecretHandleRef for password (usernamePassword channel)
 */
data class GitCredentials(
    val string: SecretHandleRef? = null,
    val user: SecretHandleRef? = null,
    val pass: SecretHandleRef? = null,
)
