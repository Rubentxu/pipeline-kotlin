package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * H0 Slice 1: Exception thrown when credential resolution fails.
 *
 * This exception is thrown by [CredentialSessionImpl.resolve] when a credential
 * cannot be resolved (SecretStoreException, materialization failure, etc.).
 *
 * It carries the [credentialsId] that failed for event correlation.
 */
class CredentialResolutionException(
    message: String,
    val credentialsId: CredentialsId,
    cause: Throwable? = null
) : Exception(message, cause)