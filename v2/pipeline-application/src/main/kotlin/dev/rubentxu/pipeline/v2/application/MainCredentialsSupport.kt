package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.LocalFileMaterialization
import java.nio.file.Path
import java.nio.file.Paths

/**
 * T12 / WU-LPR-103: compose the credential injection stack ONCE, shared by the
 * durable (--db) and in-memory (default `pipeline run`) branches.
 *
 * Design: Option A — env var PIPELINE_CREDENTIALS_STORE for store path,
 * PIPELINE_STORE_PASSPHRASE for passphrase. Default path is
 * <controlDirRoot>/../credentials.bin (sibling to the journal db).
 *
 * Behavior:
 * - Store file does not exist -> null (user runs `pipeline credentials add`
 *   first; no error at startup)
 * - Store file exists + passphrase available -> WithCredentialsExecutor
 * - Store file exists + passphrase wrong/missing -> fail fast with actionable error
 */
internal fun composeWithCredentialsExecutor(controlDirRoot: Path): WithCredentialsExecutor? {
    val credentialsStorePath: Path = System.getenv("PIPELINE_CREDENTIALS_STORE")?.let { Paths.get(it) }
        ?: controlDirRoot.parent.resolve("credentials.bin")

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
    return WithCredentialsExecutor(credentialProvider, credentialMaterialization, SystemClock())
}
