package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StepEmitter
import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * Ergonomic Kotlin DSL extension for the `scm-git.checkout` Step
 * .
 * The façade is intentionally narrow:
 *   - constructs the typed [GitScm] carrier from positional/named
 *     arguments (no Map<String, Any?> smuggling);
 *   - encodes it with the plugin's own [GitCheckoutInputCodec];
 *   - lowers to the generic [StageScope.registryStep] primitive that
 *     core provides (no plugin-specific case in the compiler).
 * The extension does NOT resolve the runtime registry, does NOT execute
 * the handler, does NOT inspect any global mutable state.
 *
 * This is the route of the CONTRACT: it applies in every scope that hosts steps, and the
 * historic route on `StageScope` —the one a published script and a precompiled plugin name—
 * delegates to it, so the two declare the SAME step.
 * @param url git URL (https://, git@, file:// all supported).
 * @param branch branch to check out (default `master`).
 * @param credentialsRef typed reference to a credentials entry in the
 *   SecretStore (NEVER the secret bytes themselves).
 * @param changelog whether to write a changelog between previous and
 *   resolved SHA.
 * @param poll whether to ls-remote probe before deciding clone/fetch/reset.
 * @param relativeTargetDir workspace-relative checkout directory.
*/
fun <B : StepEmitter<B>> StepEmitter<B>.scmGitCheckout(
    url: String,
    branch: String = "master",
    credentialsRef: String? = null,
    changelog: Boolean = true,
    poll: Boolean = true,
    relativeTargetDir: String = ".",
) {
    val input = GitScm(
        url = url,
        branch = branch,
        credentialsId = credentialsRef?.let { CredentialsId.from(it) },
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
 * The HISTORIC route of `scmGitCheckout`, and the one a published script and a
 * precompiled plugin name.
 *
 * The receiver of a Kotlin extension is the FIRST parameter of its JVM
 * signature, so the receiver of this function IS part of the descriptor a
 * compiled consumer names. This route keeps the receiver `StageScope` and the
 * descriptor it had, and it delegates to the route of the contract: the two
 * declare the SAME step, and the one that runs is the one of the contract.
 */
fun StageScope.scmGitCheckout(
    url: String,
    branch: String = "master",
    credentialsRef: String? = null,
    changelog: Boolean = true,
    poll: Boolean = true,
    relativeTargetDir: String = ".",
) {
    val emitter: StepEmitter<StageScope> = this
    emitter.scmGitCheckout(
        url = url,
        branch = branch,
        credentialsRef = credentialsRef,
        changelog = changelog,
        poll = poll,
        relativeTargetDir = relativeTargetDir,
    )
}

/**
 * The canonical plugin StepKey for scm-git.checkout, re-exported here
 * so this façade is the only thing a `.pipeline.kts` needs to import.
*/
fun scmGitCheckoutStepKey(): PluginStepId = ScmGitCheckoutKey.VALUE
