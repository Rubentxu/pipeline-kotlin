package dev.rubentxu.pipeline.model.retrievers

import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.workspace.WorkspaceManager
import java.net.URL

data class IDConfig private constructor(
    val id: String,
) : Configuration {
    companion object {
        fun create(id: String): IDConfig {
            require(id.isNotEmpty()) { "ID cannot be empty" }
            require(id.length <= 50) { "ID cannot be more than 50 characters" }
            require(id.all { it.isDefined() }) { "ID can only contain alphanumeric characters : ${id}" }
            return IDConfig(id)
        }
    }
}

interface ScmDefinition : Configuration {
    val id: IDConfig
    val sourceRepository: SourceRepository
    val branches: List<String>
}
interface SourceRepository : Configuration

data class SourceCode(val url: URL)

data class ScmConfig(
    val definitions: Map<IDConfig, ScmDefinition>,
) : Configuration
interface SourceCodeManager {
    fun findSourceRepository(id: IDConfig): SourceRepository
}

interface SourceRetriever<T> where T: IPipelineConfig {

    fun retrieve(config: T): SourceCode
}

interface ProjectSourceRetriever<T>: SourceRetriever<T> where T: IPipelineConfig

interface LibrarySourceRetriever<T>: SourceRetriever<T> where T: IPipelineConfig

interface PipelineSourceRetriever<T>: SourceRetriever<T> where T: IPipelineConfig