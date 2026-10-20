package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input for the `scm-git.checkout` Step (LFC-2E2 / F5.1).
 *
 * Kept deliberately narrow: the Step contract only carries the
 * configuration the runtime needs to invoke [GitCheckoutExecutor]. The
 * executor itself owns the policy (idempotent SHA-equality, ls-remote,
 * changelog, credential redaction) and is unchanged.
 *
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
 *
 * Carries the resolved commit SHA and the local checkout path so
 * downstream Steps can read or branch from it without re-running the
 * SCM probe.
 */
data class GitCheckoutOutput(
    val resolvedSha: String,
    val localPath: String,
    val wasCloned: Boolean,
    val credentialApplied: Boolean,
)

/**
 * Convenience factory mapping the typed input to the domain SCM carrier.
 */
fun GitCheckoutInput.toDomainCredentialsId(): dev.rubentxu.pipeline.v2.domain.CredentialsId? =
    credentialsRef?.let { dev.rubentxu.pipeline.v2.domain.CredentialsId(it) }

/**
 * Public StepKey for the SCM/Git checkout family (LFC-2E2 / F5.1).
 */
object ScmGitCheckoutKey {
    val VALUE: PluginStepId = PluginStepId("scm-git.checkout")
}
