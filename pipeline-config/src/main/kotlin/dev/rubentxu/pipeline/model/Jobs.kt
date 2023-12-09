package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.MapConfigurationBuilder
import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.validation.validateAndGet

data class JobConfig(
    val name: String,
    val scm: Scm,
    val triggers: List<Trigger>,
    val scriptPath: String,
    val environmentVars: EnvVars,
    val publisher: Publisher,
    val parameters: List<Parameter>
): Configuration {

    companion object: MapConfigurationBuilder<JobConfig> {
        override fun build(data: Map<String, Any>): JobConfig {
            val envMap = data.validateAndGet("environmentVars").isMap().throwIfInvalid("environmentVars is required in Job") as Map<String, String>
            val envVars = EnvVars(envMap)
            return JobConfig(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Job"),
                scm = Scm.build(data.validateAndGet("scm").isMap().throwIfInvalid("scm is required in Job") as Map<String, Any>),
                triggers = (data.validateAndGet("triggers").isList().throwIfInvalid("triggers is required in Job") as List<Map<String, Any>>).map { Trigger.build(it) },
                scriptPath = data.validateAndGet("scriptPath").isString().throwIfInvalid("scriptPath is required in Job"),
                environmentVars = envVars,
                publisher = Publisher.build(data.validateAndGet("publisher").isMap().throwIfInvalid("publisher is required in Job") as Map<String, Any>),
                parameters = (data.validateAndGet("parameters").isList().throwIfInvalid("parameters is required in Job") as List<Map<String, Any>>).map { Parameter.build(it) }
            )
        }
    }
}

data class Scm(
    val git: Git
) : Configuration {
    companion object: MapConfigurationBuilder<Scm> {
        override fun build(data: Map<String, Any>): Scm {
            return Scm(
                git = Git.build(data.validateAndGet("git").isMap().throwIfInvalid("git is required in Scm") as Map<String, Any>)
            )
        }
    }
}

data class Git(
    val remote: Remote,
    val branches: List<String>
): Configuration {
    companion object: MapConfigurationBuilder<Git> {
        override fun build(data: Map<String, Any>): Git {
            return Git(
                remote = Remote.build(data.validateAndGet("remote").isMap().throwIfInvalid("remote is required in Git") as Map<String, Any>),
                branches = (data.validateAndGet("branches").isList().defaultValueIfInvalid(emptyList<String>()) as List<String>)
            )
        }
    }
}

data class Remote(
    val url: String,
    val credentialsId: String
): Configuration {
    companion object: MapConfigurationBuilder<Remote> {
        override fun build(data: Map<String, Any>): Remote {
            return Remote(
                url = data.validateAndGet("url").isString().throwIfInvalid("url is required in Remote"),
                credentialsId = data.validateAndGet("credentialsId").isString().throwIfInvalid("credentialsId is required in Remote")
            )
        }
    }
}

data class Trigger(
    val cron: String
) : Configuration {
    companion object: MapConfigurationBuilder<Trigger> {
        override fun build(data: Map<String, Any>): Trigger {
            return Trigger(
                cron = data.validateAndGet("cron").isString().throwIfInvalid("cron is required in Trigger")
            )
        }
    }
}

data class Publisher(
    val mailer: Mailer,
    val archiveArtifacts: ArchiveArtifacts,
    val junit: Junit
) : Configuration {
    companion object: MapConfigurationBuilder<Publisher> {
        override fun build(data: Map<String, Any>): Publisher {
            return Publisher(
                mailer = Mailer.build(data.validateAndGet("mailer").isMap().throwIfInvalid("mailer is required in Publisher") as Map<String, Any>),
                archiveArtifacts = ArchiveArtifacts.build(data.validateAndGet("archiveArtifacts").isMap().throwIfInvalid("archiveArtifacts is required in Publisher") as Map<String, Any>),
                junit = Junit.build(data.validateAndGet("junit").isMap().throwIfInvalid("junit is required in Publisher") as Map<String, Any>)
            )
        }
    }
}

sealed class Parameter: Configuration {
    abstract val name: String
    abstract val defaultValue: Any
    abstract val description: String

