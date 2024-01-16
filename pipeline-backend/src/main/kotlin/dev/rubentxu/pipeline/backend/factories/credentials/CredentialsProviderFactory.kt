package dev.rubentxu.pipeline.backend.factories.credentials


import dev.rubentxu.pipeline.backend.credentials.LocalCredentialsProvider
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.credentials.Credentials
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.mapper.PropertyPath
import dev.rubentxu.pipeline.model.mapper.PropertySet
import pipeline.kotlin.extensions.resolveValueExpressions
import arrow.core.raise.Raise
import dev.rubentxu.pipeline.model.mapper.ValidationError
import dev.rubentxu.pipeline.model.mapper.propertyPath

class CredentialsProviderFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<ICredentialsProvider> {
        override val rootPath: PropertyPath = "pipeline.credentialsProvider".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(rawYaml: PropertySet): ICredentialsProvider {
            val resolvedYaml: Map<String, Any> = rawYaml.resolveValueExpressions() as Map<String, Any>

            val credentialsMap: MutableMap<IDComponent, Credentials> = LocalCredentialsFactory.create(resolvedYaml)
                .associateBy { it.id } as MutableMap<IDComponent, Credentials>

            return LocalCredentialsProvider(
                credentials = credentialsMap
            )
        }
    }
}