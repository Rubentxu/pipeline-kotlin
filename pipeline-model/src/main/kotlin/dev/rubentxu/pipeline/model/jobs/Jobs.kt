package dev.rubentxu.pipeline.model.jobs

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineComponent
import dev.rubentxu.pipeline.model.PipelineComponentFromMapFactory
import dev.rubentxu.pipeline.model.steps.EnvVars
import dev.rubentxu.pipeline.model.validations.validateAndGet
import kotlinx.coroutines.*
import java.nio.file.Path

interface JobLauncher {
    val listeners: List<JobExecutionListener>

    fun launch(instance: JobDefinition): JobExecution

}


@OptIn(InternalCoroutinesApi::class)
abstract class JobDefinition(
    val name: String,
    val environmentVars: EnvVars,
    val publisher: Publisher,
    val projectSource: ProjectSource,
    val librarySources: List<LibrarySource>,
    val pipelineFileSource: PipelineFileSource,
    val trigger: Trigger?,
    initParentJob: Boolean,
    active: Boolean,
) : AbstractCoroutine<Unit>(Job(), initParentJob, active) {

    abstract fun resolvePipeline(): IPipeline

    abstract suspend fun execute(pipeline: IPipeline): JobResult

    override fun onStart() {
        super.onStart()

    }


}



interface JobParameter<T> : PipelineComponent {
    val name: String
    val defaultValue: T
    val description: String
}

class JobExecution(jobInstance: JobDefinition, val job:  Job): Job by job {}

interface IPipeline {
    val env: EnvVars
}

interface JobExecutionListener : PipelineComponent {
    fun onPreExecute(pipeline: IPipeline)
    fun onPostExecute(pipeline: IPipeline, result: JobResult)
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
    val scmReferenceId: IDComponent,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<ProjectSource> {
        override fun create(data: Map<String, Any>): ProjectSource {
            return ProjectSource(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid("name is required in ProjectSource"),
                scmReferenceId = IDComponent.create(
                    data.validateAndGet("scmReferenceId")
                        .isString()
                        .throwIfInvalid("scmReferenceId is required in ProjectSource")
                )
            )
        }
    }
}

data class LibrarySource(
    val name: String,
    val scmReferenceId: IDComponent,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<LibrarySource> {
        override fun create(data: Map<String, Any>): LibrarySource {
            return LibrarySource(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid("name is required in LibrarySource"),
                scmReferenceId = IDComponent.create(
                    data.validateAndGet("scmReferenceId")
                        .isString()
                        .throwIfInvalid("scmReferenceId is required in LibrarySource")
                )
            )
        }
    }
}

class PipelineFileSource(
    val name: String,
    val relativeScriptPath: Path,
    val scmReferenceId: IDComponent,
) : PipelineComponent {


}

class PipelineFileSourceCodeFactory : PipelineComponent {

    companion object : PipelineComponentFromMapFactory<PipelineFileSource> {
        override fun create(data: Map<String, Any>): PipelineFileSource {
            val name = data.validateAndGet("pipelineFileSource.name").isString()
                .throwIfInvalid("name is required in PipelineFileSource")
            val scmReferenceId = IDComponent.create(
                data.validateAndGet("pipelineFileSource.scmReferenceId")
                    .isString()
                    .throwIfInvalid("scmReferenceId is required in PipelineFileSource")
            )

            val relativeScriptPath = data.validateAndGet("pipelineFileSource.relativeScriptPath")
                .isString()
                .throwIfInvalid("scriptPath is required in PipelineFileSource")

            return PipelineFileSource(
                name = name,
                relativeScriptPath = Path.of(relativeScriptPath),
                scmReferenceId = scmReferenceId,
            )
        }
    }

}