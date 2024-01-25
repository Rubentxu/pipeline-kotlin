package dev.rubentxu.pipeline.backend.factories.jobs

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.*
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.PropertiesError

class JobParameterFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<JobParameter<*>>> {
        override val rootPath: String = "pipeline.parameters"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<JobParameter<*>> {
            return createJobParameter(data).bind()
        }

        context(Raise<PropertiesError>)
        suspend fun createJobParameter(
            data: PropertySet,
        ): Either<PropertiesError, List<JobParameter<*>>> =
            either {
                parZip(
                    { StringJobParameterFactory.create(data) },
                    { ChoiceJobParameterFactory.create(data) },
                    { BooleanJobParameterFactory.create(data) },
                    { PasswordJobParameterFactory.create(data) },
                    { TextJobParameterFactory.create(data) },

                    ) { stringJobParameter, choiceJobParameter, booleanJobParameter, passwordJobParameter, textJobParameter ->
                    buildList {
                        addAll(stringJobParameter)
                        addAll(choiceJobParameter)
                        addAll(booleanJobParameter)
                        addAll(passwordJobParameter)
                        addAll(textJobParameter)
                    }
                }
            }
    }
}

class StringJobParameterFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<StringJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].string"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<StringJobParameter> {
            return getRootListPropertySet(data)
                .parMap { stringCredentials ->
                    StringJobParameter(
                        name = stringCredentials.required("name"),
                        defaultValue = stringCredentials.required("defaultValue"),
                        description = stringCredentials.required("description")
                    )
                }
        }
    }
}

class ChoiceJobParameterFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<ChoiceJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].choice"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<ChoiceJobParameter> {
            return getRootListPropertySet(data)
                .parMap { choiceCredentials ->
                    val choices = choiceCredentials.required<List<String>>("choices")

                    val firstChoice = choices.first()
                    ChoiceJobParameter(
                        name = choiceCredentials.required("name"),
                        defaultValue = choiceCredentials.optional("defaultValue") ?: firstChoice,
                        description = choiceCredentials.required("description"),
                        choices = choices
                    )
                }
        }
    }
}

class BooleanJobParameterFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<BooleanJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].boolean"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<BooleanJobParameter> {
            return getRootListPropertySet(data)
                .parMap { booleanCredentials ->
                    BooleanJobParameter(
                        name = booleanCredentials.required("name"),
                        defaultValue = booleanCredentials.optional("defaultValue"),
                        description = booleanCredentials.required("description")
                    )
                }
        }
    }
}

class PasswordJobParameterFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<PasswordJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].password"

        override suspend fun create(data: PropertySet): List<PasswordJobParameter> {
            return getRootListPropertySet(data)
                .parMap { passwordCredentials ->
                    PasswordJobParameter(
                        name = passwordCredentials.required("name"),
                        defaultValue = passwordCredentials.optional("defaultValue"),
                        description = passwordCredentials.required("description")
                    )
                }
        }
    }
}

class TextJobParameterFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<TextJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].text"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<TextJobParameter> {
            return getRootListPropertySet(data)
                .parMap { textCredentials ->
                    TextJobParameter(
                        name = textCredentials.required("name"),
                        defaultValue = textCredentials.optional("defaultValue"),
                        description = textCredentials.required("description")
                    )
                }
        }
    }
}

