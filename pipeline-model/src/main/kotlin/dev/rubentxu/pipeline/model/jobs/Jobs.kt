package dev.rubentxu.pipeline.model.jobs

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.repository.SourceCode
import dev.rubentxu.pipeline.model.steps.EnvVars
import dev.rubentxu.pipeline.model.validations.validateAndGet
import kotlinx.coroutines.*
import java.nio.file.Path

interface JobLauncher {
    val listeners: List<JobExecutionListener>

    fun launch(instance: JobDefinition, context: IPipelineContext): JobExecution

    suspend fun execute(pipeline: IPipeline, context: IPipelineContext): JobResult
}


interface JobDefinition: PipelineDomain{
    val name: String
    val environmentVars: EnvVars
    val publisher: Publisher?
    val projectSource: ProjectSource
    val pluginsDefinitionSource: List<PluginsDefinitionSource>
    val pipelineFileSource: PipelineFileSource
    val trigger: Trigger?

    fun resolvePipeline(): IPipeline
    fun resolveProjectSourceCode(): SourceCode
    fun resolvePluginsDefinitionSource(): List<SourceCode>
}



interface JobParameter<T> : PipelineDomain {
    val name: String
    val defaultValue: T
    val description: String
}

class JobExecution(val job:  Job): Job by job, JobExecutionListener {
    private val logger = PipelineLogger.getLogger()
    private var _status = Status.NotStarted
    private var _result: JobResult? = null
    val status: Status
        get() = _status

    val result: JobResult
        get() = _result ?: throw UnexpectedJobExecutionException("Job execution result is not available")
    override fun onPreExecute(pipeline: IPipeline) {
        logger.info("Job execution started")
        _status = Status.Running
    }

    override fun onPostExecute(pipeline: IPipeline, result: JobResult) {
        logger.info("Job execution finished with status: ${result.status}")
        _status = result.status

    }


}

interface IPipeline {
    var stageResults :MutableList<StageResult>
    var currentStage: String
    suspend fun executeStages(context: IPipelineContext)

}

interface JobExecutionListener : PipelineDomain {
    fun onPreExecute(pipeline: IPipeline)
    fun onPostExecute(pipeline: IPipeline, result: JobResult)
}

class JobExecutionException(message: String) : Exception(message) {}

class UnexpectedJobExecutionException(message: String) : Exception(message) {}

interface Trigger : PipelineDomain {}

data class CronTrigger(
    val cron: String,
) : Trigger {}



data class Publisher(
    val mailer: Mailer,
    val archiveArtifacts: ArchiveArtifacts,
    val jUnitTestResults: String,
) : PipelineDomain

data class Mailer(
    val recipients: String,
    val notifyEveryUnstableBuild: Boolean,
    val sendToIndividuals: Boolean,
) : PipelineDomain

data class ArchiveArtifacts(
    val artifacts: String,
    val excludes: String,
    val fingerprint: Boolean,
    val onlyIfSuccessful: Boolean,
    val allowEmptyArchive: Boolean,
) : PipelineDomain


data class StringJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
) : JobParameter<String>

data class ChoiceJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
    val choices: List<String>,
) : JobParameter<String>

data class BooleanJobParameter(
    override val name: String,
    override val defaultValue: Boolean,
    override val description: String,
) : JobParameter<Boolean>

data class PasswordJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
) : JobParameter<String>

data class TextJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
) : JobParameter<String>

data class ProjectSource(
    val name: String,
    val scmReferenceId: IDComponent,
) : PipelineDomain

data class PluginsDefinitionSource(
    val name: String,
    val scmReferenceId: IDComponent,
) : PipelineDomain

class PipelineFileSource(
    val name: String,
    val relativeScriptPath: Path,
    val scmReferenceId: IDComponent,
) : PipelineDomain {


}
