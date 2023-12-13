package dev.rubentxu.pipeline.model.jobs

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.IPipelineConfig
import dev.rubentxu.pipeline.model.PipelineComponent
import dev.rubentxu.pipeline.model.PipelineComponentFromMapFactory
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Agent
import dev.rubentxu.pipeline.model.pipeline.JobResult
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.validation.validateAndGet
import java.net.URL
import java.nio.file.Path

interface JobLauncher {
    val listeners: List<JobExecutionListener>
    val agent: Agent

    fun launch(instance: JobInstance): JobExecution

    fun execute(pipeline: Pipeline): JobResult
}

interface Job: IPipelineConfig  {
    val name: String
    val environmentVars: EnvVars
    val publisher: Publisher
    val projectSource: ProjectSource
    val librarySources: List<LibrarySource>
    val pipelineLoader: AbstractPipelineLoader
    val trigger: Trigger?
}

class JobInstance(
    override val name: String,
    override val environmentVars: EnvVars,
    override val publisher: Publisher,
    override val projectSource: ProjectSource,
    override val librarySources: List<LibrarySource>,
    override val pipelineLoader: AbstractPipelineLoader,
    override val trigger: Trigger?,
    val parameters: List<JobParameter<*>>,
): Job {}

interface JobParameter<T>: PipelineComponent {
    val name: String
    val defaultValue: T
    val description: String
}

class JobExecution(val jobInstance: JobInstance) : PipelineComponent {}

interface JobExecutionListener : PipelineComponent {
    fun onPreExecute(pipeline: Pipeline)
    fun onPostExecute(pipeline: Pipeline, result: JobResult)
}

class JobExecutionException(message: String) : Exception(message) {}

class UnexpectedJobExecutionException(message: String) : Exception(message) {}

interface Trigger : PipelineComponent {}

data class CronTrigger(
    val cron: String,
) : Trigger {}

class CromTriggerBuilder {
    companion object : PipelineComponentFromMapFactory<CronTrigger> {
        override fun create(data: Map<String, Any>): CronTrigger {
            return CronTrigger(
                cron = data.validateAndGet("cron").isString().throwIfInvalid("cron is required in Trigger")
            )
        }
    }
}

data class Publisher(
    val mailer: Mailer,
    val archiveArtifacts: ArchiveArtifacts,
    val junit: Junit,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<Publisher> {
        override fun create(data: Map<String, Any>): Publisher {
            return Publisher(
                mailer = Mailer.create(
                    data.validateAndGet("mailer").isMap()
                        .throwIfInvalid("mailer is required in Publisher") as Map<String, Any>
                ),
                archiveArtifacts = ArchiveArtifacts.create(
                    data.validateAndGet("archiveArtifacts").isMap()
                        .throwIfInvalid("archiveArtifacts is required in Publisher") as Map<String, Any>
                ),
                junit = Junit.create(
                    data.validateAndGet("junit").isMap()
                        .throwIfInvalid("junit is required in Publisher") as Map<String, Any>
                )
            )
        }
    }
}

class JobParameterFactory {
    companion object : PipelineComponentFromMapFactory<JobParameter<*>> {
        override fun create(data: Map<String, Any>): JobParameter<*> {
            return when (data?.keys?.first()) {
                "string" -> StringJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "choice" -> ChoiceJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "boolean" -> BooleanJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "password" -> PasswordJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "text" -> TextJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid parameter type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}

data class Mailer(
    val recipients: String,
    val notifyEveryUnstableBuild: Boolean,
    val sendToIndividuals: Boolean,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<Mailer> {
        override fun create(data: Map<String, Any>): Mailer {
            return Mailer(
                recipients = data.validateAndGet("recipients").isString()
                    .throwIfInvalid("recipients is required in Mailer"),
                notifyEveryUnstableBuild = data.validateAndGet("notifyEveryUnstableBuild").isBoolean()
                    .throwIfInvalid("notifyEveryUnstableBuild is required in Mailer"),
                sendToIndividuals = data.validateAndGet("sendToIndividuals").isBoolean()
                    .throwIfInvalid("sendToIndividuals is required in Mailer")
            )
        }
    }
}

data class ArchiveArtifacts(
    val artifacts: String,
    val excludes: String,
    val fingerprint: Boolean,
    val onlyIfSuccessful: Boolean,
    val allowEmptyArchive: Boolean,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<ArchiveArtifacts> {
        override fun create(data: Map<String, Any>): ArchiveArtifacts {
            return ArchiveArtifacts(
                artifacts = data.validateAndGet("artifacts").isString()
                    .throwIfInvalid("artifacts is required in ArchiveArtifacts"),
                excludes = data.validateAndGet("excludes").isString()
                    .throwIfInvalid("excludes is required in ArchiveArtifacts"),
                fingerprint = data.validateAndGet("fingerprint").isBoolean()
                    .throwIfInvalid("fingerprint is required in ArchiveArtifacts"),
                onlyIfSuccessful = data.validateAndGet("onlyIfSuccessful").isBoolean()
                    .throwIfInvalid("onlyIfSuccessful is required in ArchiveArtifacts"),
                allowEmptyArchive = data.validateAndGet("allowEmptyArchive").isBoolean()
                    .throwIfInvalid("allowEmptyArchive is required in ArchiveArtifacts")
            )
        }
    }
}

data class Junit(
    val testResults: String,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<Junit> {
        override fun create(data: Map<String, Any>): Junit {
            return Junit(
                testResults = data.validateAndGet("testResults").isString()
                    .throwIfInvalid("testResults is required in Junit")
            )
        }
    }
}

data class StringJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
) : JobParameter<String> {
    companion object : PipelineComponentFromMapFactory<StringJobParameter> {
        override fun create(data: Map<String, Any>): StringJobParameter {
            return StringJobParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in StringParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString()
                    .throwIfInvalid("defaultValue is required in StringParameter"),
                description = data.validateAndGet("description").isString()
                    .throwIfInvalid("description is required in StringParameter")
            )
        }
    }
}

data class ChoiceJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
    val choices: List<String>,
) : JobParameter<String> {
    companion object : PipelineComponentFromMapFactory<ChoiceJobParameter> {
        override fun create(data: Map<String, Any>): ChoiceJobParameter {
            val choices = data.validateAndGet("choices").isList()
                .throwIfInvalid("choices is required in ChoiceParameter") as List<String>
            val firstChoice = choices.first()
            return ChoiceJobParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in ChoiceParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString()
                    .defaultValueIfInvalid(firstChoice) as String,
                description = data.validateAndGet("description").isString()
                    .throwIfInvalid("description is required in ChoiceParameter"),
                choices = choices
            )
        }
    }
}

