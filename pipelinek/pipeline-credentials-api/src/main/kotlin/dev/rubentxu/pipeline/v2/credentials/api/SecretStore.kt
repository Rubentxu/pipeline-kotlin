package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * Credential store interface — ISP-optimal: bind/release/materialize split.
 * Design (— rung ii)
 * - KEK derives from passphrase via Argon2id (OWASP floor: m≥19456 KiB, t≥2, p≥1)
 * Per-entry with random 96-bit nonce + AAD (anti-swap/rename)
 * - Typed channel: [SecretHandle] is the ONLY carrier for secret bytes
 * No secret bytes cross the DSL/event boundary
 * ## ISP Analysis
 * Split into three focused interfaces:
 * - [SecretStore]: persistence + retrieval (put/get/list/remove/rotate)
 * - [CredentialScope]: lifecycle management + wipe (env/close)
 * - [SecretPatternRegistry]: redaction patterns
 * Each interface has a single reason to change:
 * - SecretStore: storage format/crypto changes
 * - CredentialScope: lifecycle/binding changes
 * - SecretPatternRegistry: pattern matching changes
 * The filesystem implementation is `LocalSecretStore`, with Argon2id and envelope
 * encryption.
*/
interface SecretStore : AutoCloseable {

    /**
     * Stores a typed credential
     * @param id The credential ID (structural carrier, not secret)
     * @param credential The typed credential to store
     * @throws SecretStoreException on storage failure
*/
    fun add(id: CredentialsId, credential: Credential)

    /**
     * Stores bytes as ONE secret.
     *
     * The bytes are the secret of a
     * [dev.rubentxu.pipeline.v2.domain.credentials.SecretText]. Internally the
     * store wraps them as that credential and delegates to [add]: the bytes
     * are written the way the store writes a typed credential, and reading
     * the credential back gives those bytes and not a serialization of them.
     *
     * @param id The credential ID
     * @param bytes The secret bytes
     * @throws SecretStoreException on storage failure
*/
    fun put(id: CredentialsId, bytes: ByteArray)

    /**
     * Retrieves a typed credential
     * @param id The credential ID
     * @return [Credential] typed credential
     * @throws SecretStoreException if not found or tampered
*/
    fun get(id: CredentialsId): Credential

    /**
     * Retrieves a credential as [SecretHandle].
     *
     * The handle carries the SECRET of the credential and nothing else: the
     * bytes of a [dev.rubentxu.pipeline.v2.domain.credentials.SecretText], and
     * never the store's own serialization of it. A credential that is not one
     * secret -- a username and a password, a private key and its owner, an
     * archive of several entries -- is refused, because a single opaque value
     * cannot say which of its parts it carries.
     *
     * @param id The credential ID
     * @return [SecretHandle] wrapping the secret bytes
     * @throws SecretStoreException if not found or tampered
     * @throws CredentialsStoreProjectionException if the credential is not a
     *   single secret
*/
    fun getAsSecretHandle(id: CredentialsId): SecretHandle

    /**
     * Retrieves a specific part of a multipart credential as a [SecretHandle].
     * Used by `CredentialMaterializer` for
     * file-based credential kinds that need to materialize to temp files.
     * @param id The credential ID
     * @param partName The name of the part to retrieve
     * @return [SecretHandle] wrapping the part bytes
     * @throws LinkedSecretReferenceNotFoundException if the credential or part does not exist
     * @throws LinkedSecretReferenceTypeMismatchException if the referenced credential is not a [dev.rubentxu.pipeline.v2.domain.credentials.SecretText]
     * @throws SecretStoreException if not found or tampered
*/
    fun getAsHandle(id: CredentialsId, partName: String): SecretHandle

    /**
     * Lists all credential IDs in the store.
     * @return List of credential IDs (NEVER returns values —)
*/
    fun list(): List<CredentialsId>

    /**
     * Removes a credential from the store.
     * @param id The credential ID to remove
*/
    fun remove(id: CredentialsId)

