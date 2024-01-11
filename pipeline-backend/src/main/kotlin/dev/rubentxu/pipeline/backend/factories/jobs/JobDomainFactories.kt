package dev.rubentxu.pipeline.backend.factories.jobs

import dev.rubentxu.pipeline.model.PipelineCollection
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.mapper.PropertySet
import dev.rubentxu.pipeline.model.validations.validateAndGet


class JobParameterFactory {
    companion object : PipelineDomainFactory<PipelineCollection<JobParameter<*>>> {
        override val rootPath: String = "pipeline.parameters"
        override val instanceName: String = "JobParameters"

        override suspend fun create(data: PropertySet): PipelineCollection<JobParameter<*>> {
            val parameters = getRootListObject(data)
                .map {
                    createJobParameter(it, it.keys.first())
                }

            return PipelineCollection(parameters)
        }

        suspend fun createJobParameter(data: Map<String, Any>, name: String): JobParameter<*> {
            return when (name) {
                "string" -> StringJobParameterFactory.create(data)
                "choice" -> ChoiceJobParameterFactory.create(data)
                "boolean" -> BooleanJobParameterFactory.create(data)
                "password" -> PasswordJobParameterFactory.create(data)
                "text" -> TextJobParameterFactory.create(data)
                else -> {
                    throw IllegalArgumentException("Invalid parameter type for '${name}'")
                }
            }
        }
    }
}


class StringJobParameterFactory {
    companion object : PipelineDomainFactory<StringJobParameter> {
        override var rootPath: String = "pipeline.parameters[index].string"
        override val instanceName: String = "StringJobParameter"

        override suspend fun create(data: PropertySet): StringJobParameter {

            return StringJobParameter(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name")),
                defaultValue = data.validateAndGet("defaultValue")
                    .isString()
                    .throwIfInvalid(getErrorMessage("defaultValue")),
                description = data.validateAndGet("description")
                    .isString()
                    .throwIfInvalid(getErrorMessage("description"))
            )
        }
    }
}

class ChoiceJobParameterFactory {
    companion object : PipelineDomainFactory<ChoiceJobParameter> {
        override var rootPath: String = "pipeline.parameters[index].choice"
        override val instanceName: String = "ChoiceJobParameter"

        override suspend fun create(data: PropertySet): ChoiceJobParameter {
            val choices = data.validateAndGet("choices")
                .isList()
                .throwIfInvalid(getErrorMessage("choices")) as List<String>

            val firstChoice = choices.first()

            return ChoiceJobParameter(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name")),
                defaultValue = data.validateAndGet("defaultValue")
                    .isString()
                    .defaultValueIfInvalid(firstChoice) as String,
                description = data.validateAndGet("description")
                    .isString()
                    .throwIfInvalid(getErrorMessage("description")),
                choices = choices
            )
        }
    }
}

class BooleanJobParameterFactory {

    companion object : PipelineDomainFactory<BooleanJobParameter> {
        override var rootPath: String = "pipeline.parameters[index].boolean"
        override val instanceName: String = "BooleanJobParameter"

        override suspend fun create(data: PropertySet): BooleanJobParameter {
            return BooleanJobParameter(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name")),
                defaultValue = data.validateAndGet("defaultValue")
                    .isBoolean()
                    .throwIfInvalid(getErrorMessage("defaultValue")),
                description = data.validateAndGet("description")
                    .isString()
                    .throwIfInvalid(getErrorMessage("description"))
            )
        }
    }
}

class PasswordJobParameterFactory {
    companion object : PipelineDomainFactory<PasswordJobParameter> {
        override var rootPath: String = "pipeline.parameters[index].password"
        override val instanceName: String = "PasswordJobParameter"
        override suspend fun create(data: PropertySet): PasswordJobParameter {
            return PasswordJobParameter(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name")),
                defaultValue = data.validateAndGet("defaultValue")
                    .isString()
                    .throwIfInvalid(getErrorMessage("defaultValue")),
                description = data.validateAndGet("description")
                    .isString()
                    .throwIfInvalid(getErrorMessage("description"))
            )
        }
    }
}

class TextJobParameterFactory {
    companion object : PipelineDomainFactory<TextJobParameter> {
        override var rootPath: String = "pipeline.parameters[index].text"
        override val instanceName: String = "TextJobParameter"
        override suspend fun create(data: PropertySet): TextJobParameter {
            return TextJobParameter(
                name = data.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name")),
                defaultValue = data.validateAndGet("defaultValue")
                    .isString()
                    .throwIfInvalid(getErrorMessage("defaultValue")),
                description = data.validateAndGet("description")
                    .isString()
                    .throwIfInvalid(getErrorMessage("description"))
            )
        }
    }
}

