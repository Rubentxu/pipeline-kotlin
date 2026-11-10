package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePosixPermissionsException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStorePassphraseMismatchException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.credentials.local.CredentialsStorePath
import dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * What opening the local credential store for the service produced.
 */
internal sealed interface ServiceCredentialAccess {

    /** The store is open and its passphrase was accepted. The service owns [provider]. */
    data class Opened(val provider: CredentialProvider) : ServiceCredentialAccess

    /** The store cannot be used. [detail] is fixed words: nothing in it was read from the store. */
    data class Refused(val detail: String) : ServiceCredentialAccess
}

/**
 * Opens the local credential store the service hands credentials from.
 *
 * ## Why the passphrase is checked HERE
 *
 * A store only proves that a passphrase is wrong when something is read from it. A service that
 * waited for the first request would start, answer that it is alive, and refuse every credential
 * of every run for the rest of its life. Checking at the start makes the mistake the operator's,
 * at the moment they can fix it.
 *
 * ## Why a missing store refuses
 *
 * The authorizations of the service name credentials, and a service that holds none of them
 * cannot keep what its authorizations promise. A store created later is not seen by a service that
 * is already running, so starting without it would only postpone the same refusal to the run.
 *
 * ## Why this never asks at a terminal
 *
 * The service runs where nobody is watching. The passphrase comes from the environment or the
 * service does not start; the command that manages the store is the interactive one.
 *
 * @param environment where the location of the store and its passphrase are read from.
 */
internal fun openServiceCredentialProvider(environment: Map<String, String>): ServiceCredentialAccess {
    val storePath: Path = CredentialsStorePath.fromEnvironment(environment)
    if (!Files.exists(storePath)) {
        return ServiceCredentialAccess.Refused(
            "the credential store the authorizations rely on is not there: run `credentials add` first",
        )
    }
    val passphrase = try {
        PassphraseResolver.resolve(environment)
    } catch (_: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
        return ServiceCredentialAccess.Refused(
            "the credential store could not be opened: no passphrase is available",
        )
    }
    val store = try {
        LocalSecretStore(storePath, passphrase)
    } catch (_: CredentialsStorePosixPermissionsException) {
        passphrase.fill(WIPED)
        return ServiceCredentialAccess.Refused(
            "the credential store could not be opened: its permissions cannot be made private to its owner",
        )
    }
    return try {
        store.verifyPassphrase()
        ServiceCredentialAccess.Opened(LocalCredentialProvider(store))
    } catch (_: SecretStorePassphraseMismatchException) {
        store.close()
        ServiceCredentialAccess.Refused("the credential store could not be unlocked: the passphrase did not match")
    } catch (_: SecretStoreTamperException) {
        store.close()
        ServiceCredentialAccess.Refused("the credential store did not pass its integrity check")
    } catch (_: SecretStoreException) {
        store.close()
        ServiceCredentialAccess.Refused("the credential store could not be read")
    }
}

private const val WIPED: Char = '\u0000'

/**
 * What composing the credential brokers of the service produced.
 */
internal sealed interface ServiceBrokers {

    /**
     * The service can start. [factory] is null when no authorization grants a credential, and
     * then [provider] is null too: such a service never opens the store. The service owns
     * [provider] and closes it after everything that may read from it.
     */
    data class Composed(val factory: RunBrokerFactory?, val provider: CredentialProvider?) : ServiceBrokers

    /** The service must not start, and this says why. */
    data class Refused(val detail: String) : ServiceBrokers
}

/**
 * Composes the brokers the service gives its runs, or none.
 *
 * ## Why a service without grants never opens the store
 *
 * Opening the store needs the passphrase of the operator. A service that grants no credential has
 * no use for it, and asking for it anyway would make every such service hold the key to secrets it
 * will never hand out.
 *
 * @param authorizations the authorizations the service was started with.
 * @param brokersRoot the directory below which every run gets its own broker directory.
 * @param environment where the store is located and unlocked from.
 * @param opening how the store is opened; a parameter so that the decision can be observed without
 *   a store.
 * @param connectedOnly whether the credentials of the runs are held by connected controllers and
 *   not by the service. Then the store is NOT opened, whatever the environment says: the brokers
 *   have no local provider, so a run that did not name a controller is refused every credential it
 *   asks and no credential is looked up anywhere else.
 */
internal fun composeServiceBrokers(
    authorizations: ServiceAuthorizations,
    brokersRoot: Path,
    environment: Map<String, String>,
    opening: (Map<String, String>) -> ServiceCredentialAccess = ::openServiceCredentialProvider,
    connectedOnly: Boolean = false,
): ServiceBrokers {
    if (!authorizations.grantsAnyCredential) {
        return ServiceBrokers.Composed(factory = null, provider = null)
    }
    if (connectedOnly) {
        val factory = RunBrokerFactory(brokersRoot, provider = null, grantsOf = authorizations::grantsOfDigest)
        return if (factory.fits()) {
            ServiceBrokers.Composed(factory, provider = null)
        } else {
            ServiceBrokers.Refused(
                "the credential broker cannot be placed below the directory of the socket: its path would be too long",
            )
        }
    }
    val provider = when (val access = opening(environment)) {
        is ServiceCredentialAccess.Opened -> access.provider
        is ServiceCredentialAccess.Refused -> return ServiceBrokers.Refused(access.detail)
    }
    val factory = RunBrokerFactory(brokersRoot, provider, authorizations::grantsOfDigest)
    if (!factory.fits()) {
        provider.close()
        return ServiceBrokers.Refused(
            "the credential broker cannot be placed below the directory of the socket: its path would be too long",
        )
    }
    return ServiceBrokers.Composed(factory, provider)
}

/**
 * Gives back the credential provider of the service on the way out, ignoring whatever it throws.
 *
 * A failure while releasing must not hide how the service ended.
 */
internal fun closeServiceCredentials(provider: CredentialProvider?) {
    try {
        provider?.close()
    } catch (_: Exception) {
        // Shutdown path: the store is closed or the process is ending.
    }
}
