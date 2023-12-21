package dev.rubentxu.pipeline.backend.factories.sources

import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.model.repository.SourceCodeRepository
import dev.rubentxu.pipeline.model.validations.validateAndGet

class SourceCodeRepositoryFactory : PipelineDomain {
    companion object : PipelineDomainFactory<SourceCodeRepository> {
        override suspend fun create(data: Map<String, Any>): SourceCodeRepository {
            val type = data.keys.first()
            val isLocalGit = data.validateAndGet("git.local")
                .isMap()
                .notNull()
                .notEmpty()
                .getValue().let {
                    it.isNullOrEmpty()
                }

            return when (type) {
                "git" -> if(isLocalGit) {
                   return LocalGitSourceCodeRepositoryFactory.create(
                        data.validateAndGet("git.local")
                            .isMap()
                            .defaultValueIfInvalid(emptyMap<String, Any>())
                    )
                } else {
                    return GitSourceCodeRepositoryFactory.create(
                        data.validateAndGet("git")
                            .isMap()
                            .defaultValueIfInvalid(emptyMap<String, Any>())
                    )
                }

                "svn" -> SvnSourceCodeRepositoryFactory.create(
                    data.validateAndGet("svn")
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