data class BooleanJobParameter(
    override val name: String,
    override val defaultValue: Boolean,
    override val description: String,
) : JobParameter<Boolean> {
    companion object : PipelineComponentFromMapFactory<BooleanJobParameter> {
        override fun create(data: Map<String, Any>): BooleanJobParameter {
            return BooleanJobParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in BooleanParameter"),
                defaultValue = data.validateAndGet("defaultValue").isBoolean()
                    .throwIfInvalid("defaultValue is required in BooleanParameter"),
                description = data.validateAndGet("description").isString()
                    .throwIfInvalid("description is required in BooleanParameter")
            )
        }
    }
}

data class PasswordJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
) : JobParameter<String> {
    companion object : PipelineComponentFromMapFactory<PasswordJobParameter> {
        override fun create(data: Map<String, Any>): PasswordJobParameter {
            return PasswordJobParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in PasswordParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString()
                    .throwIfInvalid("defaultValue is required in PasswordParameter"),
                description = data.validateAndGet("description").isString()
                    .throwIfInvalid("description is required in PasswordParameter")
            )
        }
    }
}

data class TextJobParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
) : JobParameter<String> {
    companion object : PipelineComponentFromMapFactory<TextJobParameter> {
        override fun create(data: Map<String, Any>): TextJobParameter {
            return TextJobParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in TextParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString()
                    .throwIfInvalid("defaultValue is required in TextParameter"),
                description = data.validateAndGet("description").isString()
                    .throwIfInvalid("description is required in TextParameter")
            )
        }
    }
}

data class ProjectSource(
    val name: String,
    val scmReferenceId: IDComponent
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<ProjectSource> {
        override fun create(data: Map<String, Any>): ProjectSource {
            return ProjectSource(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid("name is required in ProjectSource"),
                scmReferenceId = IDComponent.create(data.validateAndGet("scmReferenceId")
                    .isString()
                    .throwIfInvalid("scmReferenceId is required in ProjectSource")
                )
            )
        }
    }
}

data class LibrarySource(
    val name: String,
    val scmReferenceId: IDComponent
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<LibrarySource> {
        override fun create(data: Map<String, Any>): LibrarySource {
            return LibrarySource(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid("name is required in LibrarySource"),
                scmReferenceId = IDComponent.create(data.validateAndGet("scmReferenceId")
                    .isString()
                    .throwIfInvalid("scmReferenceId is required in LibrarySource")
                )
            )
        }
    }
}

abstract class AbstractPipelineLoader(
    val name: String,
    val scriptPath: Path
) : PipelineComponent {

    val logger = PipelineLogger.getLogger()
    fun loadPipeline(): Pipeline {
        val pipelineDef = evaluateScriptFile(scriptPath)
        logger.system("Pipeline definition: $pipelineDef")
        val pipeline = buildPipeline(pipelineDef)
        return pipeline
    }

    abstract fun evaluateScriptFile(scriptPath: Path): PipelineDefinition

    abstract fun buildPipeline(pipelineDef: PipelineDefinition): Pipeline
}

class PipelineFileSourceFactory(val sourceCodeRepositoryManager: SourceCodeRepositoryManager): PipelineComponentFromMapFactory<AbstractPipelineLoader> {
        override fun create(data: Map<String, Any>): AbstractPipelineLoader {
            val name = data.validateAndGet("name").isString().throwIfInvalid("name is required in PipelineFileSource")
            val scmReferenceId = IDComponent.create(data.validateAndGet("scmReferenceId").isString()
                .throwIfInvalid("scmReferenceId is required in PipelineFileSource") as String)
            val relativeScriptPath = data.validateAndGet("scriptPath").isString()
                .throwIfInvalid("scriptPath is required in PipelineFileSource")

            val repository = sourceCodeRepositoryManager.findSourceRepository(scmReferenceId)
            val sourceCode = repository.retrieve()
            // url to path
            val scriptPath: Path = resolveScriptPath(sourceCode.url, Path.of(relativeScriptPath))


        }

        fun resolveScriptPath(url: URL, relativePath: Path): Path {
            val pathUrl: Path = Path.of(url.path)
            val scriptPath: Path = pathUrl.resolve(relativePath)
            return scriptPath
        }

}