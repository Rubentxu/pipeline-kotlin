package dev.rubentxu.pipeline.backend.factories.jobs

import arrow.core.raise.result
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.backend.coroutines.parZipResult
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.optional
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.jobs.*

class JobParameterFactory {

    companion object : PipelineDomainFactory<List<JobParameter<*>>> {
        override val rootPath: String = "pipeline.parameters"

        override suspend fun create(data: PropertySet): Result<List<JobParameter<*>>> {
            return parZipResult(
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

    companion object : PipelineDomainFactory<List<StringJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].string"

        override suspend fun create(data: PropertySet): Result<List<StringJobParameter>> = result {
                getRootListPropertySet(data)
                    ?.parMap { stringCredentials ->
                      createStringJobParameter(stringCredentials).bind()
                    } ?: emptyList()
            }

        suspend fun createStringJobParameter(stringCredentials: PropertySet): Result<StringJobParameter> {
           return parZipResult(
                { stringCredentials.required<String>("name") },
                { stringCredentials.required<String>("defaultValue") },
                { stringCredentials.required<String>("description") }
            ) { name, defaultValue, description ->
                StringJobParameter(
                    name = name,
                    defaultValue = defaultValue,
                    description = description
                )
            }
        }
    }
}

class ChoiceJobParameterFactory {

    companion object : PipelineDomainFactory<List<ChoiceJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].choice"

        override suspend fun create(data: PropertySet): Result<List<ChoiceJobParameter>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { choiceCredentials ->
                       createChoiceJobParameter(choiceCredentials).bind()
                    } ?: emptyList()
            }

        suspend fun createChoiceJobParameter(choiceCredentials: PropertySet): Result<ChoiceJobParameter> {
            return parZipResult(
                { choiceCredentials.required<String>("name") },
                { choiceCredentials.optional<String>("defaultValue") },
                { choiceCredentials.required<String>("description") },
                { choiceCredentials.required<List<String>>("choices") }
            ) { name, defaultValue, description, choices ->
                val firstChoice = choices.first()
                ChoiceJobParameter(
                    name = name,
                    defaultValue = defaultValue?: firstChoice,
                    description = description,
                    choices = choices
                )
            }
        }
    }
}

class BooleanJobParameterFactory {

    companion object : PipelineDomainFactory<List<BooleanJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].boolean"

        override suspend fun create(data: PropertySet): Result<List<BooleanJobParameter>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { booleanCredentials ->
                        BooleanJobParameter(
                            name = booleanCredentials.required<String>("name").bind(),
                            defaultValue = booleanCredentials.optional<Boolean>("defaultValue").bind(),
                            description = booleanCredentials.required<String>("description").bind()
                        )
                    } ?: emptyList()
            }
    }
}

class PasswordJobParameterFactory {

    companion object : PipelineDomainFactory<List<PasswordJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].password"

        override suspend fun create(data: PropertySet): Result<List<PasswordJobParameter>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { passwordCredentials ->
                        PasswordJobParameter(
                            name = passwordCredentials.required<String>("name").bind(),
                            defaultValue = passwordCredentials.optional<String>("defaultValue").bind(),
                            description = passwordCredentials.required<String>("description").bind()
                        )
                    } ?: emptyList()
            }
    }
}

class TextJobParameterFactory {

    companion object : PipelineDomainFactory<List<TextJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].text"


        override suspend fun create(data: PropertySet): Result<List<TextJobParameter>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { textCredentials ->
                        TextJobParameter(
                            name = textCredentials.required<String>("name").bind(),
                            defaultValue = textCredentials.optional<String>("defaultValue").bind(),
                            description = textCredentials.required<String>("description").bind()
                        )
                    } ?: emptyList()
            }
    }
}

