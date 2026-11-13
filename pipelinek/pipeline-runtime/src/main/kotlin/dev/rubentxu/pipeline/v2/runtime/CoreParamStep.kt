package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepArgument
import dev.rubentxu.pipeline.v2.domain.StepArgumentType
import dev.rubentxu.pipeline.v2.domain.StepArguments
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RUN_PARAMETERS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.RunParameterValues
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.param`.
 *
 * The name is the parameter the step asks for, and it is the NAME of a parameter the run's
 * pipeline declares: the step never invents a value for a name nobody declared.
 */
data class ParamInput(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "core.param needs the name of the parameter it reads" }
    }
}

/**
 * The typed output of `core.param`.
 *
 * Two shapes, and a reader has to act on both: the run carries a value for the parameter the
 * step asked for, or it does not. The second shape is a FAILURE and not an empty string: a
 * step that asked for a parameter the run does not declare has been told something that is
 * wrong, and answering it with a value nobody delivered would be inventing one.
 */
sealed interface ParamOutput : TypedStepOutput {

    /** The run carries the effective value of the parameter the step asked for. */
    data class Resolved(
        val name: String,
        val value: String,
    ) : ParamOutput {
        override val outcome: StepOutcome get() = StepOutcome.Success
    }

    /** The run declares no parameter under the name the step asked for. */
    data class NotDeclared(
        val name: String,
    ) : ParamOutput {
        override val outcome: StepOutcome get() = StepOutcome.Failure(
            PipelineFailure(
                FailureKind.USER,
                "the run declares no parameter named '$name'",
            ),
        )
    }
}

/**
 * The production definition of `core.param`.
 *
 * It is registered through the SAME mechanism an external plugin uses
 * ([CoreStepRegistryFactory]), and the registry is the only authority that resolves the key.
 *
 * ## What it reads
 *
 * The run's own parameters, through [RUN_PARAMETERS_CAPABILITY]: the carrier the capability
 * bridge populates from the run's policy. The handler does NOT read the process environment,
 * the command line or a file, and it does NOT know where the values came from — a value that
 * was delivered by a client and a value the pipeline declares as its own default arrive the
 * same way, and the step reads the EFFECTIVE value either way.
 *
 * ## What it does not do
 *
 * It does not export the value anywhere. The value is the step's own runtime value and
 * nothing else: it does not become a variable of the step's process, and it does not reach
 * the shell. A pipeline that wants the value in a command asks for it in that command.
 *
 * ## Effect classification
 *
 * {READ_ONLY} + MEMOIZED: the step observes a fact of the run and changes nothing. A replay
 * reproduces the persisted observation instead of reading the parameters again, so a resumed
 * run answers the same value the first attempt answered.
 */
object CoreParamStep {

    val KEY: PluginStepId = PluginStepId("core.param")

    private val inputCodec = object : StepCodec<ParamInput> {
        override fun encode(value: ParamInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("param"))
                        put("name", JsonPrimitive(value.name))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): ParamInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "param") {
                "core.param payload kind must be 'param'"
            }
            return ParamInput(name = obj.getValue("name").jsonPrimitive.content)
        }

        /** The arguments this step accepts, as the catalogue publishes them. */
        override fun schema(): String = StepArguments(
            arguments = listOf(
                StepArgument(
                    name = "name",
                    type = StepArgumentType.STRING,
                    required = true,
                    description = "the name of the parameter the step reads",
                ),
            ),
        ).jsonSchema("core.param")
    }

    private val outputCodec = object : StepCodec<ParamOutput> {
        override fun encode(value: ParamOutput): EncodedStepValue =
            when (value) {
                is ParamOutput.Resolved -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("param"))
                            put("outcome", JsonPrimitive("OK"))
                            put("name", JsonPrimitive(value.name))
                            put("value", JsonPrimitive(value.value))
                        },
                    ),
                )

                is ParamOutput.NotDeclared -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("param"))
                            put("outcome", JsonPrimitive("FAILED"))
                            put("name", JsonPrimitive(value.name))
                        },
                    ),
                )
            }

        override fun decode(encoded: EncodedStepValue): ParamOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "param") {
                "core.param output payload kind must be 'param'"
            }
            val name = obj.getValue("name").jsonPrimitive.content
            return if (obj["outcome"]?.jsonPrimitive?.content == "FAILED") {
                ParamOutput.NotDeclared(name = name)
            } else {
                ParamOutput.Resolved(
                    name = name,
                    value = obj.getValue("value").jsonPrimitive.content,
                )
            }
        }
    }

    private val descriptor = StepDescriptor(
        stepId = "core.param",
        name = "param",
        jenkinsDivergence = "Jenkins exposes a declared parameter as the VARIABLE params.<name>; here the value is read with the core.param step, and a " +
            "parameter the run does not declare is a failure and not an empty value. The eager DSL DECLARES the read and returns nothing: the value " +
            "stays inside the typed output of the step, and no value is knowable while the plan is built, because the effective value is the one the run " +
            "is given.",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    /**
     * The handler reads the run's parameters and answers with the value of the one it was
     * asked for.
     *
     * The absence of the value is a typed FAILURE and not an empty string: the run either
     * declares the parameter — and then carries its effective value, the delivered one or the
     * declared default — or it does not declare it at all, and the second fact has to reach
     * the author as a failure that names the parameter.
     */
    private val capabilityRoutedHandler: StepHandler<ParamInput, ParamOutput> =
        StepHandler { input, ctx ->
            val parameters: RunParameterValues =
                ctx.capabilities.get(RUN_PARAMETERS_CAPABILITY, RunParameterValues::class)
            val value = parameters.valueOf(input.name)
            if (value == null) {
                ParamOutput.NotDeclared(name = input.name)
            } else {
                ParamOutput.Resolved(name = input.name, value = value)
            }
        }

    val definition: StepDefinition<ParamInput, ParamOutput> =
        object : StepDefinition<ParamInput, ParamOutput> {
            override val contract: StepContract<ParamInput, ParamOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(RUN_PARAMETERS_CAPABILITY),
            )

            override val handler: StepHandler<ParamInput, ParamOutput> =
                capabilityRoutedHandler
        }

    /** Registers the definition through the same open registry seam as any external Step. */
    fun registerInto(builder: StepRegistryBuilder) {
        builder.add(definition)
    }
}
