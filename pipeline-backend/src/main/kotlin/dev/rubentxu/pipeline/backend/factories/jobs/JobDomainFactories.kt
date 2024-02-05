package dev.rubentxu.pipeline.backend.factories.jobs

import arrow.core.raise.either
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.optional
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.Res
import dev.rubentxu.pipeline.model.jobs.*

class JobParameterFactory {

    companion object : PipelineDomainFactory<List<JobParameter<*>>> {
        override val rootPath: String = "pipeline.parameters"

        override suspend fun create(data: PropertySet): Res<List<JobParameter<*>>> =
            either {
                parZip(
                    { StringJobParameterFactory.create(data).bind() },
                    { ChoiceJobParameterFactory.create(data).bind() },
                    { BooleanJobParameterFactory.create(data).bind() },
                    { PasswordJobParameterFactory.create(data).bind() },
                    { TextJobParameterFactory.create(data).bind() },

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

        override suspend fun create(data: PropertySet): Res<List<StringJobParameter>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { stringCredentials ->
                        StringJobParameter(
                            name = stringCredentials.required("name"),
                            defaultValue = stringCredentials.required("defaultValue"),
                            description = stringCredentials.required("description")
                        )
                    }?: emptyList()
            }
    }
}

class ChoiceJobParameterFactory {

    companion object : PipelineDomainFactory<List<ChoiceJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].choice"

        override suspend fun create(data: PropertySet): Res<List<ChoiceJobParameter>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { choiceCredentials ->
                        val choices = choiceCredentials.required<List<String>>("choices")

                        val firstChoice = choices.first()
                        ChoiceJobParameter(
                            name = choiceCredentials.required("name"),
                            defaultValue = choiceCredentials.optional("defaultValue") ?: firstChoice,
                            description = choiceCredentials.required("description"),
                            choices = choices
                        )
                    }?: emptyList()
            }
    }
}

class BooleanJobParameterFactory {

    companion object : PipelineDomainFactory<List<BooleanJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].boolean"

        override suspend fun create(data: PropertySet): Res<List<BooleanJobParameter>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { booleanCredentials ->
                        BooleanJobParameter(
                            name = booleanCredentials.required("name"),
                            defaultValue = booleanCredentials.optional("defaultValue"),
                            description = booleanCredentials.required("description")
                        )
                    }?: emptyList()
            }
    }
}

class PasswordJobParameterFactory {

    companion object : PipelineDomainFactory<List<PasswordJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].password"

        override suspend fun create(data: PropertySet): Res<List<PasswordJobParameter>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { passwordCredentials ->
                        PasswordJobParameter(
                            name = passwordCredentials.required("name"),
                            defaultValue = passwordCredentials.optional("defaultValue"),
                            description = passwordCredentials.required("description")
                        )
                    }?: emptyList()
            }
    }
}

class TextJobParameterFactory {

    companion object : PipelineDomainFactory<List<TextJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].text"


        override suspend fun create(data: PropertySet): Res<List<TextJobParameter>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { textCredentials ->
                        TextJobParameter(
                            name = textCredentials.required("name"),
                            defaultValue = textCredentials.optional("defaultValue"),
                            description = textCredentials.required("description")
                        )
                    }?: emptyList()
            }
    }
}

