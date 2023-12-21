package dev.rubentxu.pipeline.backend.factories.credentials


import dev.rubentxu.pipeline.backend.credentials.LocalCredentialsProvider
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.credentials.Credentials
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.validations.validateAndGet

class CredentialsProviderFactory {

    companion object : PipelineDomainFactory<ICredentialsProvider> {
        override suspend fun create(data: Map<String, Any>): ICredentialsProvider {
            val credentialsList: MutableMap<IDComponent, Credentials> = data.validateAndGet("credentials")
                .isList()
                .throwIfInvalid("credentials is required in CredentialsProvider")
                .map {
                    return@map CredentialsFactory.create(it as Map<String, Any>)
                }.associateBy { it.id }.toMutableMap()

            return LocalCredentialsProvider(
                credentials = credentialsList
            )
        }
    }
}