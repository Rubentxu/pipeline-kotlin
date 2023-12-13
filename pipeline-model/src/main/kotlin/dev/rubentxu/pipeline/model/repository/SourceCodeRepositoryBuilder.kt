package dev.rubentxu.pipeline.model.repository

import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.validation.validateAndGet

class SourceCodeRepositoryBuilder : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<SourceCodeRepository> {
        override fun create(data: Map<String, Any>): SourceCodeRepository {
            return when (data.keys.first()) {
                "git" -> GitSourceCodeRepository.create(
                    data.validateAndGet("git")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                "svn" -> SvnSourceCodeRepository.create(
                    data.validateAndGet("svn")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )

                "mercurial" -> Mercurial.create(
                    data.validateAndGet("mercurial")
                        .isMap()
                        .defaultValueIfInvalid(emptyMap<String, Any>())
                )
                else -> throw IllegalArgumentException("Invalid SCM type for '${data.keys.first()}'")
            }
        }
    }
}