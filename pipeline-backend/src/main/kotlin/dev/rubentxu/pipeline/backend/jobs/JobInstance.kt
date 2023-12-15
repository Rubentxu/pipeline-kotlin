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
    parameters: List<JobParameter<*>>

): JobDefinition(
    name,
    environmentVars,
    publisher,
    projectSource,
    librarySources,
    pipelineFileSource,
    trigger

) {


}