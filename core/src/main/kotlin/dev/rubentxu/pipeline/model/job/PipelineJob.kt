package dev.rubentxu.pipeline.model.job

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.retrievers.LibrarySourceRetriever
import dev.rubentxu.pipeline.model.retrievers.PipelineSourceRetriever
import dev.rubentxu.pipeline.model.retrievers.ProjectSourceRetriever
import dev.rubentxu.pipeline.model.workspace.IWorkspaceManager
import dev.rubentxu.pipeline.model.workspace.WorkspaceManager
import dev.rubentxu.pipeline.steps.EnvVars

interface IPipelineJob<T> where T: IPipelineConfig {
    val pipelineSourceRetriever: PipelineSourceRetriever<T>
    val projectSourceRetriever: ProjectSourceRetriever<T>?
    val librarySourceRetriever: LibrarySourceRetriever<T>?
    val environment: EnvVars
    val inputParameters: Map<String, String>
    val credentialsProvider: ICredentialsProvider
    val workspaceManager: IWorkspaceManager

    fun execute(): Result<Unit>
}