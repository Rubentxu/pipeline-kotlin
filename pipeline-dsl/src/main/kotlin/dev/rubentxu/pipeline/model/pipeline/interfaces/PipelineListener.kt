package dev.rubentxu.pipeline.model.pipeline.interfaces

import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.JobResult

interface PipelineListener {
    suspend fun onPreExecute(pipeline: Pipeline)
    suspend fun onPostExecute(pipeline: Pipeline, result: JobResult)
}