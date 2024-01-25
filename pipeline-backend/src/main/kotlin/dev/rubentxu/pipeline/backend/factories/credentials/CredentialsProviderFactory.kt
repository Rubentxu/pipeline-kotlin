package dev.rubentxu.pipeline.backend.factories.credentials


import dev.rubentxu.pipeline.backend.credentials.LocalCredentialsProvider
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.credentials.Credentials
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import arrow.core.raise.Raise
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.*
import dev.rubentxu.pipeline.model.PropertiesError

class CredentialsProviderFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<ICredentialsProvider> {
        override val rootPath: String = "pipeline.credentialsProvider"

        context(Raise<PropertiesError>)
        override suspend fun create(rawYaml: PropertySet): ICredentialsProvider {
            val resolvedYaml: Map<String, Any> = rawYaml.resolveValueExpressions() as Map<String, Any>

            val credentialsMap: MutableMap<IDComponent, Credentials> = LocalCredentialsFactory.create(resolvedYaml.toPropertySet())
                .associateBy { it.id } as MutableMap<IDComponent, Credentials>

            return LocalCredentialsProvider(
                credentials = credentialsMap
            )
        }
    }
}