package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * Ergonomic Kotlin DSL extension for the `scm-git.checkout` Step
 * (LFC-2E2 / F5.1).
 *
 * The façade is intentionally narrow:
 *   - constructs the typed [GitCheckoutInput] from positional/named
 *     arguments (no Map<String, Any?> smuggling);
 *   - encodes it with the plugin's own [GitCheckoutInputCodec];
 *   - lowers to the generic [StageScope.registryStep] primitive that
 *     core provides (no plugin-specific case in the compiler).
 *
 * The extension does NOT resolve the runtime registry, does NOT execute
 * the handler, does NOT inspect any global mutable state.
 *
 * @param url git URL (https://, git@, file:// all supported).
 * @param branch branch to check out (default `master`).
 * @param credentialsRef typed reference to a credentials entry in the
 *   SecretStore (NEVER the secret bytes themselves).
 * @param changelog whether to write a changelog between previous and
 *   resolved SHA.
 * @param poll whether to ls-remote probe before deciding clone/fetch/reset.
 * @param relativeTargetDir workspace-relative checkout directory.
 */
fun StageScope.scmGitCheckout(
    url: String,
    branch: String = "master",
    credentialsRef: String? = null,
    changelog: Boolean = true,
    poll: Boolean = true,
    relativeTargetDir: String = ".",
) {
    val input = GitCheckoutInput(
        url = url,
        branch = branch,
        credentialsRef = credentialsRef,
        changelog = changelog,
        poll = poll,
        relativeTargetDir = relativeTargetDir,
    )
    val encoded: EncodedStepValue = GitCheckoutInputCodec.encode(input)
    registryStep(
        stepKey = scmGitCheckoutStepKey(),
        encodedInput = encoded,
    )
}

/**
 * The canonical plugin StepKey for scm-git.checkout, re-exported here
 * so this façade is the only thing a `.pipeline.kts` needs to import.
 */
fun scmGitCheckoutStepKey(): PluginStepId = ScmGitCheckoutKey.VALUE
