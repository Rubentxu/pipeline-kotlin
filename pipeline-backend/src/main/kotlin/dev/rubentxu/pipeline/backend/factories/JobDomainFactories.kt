package dev.rubentxu.pipeline.backend.factories

import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.PipelineLogger

import dev.rubentxu.pipeline.model.steps.EnvVars
import dev.rubentxu.pipeline.model.validations.validateAndGet

class JobInstanceFactory(
): PipelineDomain {
    companion object : PipelineDomainFactory<JobInstance> {
        override fun create(data: Map<String, Any>): JobInstance {
            val envMap = data.validateAndGet("job.environmentVars").isMap()
                .throwIfInvalid("environmentVars is required in Job") as Map<String, String>
            val envVars = EnvVars(envMap)

            val trigger = data.validateAndGet("job.trigger").isMap().defaultValueIfInvalid(emptyMap<String, Any>())
            var cron: CronTrigger? = null
            if (trigger.containsKey("cron")) {
                cron = CromTriggerBuilder.create(data)
            }

            val parameters =
                data.validateAndGet("job.parameters")
                    .isList()
                    .defaultValueIfInvalid(emptyList<Map<String, Any>>())
                    .map { JobParameterFactory.create(it) }
            val sourceCodeRepositoryManager = SourceCodeRepositoryManagerFactory.create(data)




            return JobInstance(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Job"),
                environmentVars = envVars,
                publisher = PublisherFactory.create(
                    data.validateAndGet("publisher").isMap()
                        .throwIfInvalid("publisher is required in Job") as Map<String, Any>
                ),
                projectSource = ProjectSourceFactory.create(
                    data.validateAndGet("projectSource").isMap()
                        .throwIfInvalid("projectSource is required in Job") as Map<String, Any>
                ),
                pluginsDefinitionSource = data.validateAndGet("pluginsDefinitionSource")
                    .isList()
                    .defaultValueIfInvalid(emptyList<Map<String, Any>>())
                    .map { PluginsDefinitionSourceFactory.create(it) },


                trigger = cron,
                pipelineFileSource = PipelineFileSourceCodeFactory.create(data),
                sourceCodeRepositoryManager = sourceCodeRepositoryManager,
                logger = PipelineLogger.getLogger(),
                parameters = parameters,
            )
        }
    }
}

class JobParameterFactory {
    companion object : PipelineDomainFactory<JobParameter<*>> {
        override fun create(data: Map<String, Any>): JobParameter<*> {
            return when (data?.keys?.first()) {
                "string" -> StringJobParameterFactory.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "choice" -> ChoiceJobParameterFactory.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "boolean" -> BooleanJobParameterFactory.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "password" -> PasswordJobParameterFactory.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "text" -> TextJobParameterFactory.create(data.get(data?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid parameter type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}

class PublisherFactory {
    companion object : PipelineDomainFactory<Publisher> {
        override fun create(data: Map<String, Any>): Publisher {
            return Publisher(
                mailer = MailerFactory.create(
                    data.validateAndGet("mailer").isMap()
                        .throwIfInvalid("mailer is required in Publisher") as Map<String, Any>
                ),
                archiveArtifacts = ArchiveArtifactsFactory.create(
                    data.validateAndGet("archiveArtifacts").isMap()
                        .throwIfInvalid("archiveArtifacts is required in Publisher") as Map<String, Any>
                ),
                jUnitTestResults = data.validateAndGet("junit.testResults")
                    .isString()
                        .throwIfInvalid("junit is required in Publisher")

            )
        }
    }
}

class MailerFactory {
    companion object : PipelineDomainFactory<Mailer> {
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

class ArchiveArtifactsFactory {
    companion object : PipelineDomainFactory<ArchiveArtifacts> {
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



class StringJobParameterFactory {
    companion object : PipelineDomainFactory<StringJobParameter> {
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

class ChoiceJobParameterFactory {
    companion object : PipelineDomainFactory<ChoiceJobParameter> {
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

class BooleanJobParameterFactory {
    companion object : PipelineDomainFactory<BooleanJobParameter> {
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

class PasswordJobParameterFactory {
    companion object : PipelineDomainFactory<PasswordJobParameter> {
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

class TextJobParameterFactory {
    companion object : PipelineDomainFactory<TextJobParameter> {
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

class ProjectSourceFactory {
    companion object : PipelineDomainFactory<ProjectSource> {
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

class CromTriggerBuilder {
    companion object : PipelineDomainFactory<CronTrigger> {
        override fun create(data: Map<String, Any>): CronTrigger {
            return CronTrigger(
                cron = data.validateAndGet("cron").isString().throwIfInvalid("cron is required in Trigger")
            )
        }
    }
}