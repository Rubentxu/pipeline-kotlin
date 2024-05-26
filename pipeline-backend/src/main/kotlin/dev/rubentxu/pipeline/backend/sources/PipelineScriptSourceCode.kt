package dev.rubentxu.pipeline.backend.sources

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.PipelineError
import dev.rubentxu.pipeline.model.jobs.IPipeline
import dev.rubentxu.pipeline.model.jobs.PipelineSource
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager


class PipelineScriptSourceCode(
    override val id: IDComponent,
    override val name: String,
    override val description: String?,
    override val path: Path
) : PipelineSource {
    private val logger = PipelineLogger.getLogger()



}