    /**
     * Re-encrypts a credential with new bytes, preserving the DEK
     * @param id The credential ID
     * @param credential The new typed credential
     * @throws SecretStoreException if the credential doesn't exist
*/
    fun rotate(id: CredentialsId, credential: Credential)

    /**
     * Replaces the bytes of ONE secret.
     *
     * The bytes are the secret of a
     * [dev.rubentxu.pipeline.v2.domain.credentials.SecretText]. Internally the
     * store wraps them as that credential and delegates to [rotate], so the
     * bytes are written the way the store writes a typed credential. A store
     * that holds no credential under that name is refused and nothing is
     * written.
     *
     * @param id The credential ID
     * @param newBytes The new secret bytes
     * @throws SecretStoreException if the credential doesn't exist
*/
    fun rotateBytes(id: CredentialsId, newBytes: ByteArray)

    /**
     * Closes the store, wiping cached keys from memory.
*/
    override fun close()
}

/**
 * Base exception for credential store errors.
*/
sealed class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the store file is corrupted or tampered.
*/
open class SecretStoreTamperException(message: String, cause: Throwable? = null) : SecretStoreException(message, cause)

/**
 * Thrown when the passphrase is incorrect.
*/
open class SecretStorePassphraseMismatchException(message: String = "Passphrase required") : SecretStoreException(message)

/**
 * Thrown when no passphrase is available.
*/
open class CredentialsStorePassphraseUnavailableException(message: String) : SecretStoreException(message)

/**
 * Thrown when a secret is empty.
*/
open class CredentialsStoreEmptySecretException(message: String = "Cannot store empty secret") : SecretStoreException(message)

/**
 * Thrown when POSIX permissions cannot be enforced.
*/
open class CredentialsStorePosixPermissionsException(message: String) : SecretStoreException(message)

/**
 * Thrown when a mutation could not be committed to the store file.
 *
 * The store was reachable and the mutation was understood, but the change
 * never reached the file. The answer is about the write and not about the
 * store: the store may be perfectly healthy, and what failed is the
 * replacement of its content. A caller that reads this as a tampered store is
 * diagnosing the wrong thing.
*/
open class CredentialsStoreCommitException(message: String, cause: Throwable? = null) :
    SecretStoreException(message, cause)

/**
 * Thrown when a credential is asked for as ONE secret and the kind it holds is
 * not one secret.
 *
 * A credential that carries a username AND a password, a private key AND its
 * owner, or an archive of several entries cannot be delivered as a single
 * opaque value: the blob would have to pick one of its parts without saying
 * which, and the caller would hold bytes it cannot interpret. The store
 * refuses rather than guesses, and it never hands out its own serialization of
 * the credential as if it were the secret.
 *
 * The refusal names the kind the store holds, so the operator knows what is
 * there. A caller that wants the typed credential asks for it
 * ([SecretStore.get]); a caller that wants one part asks for it by name
 * ([SecretStore.getAsHandle]).
 *
 * @param credentialId The credential the store holds
 * @param foundKind The kind it holds, named as the credential names it
*/
open class CredentialsStoreProjectionException(
    val credentialId: CredentialsId,
    val foundKind: String,
) : SecretStoreException(
    "Credential '${credentialId.value}' is of kind '$foundKind', which is not a single secret: " +
        "it is not delivered as one opaque value. Read the typed credential, or the part by name.",
)

/**
 * Thrown when a [dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef]
 * references a credential ID that does not exist in the store.
*/
class LinkedSecretReferenceNotFoundException(
    val referencedId: CredentialsId
) : SecretStoreException("Referenced credential '${referencedId.value}' not found in store")

/**
 * Thrown when a [dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef]
 * references a credential that is not a [dev.rubentxu.pipeline.v2.domain.credentials.SecretText].
*/
class LinkedSecretReferenceTypeMismatchException(
    val referencedId: CredentialsId,
    val expectedType: String,
    val actualType: String
) : SecretStoreException(
    "Referenced credential '${referencedId.value}' is of type '$actualType' where '$expectedType' was expected."
)
