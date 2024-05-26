package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.jobs.JobResult


interface PipelineListener {
    suspend fun onPreExecute(pipeline: IPipeline)
    suspend fun onPostExecute(pipeline: IPipeline, result: JobResult)
}