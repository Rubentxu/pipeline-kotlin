package dev.rubentxu.pipeline.backend.agent



import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.workspace.IWorkspaceManager
import dev.rubentxu.pipeline.model.steps.EnvVars


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
