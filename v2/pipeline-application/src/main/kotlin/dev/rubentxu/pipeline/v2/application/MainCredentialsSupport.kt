package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.local.CredentialsStorePath
import dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.LocalFileMaterialization
import java.nio.file.Path

/**
 * T12 / WU-LPR-103: compose the credential injection stack ONCE, shared by the
 * durable (--db) and in-memory (default `pipeline run`) branches.
 *
 * Design: the store location comes from CredentialsStorePath, the ONE authority:
 * PIPELINE_CREDENTIALS_STORE if set, else ~/.pipeline/credentials.bin.
 * PIPELINE_STORE_PASSPHRASE supplies the passphrase.
 *
 * Behavior:
 * - Store file does not exist -> null (user runs `pipeline credentials add`
 *   first; no error at startup)
 * - Store file exists + passphrase available -> WithCredentialsExecutor
 * - Store file exists + passphrase wrong/missing -> fail fast with actionable error
 */
internal fun composeWithCredentialsExecutor(
    redactionRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
): WithCredentialsExecutor? {
    // [controlDirRoot] is deliberately NOT a parameter of this function. It used to
    // be (`controlDirRoot.parent.resolve("credentials.bin")`), which meant the
    // documented `credentials add` -> `run` flow could not find the store it had
    // just written. Removing it from the signature is what makes that impossible
    // to reintroduce by accident; see CredentialsStorePath's KDoc.
    val credentialsStorePath: Path = CredentialsStorePath.fromEnvironment(System.getenv())

    if (!credentialsStorePath.toFile().exists()) return null

    val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore = try {
        val passphraseChars = PassphraseResolver.resolve()
        // Do NOT wipe passphraseChars here — LocalSecretStore stores the
        // same CharArray reference and would lose its own copy. The store
        // zeros the passphrase in its own close().
        LocalSecretStore(credentialsStorePath, passphraseChars)
    } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
        System.err.println("Error: ${e.message}")
        System.err.println("Hint: set PIPELINE_STORE_PASSPHRASE env var, or run interactively in a TTY.")
        System.exit(3)
        return null // unreachable
    } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
        System.err.println("Error: $e.message")
        System.err.println("Hint: the passphrase does not match. Check PIPELINE_STORE_PASSPHRASE.")
        System.exit(3)
        return null // unreachable
    } catch (e: LocalSecretStore.SecretStoreTamperException) {
        System.err.println("Error: credentials store tampered: ${e.message}")
        System.exit(4)
        return null // unreachable
    }

    val credentialProvider: dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider = LocalCredentialProvider(secretStore)
    val credentialMaterialization: dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization =
        LocalFileMaterialization(CredentialMaterializer(secretStore))
    return WithCredentialsExecutor(credentialProvider, credentialMaterialization, SystemClock(), redactionRegistry)
}
