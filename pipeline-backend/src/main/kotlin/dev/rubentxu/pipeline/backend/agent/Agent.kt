package dev.rubentxu.pipeline.backend.agent


import dev.rubentxu.pipeline.backend.PipelineScriptRunner

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.job.IPipelineJob
import dev.rubentxu.pipeline.model.retrievers.LibrarySourceRetriever
import dev.rubentxu.pipeline.model.retrievers.PipelineSourceRetriever
import dev.rubentxu.pipeline.model.retrievers.ProjectSourceRetriever
import dev.rubentxu.pipeline.model.workspace.IWorkspaceManager
import dev.rubentxu.pipeline.model.steps.EnvVars






interface Agent {
    val runner: PipelineScriptRunner


    fun setup(config: IPipelineConfig): Result<Unit>
    fun execute(job: IPipelineJob): Result<Unit>

    fun teardown(): Result<Unit>

}


class PipelineJob(
    override val pipelineSourceRetriever: PipelineSourceRetriever,
    override val projectSourceRetriever: ProjectSourceRetriever?,
    override val librarySourceRetriever: LibrarySourceRetriever?,
    override val environment: EnvVars,
    override val inputParameters: Map<String, String>,
    override val credentialsProvider: ICredentialsProvider,
    override val workspaceManager: IWorkspaceManager
    ) : IPipelineJob {


    override fun execute(): Result<Unit> {
        val pipelineSource = pipelineSourceRetriever.retrieve()
    }
}
