package dev.rubentxu.pipeline.backend.agent

import dev.rubentxu.pipeline.model.pipeline.Pipeline
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerResponse
import com.github.dockerjava.api.model.ContainerConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.auth.CredentialsProvider
import dev.rubentxu.pipeline.backend.PipelineScriptRunner
import dev.rubentxu.pipeline.backend.retrievers.LibrarySourceRetriever
import dev.rubentxu.pipeline.backend.retrievers.PipelineSourceRetriever
import dev.rubentxu.pipeline.backend.retrievers.ProjectSourceRetriever
import dev.rubentxu.pipeline.model.Workspace
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.steps.EnvVars


interface IPipelineJob {
    val pipelineSourceRetriever: PipelineSourceRetriever
    val projectSourceRetriever: ProjectSourceRetriever?
    val librarySourceRetriever: LibrarySourceRetriever?
    val environment: EnvVars
    val inputParameters: Map<String, String>
    val credentialsProvider: CredentialsProvider
    val workspace: Workspace

    fun execute(): Result<Unit>
}



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
    override val credentialsProvider: CredentialsProvider,

) : IPipelineJob {
    override fun execute(): Result<Unit> {
        TODO("Not yet implemented")
    }
}
