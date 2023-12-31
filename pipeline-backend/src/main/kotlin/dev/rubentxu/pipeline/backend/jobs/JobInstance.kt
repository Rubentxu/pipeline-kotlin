package dev.rubentxu.pipeline.backend.jobs

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.repository.ISourceCodeManager
import dev.rubentxu.pipeline.model.repository.PluginSourceCodeConfig
import dev.rubentxu.pipeline.model.repository.SourceCode
import dev.rubentxu.pipeline.model.repository.SourceCodeConfig
import dev.rubentxu.pipeline.model.workspace.WorkspaceManager
import java.net.URLClassLoader
import java.nio.file.Path

class JobInstance(
    override val name: String,
    override val projectSourceCode: SourceCodeConfig,
    override val pluginsSources: List<PluginSourceCodeConfig>,
    override val pipelineSourceCode: SourceCodeConfig,
    override val parameters: List<JobParameter<*>>,
) : JobDefinition {
    val logger: IPipelineLogger = PipelineLogger.getLogger()

    override suspend fun resolvePipeline(context: IPipelineContext): IPipeline {

        val sourceCodeRepositoryManager: ISourceCodeManager = context.getService(ISourceCodeManager::class).getOrThrow()

        val repository = sourceCodeRepositoryManager.findSourceRepository(pipelineSourceCode.repositoryId)
        val sourceCode: PipelineSource = repository.retrieve(pipelineSourceCode, context) as PipelineSource

        return sourceCode.getPipeline(context)
    }

    override suspend fun resolveProjectPath(context: IPipelineContext): Path {
        val sourceCodeRepositoryManager: ISourceCodeManager = context.getService(ISourceCodeManager::class).getOrThrow()
        val scmReferenceId = projectSourceCode.repositoryId
        val workspaceManager = context.getService(WorkspaceManager::class).getOrThrow()

        val repository = sourceCodeRepositoryManager.findSourceRepository(scmReferenceId)
        val sourceCode: ProjectSourceCode = repository.retrieve(projectSourceCode, context) as ProjectSourceCode

        return sourceCode.path
    }

    override suspend fun loadPlugins(context: IPipelineContext): Boolean {
        val sourceCodeRepositoryManager: ISourceCodeManager = context.getService(ISourceCodeManager::class).getOrThrow()


        // Cargar en classpath los plugins


        return true
    }

}