package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.local.CredentialsStorePath
import dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.LocalFileMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import java.nio.file.Path

/**
 * Compose the credential injection stack ONCE, shared by the durable (--db)
 * and in-memory (default `pipeline run`) branches.
 *
 * T12 / WU-LPR-103: the store location comes from [CredentialsStorePath], the
 * ONE authority: PIPELINE_CREDENTIALS_STORE if set, else ~/.pipeline/credentials.bin.
 * PIPELINE_STORE_PASSPHRASE supplies the passphrase.
 *
 * This decision used to be written TWICE: the in-memory branch called
 * `composeWithCredentialsExecutor`, and the durable branch carried an inline
 * copy of the same body. The two copies even disagreed on a detail nobody had
 * noticed — the in-memory one opened the store with a fresh `SystemClock()`
 * while the durable one used the run's clock. Two copies of a decision is two
 * chances to disagree, and the disagreement would have been invisible. One
 * function, one clock, one store.
 *
 * Behavior:
 * - Store file does not exist -> null (user runs `pipeline credentials add`
 *   first; no error at startup)
 * - Store file exists + passphrase available -> provider and executor
 * - Store file exists + passphrase wrong/missing -> fail fast with actionable error
 */
internal fun composeCredentialSeam(
    redactionRegistry: SecretPatternRegistry?,
    clock: Clock,
): CredentialSeam {
    // [controlDirRoot] is deliberately NOT a parameter of this function. It used to
    // be (`controlDirRoot.parent.resolve("credentials.bin")`), which meant the
    // documented `credentials add` -> `run` flow could not find the store it had
    // just written. Removing it from the signature is what makes that impossible
    // to reintroduce by accident; see CredentialsStorePath's KDoc.
    val credentialsStorePath: Path = CredentialsStorePath.fromEnvironment(System.getenv())

    if (!credentialsStorePath.toFile().exists()) {
        return CredentialSeam(provider = null, executor = null)
    }

    val secretStore: SecretStore = try {
        val passphraseChars = PassphraseResolver.resolve()
        // Do NOT wipe passphraseChars here — LocalSecretStore stores the
        // same CharArray reference and would lose its own copy. The store
        // zeros the passphrase in its own close().
        LocalSecretStore(credentialsStorePath, passphraseChars)
    } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
        System.err.println("Error: ${e.message}")
        System.err.println("Hint: set PIPELINE_STORE_PASSPHRASE env var, or run interactively in a TTY.")
        System.exit(3)
        error("unreachable")
    } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
        System.err.println("Error: $e.message")
        System.err.println("Hint: the passphrase does not match. Check PIPELINE_STORE_PASSPHRASE.")
        System.exit(3)
        error("unreachable")
    } catch (e: LocalSecretStore.SecretStoreTamperException) {
        System.err.println("Error: credentials store tampered: ${e.message}")
        System.exit(4)
        error("unreachable")
    }

    // H0 composition root: construct credential ports and executor
    // LocalSecretStore (existing) -> LocalCredentialProvider -> CredentialMaterializer
    // -> LocalFileMaterialization -> WithCredentialsExecutor
    val provider: CredentialProvider = LocalCredentialProvider(secretStore)
    val materialization: CredentialMaterialization = LocalFileMaterialization(CredentialMaterializer(secretStore))
    val executor = WithCredentialsExecutor(provider, materialization, clock, redactionRegistry)
    return CredentialSeam(provider = provider, executor = executor)
}
