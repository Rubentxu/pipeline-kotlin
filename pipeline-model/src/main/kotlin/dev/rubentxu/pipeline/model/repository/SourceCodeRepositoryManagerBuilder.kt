package dev.rubentxu.pipeline.model.repository

import dev.rubentxu.pipeline.model.PipelineComponent
import dev.rubentxu.pipeline.model.PipelineComponentFromMapFactory
import dev.rubentxu.pipeline.validation.validateAndGet

class SourceCodeRepositoryManagerBuilder: PipelineComponent {
    companion object : PipelineComponentFromMapFactory<SourceCodeRepositoryManager> {
        override fun create(data: Map<String, Any>): SourceCodeRepositoryManager {
            val scmListMap = data.validateAndGet("scm")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>())
            val scmMap = scmListMap.map {
                val scm = SourceCodeRepositoryBuilder.create(it)
                scm.id to scm
            }.toMap()
            return SourceCodeRepositoryManager(scmMap)
        }
    }
}