package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * Credential store interface — ISP-optimal: bind/release/materialize split.
 *
 * Design (ADR-0049 D2 — rung ii):
 * - KEK derives from passphrase via Argon2id (OWASP floor: m≥19456 KiB, t≥2, p≥1)
 * - Per-entry AES-256-GCM with random 96-bit nonce + AAD (anti-swap/rename)
 * - Typed channel: [SecretHandle] is the ONLY carrier for secret bytes
 * - No secret bytes cross the DSL/event boundary (CR-BD-016)
 *
 * ## ISP Analysis
 *
 * Split into three focused interfaces:
 * - [SecretStore]: persistence + retrieval (put/get/list/remove/rotate)
 * - [CredentialScope]: lifecycle management + wipe (env/close)
 * - [SecretPatternRegistry]: redaction patterns (T6)
 *
 * Each interface has a single reason to change:
 * - SecretStore: storage format/crypto changes
 * - CredentialScope: lifecycle/binding changes
 * - SecretPatternRegistry: pattern matching changes
 *
 * @see LocalSecretStore for the filesystem implementation using Argon2id + AES-GCM envelope encryption
 */
interface SecretStore : AutoCloseable {

    /**
     * Stores a typed credential (ML-R6).
     *
     * @param id The credential ID (L1 structural carrier, not secret)
     * @param credential The typed credential to store
     * @throws CredentialsStoreException on storage failure
     */
    fun add(id: CredentialsId, credential: Credential)

    /**
     * Legacy single-blob storage (ML-R4 compatibility).
     *
     * Internally wraps the bytes as a [dev.rubentxu.pipeline.v2.credentials.multipart.SecretText]
     * and delegates to [add].
     *
     * @param id The credential ID
     * @param bytes The secret bytes
     * @throws CredentialsStoreException on storage failure
     */
    fun put(id: CredentialsId, bytes: ByteArray)

    /**
     * Retrieves a typed credential (ML-R6).
     *
     * @param id The credential ID
     * @return [Credential] typed credential
     * @throws SecretStoreException if not found or tampered
     */
    fun get(id: CredentialsId): Credential

    /**
     * Retrieves a credential as [SecretHandle] (v1 compatibility).
     *
     * This is the ML-R4 compatibility method. Prefer [get] which returns
     * the typed [Credential].
     *
     * @param id The credential ID
     * @return [SecretHandle] wrapping the secret bytes
     * @throws SecretStoreException if not found or tampered
     */
    fun getAsSecretHandle(id: CredentialsId): SecretHandle

    /**
     * Retrieves a specific part of a multipart credential as a [SecretHandle].
     *
     * Used by [dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer] for
     * file-based credential kinds that need to materialize to temp files.
     *
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
     *
     * @return List of credential IDs (NEVER returns values — CR-BD-010)
     */
    fun list(): List<CredentialsId>

    /**
     * Removes a credential from the store.
     *
     * @param id The credential ID to remove
     */
    fun remove(id: CredentialsId)

    /**
     * Re-encrypts a credential with new bytes, preserving the DEK (ML-R6).
     *
     * @param id The credential ID
     * @param credential The new typed credential
     * @throws CredentialsStoreException if the credential doesn't exist
     */
    fun rotate(id: CredentialsId, credential: Credential)

    /**
     * Re-encrypts a credential with new bytes, preserving the DEK (v1 compatibility).
     *
     * Internally wraps the bytes as [dev.rubentxu.pipeline.v2.domain.credentials.SecretText]
     * and delegates to [rotate].
     *
     * @param id The credential ID
     * @param newBytes The new secret bytes
     * @throws CredentialsStoreException if the credential doesn't exist
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
