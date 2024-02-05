package dev.rubentxu.pipeline.backend.factories.credentials


import arrow.core.raise.either
import dev.rubentxu.pipeline.backend.credentials.LocalCredentialsProvider
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.resolveValueExpressions
import dev.rubentxu.pipeline.backend.mapper.toPropertySet
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.Res
import dev.rubentxu.pipeline.model.credentials.Credentials
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider

class CredentialsProviderFactory {
    companion object {
        suspend fun create(rawYaml: PropertySet): Res<ICredentialsProvider> =
            either {
                val resolvedYaml = rawYaml.resolveValueExpressions()

                val credentialsMap: MutableMap<IDComponent, Credentials> =
                    LocalCredentialsFactory.create(resolvedYaml.toPropertySet()).bind()
                        .associateBy { it.id } as MutableMap<IDComponent, Credentials>

                LocalCredentialsProvider(
                    credentials = credentialsMap
                )
            }
    }
}