package dev.rubentxu.pipeline.model.credentials

import dev.rubentxu.pipeline.model.IDComponent

interface ICredentialsProvider {
    fun getCredentialsById(id: String): Result<Credentials>

    fun registerCredentials(credentials: Credentials)

    fun unregisterCredentials(id: String)

    fun listCredentials(): List<Credentials>

    fun listCredentialsByType(type: Class<out Credentials>): List<Credentials>

    fun listCredentialsByScope(scope: String): List<Credentials>
}


class LocalCredentialsProvider(val credentials: MutableMap<IDComponent, Credentials>) : ICredentialsProvider {
    companion object {
         fun create(data: List<Map<String, Any>>): LocalCredentialsProvider {
            if (data.isEmpty()) return LocalCredentialsProvider(mutableMapOf())

            val credentialsBuilderLists: MutableMap<IDComponent, Credentials> = data.map {
                val credentials = CredentialsBuilder.create(it)
                credentials.id to credentials
            }.toMap().toMutableMap()
            return LocalCredentialsProvider(credentialsBuilderLists)
        }
    }

    override fun getCredentialsById(id: String): Result<Credentials> {
        return Result.success(credentials[IDComponent.create(id)]!!)
    }

    override fun registerCredentials(credentials: Credentials) {
        this.credentials[credentials.id] = credentials
    }

    override fun unregisterCredentials(id: String) {
        this.credentials.remove(IDComponent.create(id))
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

