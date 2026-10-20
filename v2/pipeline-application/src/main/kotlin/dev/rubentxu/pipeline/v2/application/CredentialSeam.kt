package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider

/**
 * The credential ports of a run: the provider (what a Step may look up) and
 * the executor (what injects a credential into a `withCredentials` block).
 *
 * Both are null when the operator configured no store, and that is a state,
 * not a failure: the run simply has nowhere to look.
 */
internal data class CredentialSeam(
    val provider: CredentialProvider?,
    val executor: WithCredentialsExecutor?,
)
