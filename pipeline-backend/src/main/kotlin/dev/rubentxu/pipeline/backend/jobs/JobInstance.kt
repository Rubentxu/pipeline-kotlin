package dev.rubentxu.pipeline.backend.jobs

import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import dev.rubentxu.pipeline.model.repository.ISourceCodeManager
import dev.rubentxu.pipeline.model.repository.SourceCode
import dev.rubentxu.pipeline.model.steps.EnvVars
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.nio.file.Path
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

class JobInstance(
    override val name: String,
    override val environmentVars: EnvVars,
    override val publisher: Publisher?,
    override val projectSource: ProjectSource,
    override val pluginsDefinitionSource: List<PluginsDefinitionSource>,
    override val pipelineFileSource: PipelineFileSource,
    override val trigger: Trigger?,
    parameters: List<JobParameter<*>>,
    val sourceCodeRepositoryManager: ISourceCodeManager,
    val logger: IPipelineLogger,


    ): JobDefinition {

    override fun resolvePipeline(): IPipeline {
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

    override fun resolveProjectSourceCode(): SourceCode {
        val smcReferenceId = projectSource.scmReferenceId

        val repository = sourceCodeRepositoryManager.findSourceRepository(smcReferenceId)
        return repository.retrieve()
    }

    override fun resolvePluginsDefinitionSource(): List<SourceCode> {
        return pluginsDefinitionSource.map { source ->
            val smcReferenceId = source.scmReferenceId
            val repository = sourceCodeRepositoryManager.findSourceRepository(smcReferenceId)
            repository.retrieve()
        }
    }

    private fun resolveScriptPath(url: URL, relativeScriptPath: Path): Path {
        val rootPath = Path.of(url.path)
        return rootPath.resolve(relativeScriptPath)
    }

    fun buildPipeline(pipelineDef: PipelineDefinition): Pipeline = runBlocking {
        pipelineDef.build()
    }

    // Evalúa el archivo de script y devuelve la definición del pipeline.
    fun evaluateScriptFile(scriptPath: String): PipelineDefinition {
        val engine = getScriptEngine()
        val scriptFile = normalizeAndAbsolutePath(scriptPath).toFile()
        return engine.eval(scriptFile.reader()) as? PipelineDefinition
            ?: throw IllegalArgumentException("Script does not contain a PipelineDefinition")
    }

    fun getScriptEngine(): ScriptEngine =
        ScriptEngineManager().getEngineByExtension("kts")
            ?: throw IllegalStateException("Script engine for .kts files not found")



    fun normalizeAndAbsolutePath(file: String): Path {
        return Path.of(file).toAbsolutePath().normalize()
    }

    fun normalizeAndAbsolutePath(path: Path): Path {
        return path.toAbsolutePath().normalize()
    }


}