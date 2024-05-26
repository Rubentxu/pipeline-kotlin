package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.jobs.JobExecution
import dev.rubentxu.pipeline.core.jobs.JobResult
import java.io.InputStreamReader

interface JobLauncher {
    val listeners: List<JobExecutionListener>

    fun launch(context: IPipelineContext, scriptReader: InputStreamReader): JobExecution

    suspend fun execute(pipeline: IPipeline, context: IPipelineContext): JobResult
}