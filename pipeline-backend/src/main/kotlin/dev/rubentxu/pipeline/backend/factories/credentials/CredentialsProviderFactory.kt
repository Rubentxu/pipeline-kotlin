package dev.rubentxu.pipeline.backend.factories.credentials


import dev.rubentxu.pipeline.backend.credentials.LocalCredentialsProvider
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.resolveValueExpressions
import dev.rubentxu.pipeline.backend.mapper.toPropertySet
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.credentials.Credentials
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class CredentialsProviderFactory {
    companion object {
        suspend fun create(rawYaml: PropertySet): Result<ICredentialsProvider> = runCatching {
            val resolvedYaml = rawYaml.resolveValueExpressions()

            val credentialsMap: MutableMap<IDComponent, Credentials> = coroutineScope {
                val credentials = async { LocalCredentialsFactory.create(resolvedYaml.toPropertySet()).getOrThrow() }
                credentials.await().associateBy { it.id } as MutableMap<IDComponent, Credentials>
            }

            LocalCredentialsProvider(
                credentials = credentialsMap
            )
        }
    }
}