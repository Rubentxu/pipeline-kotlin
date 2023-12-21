package dev.rubentxu.pipeline.backend.sources

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.IPipelineContext
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

    override suspend fun getPipeline(context: IPipelineContext): IPipeline {
        val pipelineDef = evaluateScriptFile(path)
        logger.system("Pipeline definition: $pipelineDef")
        return buildPipeline(pipelineDef)
    }

    fun buildPipeline(pipelineDef: PipelineDefinition): Pipeline = runBlocking {
        pipelineDef.build()
    }

    // Evalúa el archivo de script y devuelve la definición del pipeline.
    fun evaluateScriptFile(scriptPath: Path): PipelineDefinition {
        val engine = getScriptEngine()
        val scriptFile = scriptPath.toFile()
        return engine.eval(scriptFile.reader()) as? PipelineDefinition
            ?: throw IllegalArgumentException("Script does not contain a PipelineDefinition")
    }

    override fun getScriptEngine(): ScriptEngine =
        ScriptEngineManager().getEngineByExtension("kts")
            ?: throw IllegalStateException("Script engine for .kts files not found")


}