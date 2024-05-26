package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.jobs.JobResult

interface JobExecutionListener {
    fun onPreExecute(pipeline: IPipeline)
    fun onPostExecute(pipeline: IPipeline, result: JobResult)
}