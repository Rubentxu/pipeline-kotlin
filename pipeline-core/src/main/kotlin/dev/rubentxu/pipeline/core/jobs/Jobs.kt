package dev.rubentxu.pipeline.core.jobs


import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.interfaces.IPipeline
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext
import dev.rubentxu.pipeline.core.interfaces.JobExecutionListener
import kotlinx.coroutines.Job


interface JobDefinition {
    val name: String
    val parameters: List<JobParameter<*>>
    suspend fun resolvePipeline(context: IPipelineContext): IPipeline

}


interface JobParameter<T> {
    val name: String
    val defaultValue: T?
    val description: String
}

class JobExecution(val job: Job, private val logger: ILogger) : Job by job, JobExecutionListener {
    private var _status = Status.NotStarted
    private var _result: JobResult? = null
    val status: Status
        get() = _status

    val result: JobResult
        get() = _result ?: throw UnexpectedJobExecutionException("Job execution result is not available")

    override fun onPreExecute(pipeline: IPipeline) {
        logger.info("JobExecution", "Job execution started")
        _status = Status.Running
    }

    override fun onPostExecute(pipeline: IPipeline, result: JobResult) {
        logger.info("JobExecution", "Job execution finished with status: ${result.status}")
        _status = result.status

    }


}


class JobExecutionException(message: String) : Exception(message) {}

class UnexpectedJobExecutionException(message: String) : Exception(message) {}

interface Trigger {
    abstract fun nextExecutionTime(): Any
}

data class CronTrigger(
    val cron: String,
) : Trigger {
    override fun nextExecutionTime(): Any {
        TODO("Not yet implemented")
    }
}

data class StringJobParameter(
    override val name: String,
    override val defaultValue: String?,
    override val description: String,
) : JobParameter<String>

data class ChoiceJobParameter(
    override val name: String,
    override val defaultValue: String?,
    override val description: String,
    val choices: List<String>,
) : JobParameter<String>

data class BooleanJobParameter(
    override val name: String,
    override val defaultValue: Boolean?,
    override val description: String,
) : JobParameter<Boolean>

data class PasswordJobParameter(
    override val name: String,
    override val defaultValue: String?,
    override val description: String,
) : JobParameter<String>

data class TextJobParameter(
    override val name: String,
    override val defaultValue: String?,
    override val description: String,
) : JobParameter<String>




