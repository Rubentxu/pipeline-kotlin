package dev.rubentxu.pipeline.model.credentials


interface Credentials {
    val id: String
    val description: String
    val scope: String
}

interface ICredentialsProvider {
    fun getCredentialsById(id: String): Result<Credentials>

    fun registerCredentials(credentials: Credentials)

    fun unregisterCredentials(id: String)

    fun listCredentials(): List<Credentials>

    fun listCredentialsByType(type: Class<out Credentials>): List<Credentials>

    fun listCredentialsByScope(scope: String): List<Credentials>
}

