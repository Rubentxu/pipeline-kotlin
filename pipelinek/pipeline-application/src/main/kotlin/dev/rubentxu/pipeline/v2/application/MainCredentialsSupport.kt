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
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import java.nio.file.Path

/**
 * Compose the credential injection stack ONCE, shared by the durable (--db)
 * and in-memory (default `pipeline run`) branches.
 *
 * T12: the store location comes from [CredentialsStorePath], the
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
 * - The run declares no credential binding -> null (no store is opened, and no
 *   passphrase is asked for: a run that wants no secret does not have to unlock
 *   the place where secrets live)
 * - The run declares a binding and the store file does not exist -> null (the
 *   user runs `pipeline credentials add` first; no error at startup)
 * - The run declares a binding, the store file exists and the passphrase is
 *   available -> provider and executor
 * - The run declares a binding, the store file exists and the passphrase is
 *   wrong/missing -> fail fast with actionable error
 *
 * @param pipelineSpec the declaration of the run, which says whether it binds a
 *   credential. The need for the store is a fact about the RUN, and asking the
 *   machine whether its store file exists answered a different question: it made
 *   every run on a machine that had ever stored a secret ask for a passphrase,
 *   including the runs that bind nothing. The caller therefore hands over the
 *   declaration and lets this function decide.
 */
internal fun composeCredentialSeam(
    redactionRegistry: SecretPatternRegistry?,
    clock: Clock,
    pipelineSpec: PipelineSpec?,
): CredentialSeam {
    // [controlDirRoot] is deliberately NOT a parameter of this function. It used to
    // be (`controlDirRoot.parent.resolve("credentials.bin")`), which meant the
    // documented `credentials add` -> `run` flow could not find the store it had
    // just written. Removing it from the signature is what makes that impossible
    // to reintroduce by accident; see CredentialsStorePath's KDoc.
    val credentialsStorePath: Path = CredentialsStorePath.fromEnvironment(System.getenv())

    val storeNeeded = pipelineSpec?.declaresCredentialBinding() == true
    if (!storeNeeded || !credentialsStorePath.toFile().exists()) {
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
        // The refusal UNWINDS instead of ending the process: a branch that owns a lease and an
        // event store has to give them back before the process ends, and ending here would skip
        // that release. The branch that owns nothing turns the refusal into the exit.
        throw CommandRefused(exitCode = 3)
    } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
        System.err.println("Error: $e.message")
        System.err.println("Hint: check PIPELINE_STORE_PASSPHRASE.")
        throw CommandRefused(exitCode = 3)
    } catch (e: LocalSecretStore.SecretStoreTamperException) {
        System.err.println("Error: credentials store tampered: ${e.message}")
        throw CommandRefused(exitCode = 4)
    }

    // Composition root: construct credential ports and executor
    // LocalSecretStore (existing) -> LocalCredentialProvider -> CredentialMaterializer
    // -> LocalFileMaterialization -> WithCredentialsExecutor
    val provider: CredentialProvider = LocalCredentialProvider(secretStore)
    val materialization: CredentialMaterialization = LocalFileMaterialization(CredentialMaterializer(secretStore))
    val executor = WithCredentialsExecutor(provider, materialization, clock, redactionRegistry)
    // The process opened the store and wrote the credential material to disk, so
    // the process is what gives both back. The materializer removes the files
    // it wrote and the provider closes the store; without this the material of
    // every run stayed on disk under the shared temporary directory, which is
    // where a secret outlives the run that needed it.
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                closeQuietly(materialization)
                closeQuietly(provider)
            },
            "pipelinek-credentials-cleanup",
        ),
    )
    return CredentialSeam(provider = provider, executor = executor)
}

/**
 * Release a resource on the way out, ignoring whatever it throws.
 *
 * A failure while releasing must not turn a successful run into a failed one,
 * so the exception is swallowed on this path.
 */
private fun closeQuietly(resource: AutoCloseable) {
    try {
        resource.close()
    } catch (_: Exception) {
        // Shutdown path.
    }
}
