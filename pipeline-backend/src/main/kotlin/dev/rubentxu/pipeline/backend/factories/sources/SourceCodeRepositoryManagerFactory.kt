package dev.rubentxu.pipeline.backend.factories.sources

import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import dev.rubentxu.pipeline.model.validations.validateAndGet


class SourceCodeRepositoryManagerFactory: PipelineDomain {
    companion object : PipelineDomainFactory<SourceCodeRepositoryManager> {
        override suspend fun create(data: Map<String, Any>): SourceCodeRepositoryManager {
            val scmListMap = data.validateAndGet("scm")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>())
            val scmMap = scmListMap.map {
                val scm = SourceCodeRepositoryFactory.create(it)
                scm.id to scm
            }.toMap()
            return SourceCodeRepositoryManager(scmMap)
        }
    }
}