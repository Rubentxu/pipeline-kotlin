package dev.rubentxu.pipeline.backend.factories.jobs

import arrow.core.raise.Raise
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.mapper.*


class JobParameterFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<List<JobParameter<*>>> {
        override val rootPath: PropertyPath = "pipeline.parameters".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): List<JobParameter<*>> {
            val parameters = getRootListPropertySet(data)
                .map {
                    val name = it.keys.first().propertyPath()
                    createJobParameter(name, it)
                }

            return parameters
        }

        suspend fun createJobParameter(path: PropertyPath, data: Map<String, Any?>): JobParameter<*> {
            return when (path) {
                StringJobParameterFactory.rootPath -> StringJobParameterFactory.create(data)
                ChoiceJobParameterFactory.rootPath -> ChoiceJobParameterFactory.create(data)
                BooleanJobParameterFactory.rootPath -> BooleanJobParameterFactory.create(data)
                PasswordJobParameterFactory.rootPath -> PasswordJobParameterFactory.create(data)
                TextJobParameterFactory.rootPath -> TextJobParameterFactory.create(data)
                else -> {
                    throw IllegalArgumentException("Invalid parameter type for '${path}'")
                }
            }
        }
    }
}


class StringJobParameterFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<StringJobParameter> {
        override var rootPath: PropertyPath = "string".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): StringJobParameter {
            val stringCredentials =  getRootPropertySet(data)
            return StringJobParameter(
                name = stringCredentials.required("name"),
                defaultValue = stringCredentials.required("defaultValue"),
                description = stringCredentials.required("description")
            )
        }
    }
}

class ChoiceJobParameterFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<ChoiceJobParameter> {
        override var rootPath: PropertyPath = "choice".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): ChoiceJobParameter {
            val choiceCredentials =  getRootPropertySet(data)
            val choices = choiceCredentials.required<List<String>>("choices")

            val firstChoice = choices.first()

            return ChoiceJobParameter(
                name = choiceCredentials.required("name"),
                defaultValue = choiceCredentials.optional("defaultValue")?: firstChoice,
                description = choiceCredentials.required("description"),
                choices = choices
            )
        }
    }
}

class BooleanJobParameterFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<BooleanJobParameter> {
        override var rootPath: PropertyPath = "boolean".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): BooleanJobParameter {
            val booleanCredentials =  getRootPropertySet(data)
            return BooleanJobParameter(
                name = booleanCredentials.required("name"),
                defaultValue = booleanCredentials.optional("defaultValue"),
                description = booleanCredentials.required("description")
            )
        }
    }
}

class PasswordJobParameterFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<PasswordJobParameter> {
        override var rootPath: PropertyPath = "password".propertyPath()

        override suspend fun create(data: PropertySet): PasswordJobParameter {
            val passwordCredentials =  getRootPropertySet(data)
            return PasswordJobParameter(
                name = passwordCredentials.required("name"),
                defaultValue = passwordCredentials.required("defaultValue"),
                description = passwordCredentials.required("description")
            )
        }
    }
}

class TextJobParameterFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<TextJobParameter> {
        override var rootPath: PropertyPath = "text".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): TextJobParameter {
            val textCredentials =  getRootPropertySet(data)
            return TextJobParameter(
                name = textCredentials.required("name"),
                defaultValue = textCredentials.required("defaultValue"),
                description = textCredentials.required("description")
            )
        }
    }
}

