package dev.rubentxu.pipeline.model.repository

import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.model.jobs.PipelineSource
import dev.rubentxu.pipeline.model.jobs.ProjectSourceCode
import java.net.URL
import java.nio.file.Path

enum class SourceCodeType {
    PROJECT,
    PIPELINE_DEFINITION,
    PLUGIN,
}

interface ISourceCodeManager: PipelineDomain {
    val definitions: Map<IDComponent, SourceCodeRepository>
    fun findSourceRepository(id: IDComponent): SourceCodeRepository
}

interface SCMExtension : PipelineDomain
data class SimpleSCMExtension<T>(
    val name: String,
    val value: T,
) : SCMExtension

interface SourceCodeRepository: IPipelineConfig  {
    val id: IDComponent
    val name: String
    val description: String?

    suspend fun retrieve(config: SourceCodeConfig, context: IPipelineContext): SourceCode
}

interface SourceCode: PipelineDomain {
    val id: IDComponent
    val name: String
    val description: String?
    val path: Path

}

open class SourceCodeConfig(
    val repositoryId: IDComponent,
    val name: String,
    val description: String?,
    val relativePath: Path?,
    val sourceCodeType: SourceCodeType,

) : PipelineDomain

class PluginSourceCodeConfig(
    repositoryId: IDComponent,
    name: String,
    description: String?,
    relativePath: Path?,
    sourceCodeType: SourceCodeType,
    val loadClass: String,

    ) : SourceCodeConfig(repositoryId, name, description, relativePath, sourceCodeType)

class SourceCodeRepositoryManager(
    override val definitions: Map<IDComponent, SourceCodeRepository>
): ISourceCodeManager {
    override fun findSourceRepository(id: IDComponent): SourceCodeRepository {
        return definitions[id] ?: throw IllegalArgumentException("Source code repository with id '$id' not found")
    }
}
