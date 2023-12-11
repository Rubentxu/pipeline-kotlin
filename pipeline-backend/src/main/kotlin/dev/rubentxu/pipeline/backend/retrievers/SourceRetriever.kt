package dev.rubentxu.pipeline.backend.retrievers


import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.retrievers.*
import dev.rubentxu.pipeline.model.workspace.WorkspaceManager

class SourceCodeManagerImpl(
    val scmConfig: ScmConfig
): SourceCodeManager {
    override fun findSourceRepository(id: IDConfig): SourceRepository {
        val scmDef = scmConfig.definitions.get(id)
        return scmDef?.sourceRepository ?: throw IllegalArgumentException("Invalid SCM definition for '${id}'")
    }
}

class ProjectLocalSourceRetriever(
    val workspaceManager: WorkspaceManager,
    val sourceCodeManager: SourceCodeManager,
    ): ProjectSourceRetriever<JobConfig> {

    override fun retrieve(it: JobConfig): SourceCode {

            val jobName = it.name
            workspaceManager.createWorkspace(jobName)
            val id = it.projectSource.scmReferenceId

            val sourceRepository = sourceCodeManager.findSourceRepository(id) as LocalRepository

            val sourcePath = sourceRepository.path
            return SourceCode(url = sourcePath.toUri().toURL())

    }
}

class ProjectSCMSourceRetriever: ProjectSourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): SourceCode {
        TODO()
    }
}

class LibraryLocalSourceRetriever: LibrarySourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): SourceCode {
        TODO()
    }
}

class LibrarySCMSourceRetriever: LibrarySourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): SourceCode {
        TODO()
    }
}

class PipelineLocalSourceRetriever: PipelineSourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): SourceCode {
        TODO()
    }
}

class PipelineSCMSourceRetriever: PipelineSourceRetriever<PipelineConfig> {
    override fun retrieve(config: PipelineConfig): SourceCode {
        TODO()
    }
}