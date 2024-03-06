package dev.rubentxu.pipeline.backend.factories.jobs


import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.optional
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.jobs.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class JobParameterFactory {

    companion object : PipelineDomainFactory<List<JobParameter<*>>> {
        override val rootPath: String = "pipeline.parameters"

        override suspend fun create(data: PropertySet): Result<List<JobParameter<*>>> = runCatching {
            val jobParametersProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val jobParameters = jobParametersProperties.map { properties ->
                    async { createJobParameter(properties) }
                }
                jobParameters.awaitAll()
            }
        }

        suspend fun createJobParameter(jobParametersProperties: PropertySet): JobParameter<*> {
            val name = jobParametersProperties.required<String>("name").getOrThrow()
            val defaultValue = jobParametersProperties.required<String>("defaultValue").getOrThrow()
            val description = jobParametersProperties.required<String>("description").getOrThrow()

            return StringJobParameter(
                name = name,
                defaultValue = defaultValue,
                description = description
            )
        }
    }
}

class StringJobParameterFactory {

    companion object : PipelineDomainFactory<List<StringJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].string"

        override suspend fun create(data: PropertySet): Result<List<StringJobParameter>> = runCatching {
            val stringJobParametersProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val stringJobParameters = stringJobParametersProperties.map { properties ->
                    async { createStringJobParameter(properties) }
                }
                stringJobParameters.awaitAll()
            }
        }

        suspend fun createStringJobParameter(stringJobParametersProperties: PropertySet): StringJobParameter {
            val name = stringJobParametersProperties.required<String>("name").getOrThrow()
            val defaultValue = stringJobParametersProperties.required<String>("defaultValue").getOrThrow()
            val description = stringJobParametersProperties.required<String>("description").getOrThrow()

            return StringJobParameter(
                name = name,
                defaultValue = defaultValue,
                description = description
            )
        }
    }
}

class ChoiceJobParameterFactory {

    companion object : PipelineDomainFactory<List<ChoiceJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].choice"

        override suspend fun create(data: PropertySet): Result<List<ChoiceJobParameter>> = runCatching {
            val choiceJobParametersProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val choiceJobParameters = choiceJobParametersProperties.map { properties ->
                    async { createChoiceJobParameter(properties) }
                }
                choiceJobParameters.awaitAll()
            }
        }

        suspend fun createChoiceJobParameter(choiceCredentials: PropertySet): ChoiceJobParameter {
            val name = choiceCredentials.required<String>("name").getOrThrow()
            val defaultValue = choiceCredentials.optional<String>("defaultValue").getOrThrow()
            val description = choiceCredentials.required<String>("description").getOrThrow()
            val choices = choiceCredentials.required<List<String>>("choices").getOrThrow()

            val firstChoice = choices.first()

            return ChoiceJobParameter(
                name = name,
                defaultValue = defaultValue ?: firstChoice,
                description = description,
                choices = choices
            )
        }
    }
}

class BooleanJobParameterFactory {

    companion object : PipelineDomainFactory<List<BooleanJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].boolean"

        override suspend fun create(data: PropertySet): Result<List<BooleanJobParameter>> = runCatching {
            val booleanJobParametersProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val booleanJobParameters = booleanJobParametersProperties.map { properties ->
                    async { createBooleanJobParameter(properties) }
                }
                booleanJobParameters.awaitAll()
            }
        }

        suspend fun createBooleanJobParameter(booleanCredentials: PropertySet): BooleanJobParameter {
            val name = booleanCredentials.required<String>("name").getOrThrow()
            val defaultValue = booleanCredentials.optional<Boolean>("defaultValue").getOrThrow()
            val description = booleanCredentials.required<String>("description").getOrThrow()

            return BooleanJobParameter(
                name = name,
                defaultValue = defaultValue,
                description = description
            )
        }
    }
}

class PasswordJobParameterFactory {

    companion object : PipelineDomainFactory<List<PasswordJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].password"

        override suspend fun create(data: PropertySet): Result<List<PasswordJobParameter>> = runCatching {
            val passwordJobParametersProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val passwordJobParameters = passwordJobParametersProperties.map { properties ->
                    async { createPasswordJobParameter(properties) }
                }
                passwordJobParameters.awaitAll()
            }
        }

        suspend fun createPasswordJobParameter(passwordCredentials: PropertySet): PasswordJobParameter {
            val name = passwordCredentials.required<String>("name").getOrThrow()
            val defaultValue = passwordCredentials.optional<String>("defaultValue").getOrThrow()
            val description = passwordCredentials.required<String>("description").getOrThrow()

            return PasswordJobParameter(
                name = name,
                defaultValue = defaultValue,
                description = description
            )
        }
    }
}

class TextJobParameterFactory {

    companion object : PipelineDomainFactory<List<TextJobParameter>> {
        override var rootPath: String = "pipeline.parameters[*].text"

        override suspend fun create(data: PropertySet): Result<List<TextJobParameter>> = runCatching {
            val textJobParametersProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val textJobParameters = textJobParametersProperties.map { properties ->
                    async { createTextJobParameter(properties) }
                }
                textJobParameters.awaitAll()
            }
        }

        suspend fun createTextJobParameter(textCredentials: PropertySet): TextJobParameter {
            val name = textCredentials.required<String>("name").getOrThrow()
            val defaultValue = textCredentials.optional<String>("defaultValue").getOrThrow()
            val description = textCredentials.required<String>("description").getOrThrow()

            return TextJobParameter(
                name = name,
                defaultValue = defaultValue,
                description = description
            )
        }
    }
}
