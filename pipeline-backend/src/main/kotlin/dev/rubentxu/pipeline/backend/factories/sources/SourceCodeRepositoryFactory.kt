package dev.rubentxu.pipeline.backend.factories.sources

import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.mapper.PropertySet
import dev.rubentxu.pipeline.model.repository.SourceCodeRepository
import dev.rubentxu.pipeline.model.validations.validateAndGet

class SourceCodeRepositoryFactory : PipelineDomain {
    companion object : PipelineDomainFactory<SourceCodeRepository> {
        override val rootPath: String = "repositories"
        override val instanceName: String = "SourceCodeRepository"
        override suspend fun create(data: PropertySet): SourceCodeRepository {
            val type = data.keys.first()


            return when (type) {
                "git" -> GitSourceCodeRepositoryFactory.create(
                    data.validateAndGet("git")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                "local" -> LocalGitSourceCodeRepositoryFactory.create(
                    data.validateAndGet("git.local")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                "mercurial" -> MercurialSourceCodeRepositoryFactory.create(
                    data.validateAndGet("mercurial")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                else -> throw IllegalArgumentException("Invalid SCM type for '${data.keys.first()}'")
            }
        }
    }
}