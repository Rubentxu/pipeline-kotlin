package dev.rubentxu.pipeline.backend.jobs

import dev.rubentxu.pipeline.backend.buildPipeline
import dev.rubentxu.pipeline.backend.evaluateScriptFile
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.jobs.JobResult
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.jobs.StageResult
import dev.rubentxu.pipeline.model.jobs.Status
import dev.rubentxu.pipeline.model.repository.ISourceCodeManager
import dev.rubentxu.pipeline.model.steps.EnvVars
import kotlinx.coroutines.coroutineScope
import java.net.URL
import java.nio.file.Path

class JobInstance(
    name: String,
    environmentVars: EnvVars,
    publisher: Publisher,
    projectSource: ProjectSource,
    librarySources: List<LibrarySource>,
    pipelineFileSource: PipelineFileSource,
    trigger: Trigger?,
    parameters: List<JobParameter<*>>,
    initParentJob: Boolean,
    active: Boolean,
    val logger: IPipelineLogger = PipelineLogger.getLogger(),
    val sourceCodeRepositoryManager: ISourceCodeManager,
): JobDefinition(
    name,
    environmentVars,
    publisher,
    projectSource,
    librarySources,
    pipelineFileSource,
    trigger,
    initParentJob,
    active
) {
    override fun resolvePipeline(): Pipeline {
        val smcReferenceId = pipelineFileSource.scmReferenceId
        val relativeScriptPath = pipelineFileSource.relativeScriptPath

        val repository = sourceCodeRepositoryManager.findSourceRepository(smcReferenceId)
        val sourceCode = repository.retrieve()
        // url to path
        val scriptPath: Path = resolveScriptPath(sourceCode.url, relativeScriptPath)


        val pipelineDef = evaluateScriptFile(scriptPath.toString())
        logger.system("Pipeline definition: $pipelineDef")
        return buildPipeline(pipelineDef)
    }

    private fun resolveScriptPath(url: URL, relativeScriptPath: Path): Path {
        val rootPath = Path.of(url.path)
        return rootPath.resolve(relativeScriptPath)
    }



    override suspend fun execute(pipeline: Pipeline): JobResult = coroutineScope {
        var status: Status

        logger.system("Registering pipeline listeners...")
        logger.system("Executing pipeline...")

        try {
            pipeline.executeStages()
        } catch (e: Exception) {
            logger.error("Pipeline execution failed: ${e.message}")
            status = Status.Failure
            pipeline.stageResults.addAll(listOf(StageResult(pipeline.currentStage, status)))
        }

        logger.system("Pipeline execution finished")

        status = if (pipeline.stageResults.any { it.status == Status.Failure }) Status.Failure else Status.Success

        val result = JobResult(status, pipeline.stageResults, pipeline.env, logger.logs())

        return@coroutineScope result
    }
}