    companion object: MapConfigurationBuilder<Parameter> {
        override fun build(data: Map<String, Any>): Parameter {
            return when (data?.keys?.first()) {
                "string" -> StringParameter.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "choice" -> ChoiceParameter.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "boolean" -> BooleanParameter.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "password" -> PasswordParameter.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "text" -> TextParameter.build(data.get(data?.keys?.first()) as Map<String, Any>)
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
    val sendToIndividuals: Boolean
): Configuration {
    companion object: MapConfigurationBuilder<Mailer> {
        override fun build(data: Map<String, Any>): Mailer {
            return Mailer(
                recipients = data.validateAndGet("recipients").isString().throwIfInvalid("recipients is required in Mailer"),
                notifyEveryUnstableBuild = data.validateAndGet("notifyEveryUnstableBuild").isBoolean().throwIfInvalid("notifyEveryUnstableBuild is required in Mailer"),
                sendToIndividuals = data.validateAndGet("sendToIndividuals").isBoolean().throwIfInvalid("sendToIndividuals is required in Mailer")
            )
        }
    }
}

data class ArchiveArtifacts(
    val artifacts: String,
    val excludes: String,
    val fingerprint: Boolean,
    val onlyIfSuccessful: Boolean,
    val allowEmptyArchive: Boolean
): Configuration {
    companion object: MapConfigurationBuilder<ArchiveArtifacts> {
        override fun build(data: Map<String, Any>): ArchiveArtifacts {
            return ArchiveArtifacts(
                artifacts = data.validateAndGet("artifacts").isString().throwIfInvalid("artifacts is required in ArchiveArtifacts"),
                excludes = data.validateAndGet("excludes").isString().throwIfInvalid("excludes is required in ArchiveArtifacts"),
                fingerprint = data.validateAndGet("fingerprint").isBoolean().throwIfInvalid("fingerprint is required in ArchiveArtifacts"),
                onlyIfSuccessful = data.validateAndGet("onlyIfSuccessful").isBoolean().throwIfInvalid("onlyIfSuccessful is required in ArchiveArtifacts"),
                allowEmptyArchive = data.validateAndGet("allowEmptyArchive").isBoolean().throwIfInvalid("allowEmptyArchive is required in ArchiveArtifacts")
            )
        }
    }
}

data class Junit(
    val testResults: String
): Configuration {
    companion object: MapConfigurationBuilder<Junit> {
        override fun build(data: Map<String, Any>): Junit {
            return Junit(
                testResults = data.validateAndGet("testResults").isString().throwIfInvalid("testResults is required in Junit")
            )
        }
    }
}

data class StringParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String
) : Parameter() {
    companion object: MapConfigurationBuilder<StringParameter> {
        override fun build(data: Map<String, Any>): StringParameter {
            return StringParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in StringParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString().throwIfInvalid("defaultValue is required in StringParameter"),
                description = data.validateAndGet("description").isString().throwIfInvalid("description is required in StringParameter")
            )
        }
    }
}

data class ChoiceParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String,
    val choices: List<String>
) : Parameter() {
    companion object: MapConfigurationBuilder<ChoiceParameter> {
        override fun build(data: Map<String, Any>): ChoiceParameter {
            val choices = data.validateAndGet("choices").isList().throwIfInvalid("choices is required in ChoiceParameter") as List<String>
            val firstChoice = choices.first()
            return ChoiceParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in ChoiceParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString().defaultValueIfInvalid(firstChoice) as String,
                description = data.validateAndGet("description").isString().throwIfInvalid("description is required in ChoiceParameter"),
                choices = choices
            )
        }
    }
}

data class BooleanParameter(
    override val name: String,
    override val defaultValue: Boolean,
    override val description: String
) : Parameter() {
    companion object: MapConfigurationBuilder<BooleanParameter> {
        override fun build(data: Map<String, Any>): BooleanParameter {
            return BooleanParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in BooleanParameter"),
                defaultValue = data.validateAndGet("defaultValue").isBoolean().throwIfInvalid("defaultValue is required in BooleanParameter"),
                description = data.validateAndGet("description").isString().throwIfInvalid("description is required in BooleanParameter")
            )
        }
    }
}

data class PasswordParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String
) : Parameter() {
    companion object: MapConfigurationBuilder<PasswordParameter> {
        override fun build(data: Map<String, Any>): PasswordParameter {
            return PasswordParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in PasswordParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString().throwIfInvalid("defaultValue is required in PasswordParameter"),
                description = data.validateAndGet("description").isString().throwIfInvalid("description is required in PasswordParameter")
            )
        }
    }
}

data class TextParameter(
    override val name: String,
    override val defaultValue: String,
    override val description: String
) : Parameter() {
    companion object: MapConfigurationBuilder<TextParameter> {
        override fun build(data: Map<String, Any>): TextParameter {
            return TextParameter(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in TextParameter"),
                defaultValue = data.validateAndGet("defaultValue").isString().throwIfInvalid("defaultValue is required in TextParameter"),
                description = data.validateAndGet("description").isString().throwIfInvalid("description is required in TextParameter")
            )
        }
    }
}
