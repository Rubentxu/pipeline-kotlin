package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Typed input for the `scm-git.checkout` Step
 * Kept deliberately narrow: the Step contract only carries the
 * configuration the runtime needs to invoke [GitCheckoutExecutor]. The
 * executor itself owns the policy (idempotent SHA-equality, ls-remote,
 * changelog, credential redaction) and is unchanged.
 * `credentialsRef` is a typed reference to a credentials entry in the
 * [dev.rubentxu.pipeline.v2.credentials.api.SecretStore]; the actual
 * secret bytes are NEVER embedded in the encoded payload (typed carrier
 * discipline from SCM domain).
*/
data class GitCheckoutInput(
    val url: String,
    val branch: String = "master",
    val credentialsRef: String? = null,
    val changelog: Boolean = true,
    val poll: Boolean = true,
    val relativeTargetDir: String = ".",
)

/**
 * Typed output for the `scm-git.checkout` Step.
 * Carries the resolved commit SHA and the local checkout path so
 * downstream Steps can read or branch from it without re-running the
 * SCM probe.
*/
data class GitCheckoutOutput(
    val resolvedSha: String,
    val localPath: String,
    val wasCloned: Boolean,
    /**
     * Whether a credential was applied to this checkout.
     *
     * It says what HAPPENED and not what was declared: a checkout whose remote
     * cannot authenticate with the credential it declared applied none, and a
     * checkout that applied an SSH key applied one. A reader of this output
     * decides what to do next from it, so it may not claim a credential that
     * was never handed to git.
    */
    val credentialApplied: Boolean,
    /**
     * Whether the checkout asked the remote for the head of the declared branch.
     *
     * FALSE means the remote was NOT consulted: the published [resolvedSha] is
     * then the commit the LOCAL working tree held, and a reader that took it for
     * a remote fact would be reading a fact nobody established. TRUE means the
     * remote was asked and the published commit is the one the tree holds after
     * materialising what the remote declared.
     *
     * A payload encoded before this field existed comes from a checkout that
     * ALWAYS asked the remote, so that is what the decoder assumes for it.
    */
    val remotePolled: Boolean,
)

/**
 * Convenience factory mapping the typed input to the domain SCM carrier.
*/
fun GitCheckoutInput.toDomainCredentialsId(): dev.rubentxu.pipeline.v2.domain.CredentialsId? =
    credentialsRef?.let { dev.rubentxu.pipeline.v2.domain.CredentialsId(it) }

/**
 * Public StepKey for the SCM/Git checkout family
*/
object ScmGitCheckoutKey {
    val VALUE: PluginStepId = PluginStepId("scm-git.checkout")
}
