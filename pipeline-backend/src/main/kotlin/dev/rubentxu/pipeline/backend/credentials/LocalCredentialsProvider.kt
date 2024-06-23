package dev.rubentxu.pipeline.backend.credentials

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineError
import dev.rubentxu.pipeline.model.credentials.Credentials
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider


class LocalCredentialsProvider(
    private val credentials: MutableMap<IDComponent, Credentials>
): ICredentialsProvider {

    override fun getCredentialsById(id: IDComponent): Result<Credentials> {
        credentials[id]?.let {
            return Result.success(it)
        } ?: return Result.failure(PipelineError("Credentials with id '$id' not found"))
    }

    override fun registerCredentials(vararg credentialsConfig: Credentials) {
        credentialsConfig.forEach {
            credentials[it.id] = it
        }
    }

    override fun unregisterCredentials(id: IDComponent) {
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