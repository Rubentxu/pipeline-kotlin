package dev.rubentxu.pipeline.model.credentials

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain

interface ICredentialsProvider: PipelineDomain {
    fun getCredentialsById(id: IDComponent): Result<Credentials>

    fun registerCredentials(credentials: Credentials)

    fun unregisterCredentials(id: IDComponent)

    fun listCredentials(): List<Credentials>

    fun listCredentialsByType(type: Class<out Credentials>): List<Credentials>

    fun listCredentialsByScope(scope: String): List<Credentials>
}



