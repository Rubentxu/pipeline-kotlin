package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.model.credentials.Credentials
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider


class CredentialsProvider(): ICredentialsProvider {
    val credentials = mutableMapOf<String, Credentials>()

    override fun getCredentialsById(id: String): Result<Credentials> {
        credentials[id]?.let {
            return Result.success(it)
        } ?: return Result.failure(IllegalArgumentException("Credentials with id '$id' not found"))
    }

    override fun registerCredentials(credentialsConfig: Credentials) {
        credentials[credentialsConfig.id] = credentialsConfig
    }

    override fun unregisterCredentials(id: String) {
        credentials.remove(id)
    }

    override fun listCredentials(): List<Credentials> {
        return credentials.values.toList()
    }

    override fun listCredentialsByType(type: Class<out Credentials>): List<Credentials> {
        return credentials.values.filter { type.isInstance(it) }
    }


    override fun listCredentialsByScope(scope: String): List<Credentials> {
        return credentials.values.filter { it.scope == scope }
    }
}