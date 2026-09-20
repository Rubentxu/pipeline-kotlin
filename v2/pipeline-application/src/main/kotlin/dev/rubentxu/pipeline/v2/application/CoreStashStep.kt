package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `core.stash` Step (WU-LPR-089 / Tier B #1 — first Tier B Step).
 *
 * Copies workspace files matching an Ant-style `includes` pattern into the
 * run-scoped stash directory, so a later stage (within the same run) can
 * restore them via [CoreUnstashStep].
 *
 * Jenkins signature: `stash(name, includes, excludes = "")`.
 *
 * ## Capability discipline
 *
 * The handler reaches ONLY [StashOperations] through the
 * [STASH_OPERATIONS_CAPABILITY] typed seam. It never touches `Files`,
 * `AntStyleGlob`, `MessageDigest`, or `EventSink` directly — those belong to
 * [StashOperationsAdapter].
 *
 * ## Effect classification
 *
 * `Effect.WRITES_WORKSPACE` is declared because the handler physically
 * modifies filesystem state (creates the run-scoped stash directory and copies
 * files into it). The stash directory lives OUTSIDE the stage workspace
 * (F-ARCH-L7 workspace-cleanup preservation invariant) but the WORKSPACE
 * effect class still applies because the durable side-effect is a filesystem
 * write scoped to this run.
 *
 * `ReplayPolicy.MEMOIZED` is declared: fresh/rerun overwrites the existing
 * stash with the current snapshot (idempotent); resume/reuse reproduces the
 * persisted observation without re-stashing.
 */
object CoreStashStep {

    val KEY: PluginStepId = PluginStepId("core.stash")

    /** Successful typed output: per-file summaries flow through the StashCreated event. */
    data class StashOutput(
        val stashedCount: Int,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Success
    }

    /** Typed failure output (SCRIPT / USER / INFRASTRUCTURE). */
    data class StashFailureOutput(
        val failureKind: FailureKind,
        val message: String,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Failure(
            dev.rubentxu.pipeline.v2.domain.PipelineFailure(failureKind, message),
        )
    }

    private val inputCodec = object : StepCodec<StashInput> {
        override fun encode(value: StashInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("stash"))
                        put("name", JsonPrimitive(value.name))
                        put("includes", JsonPrimitive(value.includes))
                        if (value.excludes.isNotEmpty()) {
                            put("excludes", JsonPrimitive(value.excludes))
                        }
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): StashInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "stash") {
                "core.stash payload kind must be 'stash'"
            }
            return StashInput(
                name = obj.getValue("name").jsonPrimitive.content,
                includes = obj.getValue("includes").jsonPrimitive.content,
                excludes = obj["excludes"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    private val outputCodec = object : StepCodec<TypedStepOutput> {
        override fun encode(value: TypedStepOutput): EncodedStepValue =
            when (value) {
                is StashOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("stash"))
                            put("stashedCount", JsonPrimitive(value.stashedCount))
                        },
                    ),
                )
                is StashFailureOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("stash"))
                            put("outcome", JsonPrimitive("FAILED"))
                            put("failureKind", JsonPrimitive(value.failureKind.name))
                            put("message", JsonPrimitive(value.message))
                        },
                    ),
                )
                else -> throw IllegalArgumentException(
                    "core.stash output codec cannot encode ${value::class.simpleName}",
                )
            }

        override fun decode(encoded: EncodedStepValue): TypedStepOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "stash") {
                "core.stash output payload kind must be 'stash'"
            }
            val outcome = obj["outcome"]?.jsonPrimitive?.content
            if (outcome == "FAILED") {
                return StashFailureOutput(
                    failureKind = FailureKind.valueOf(obj.getValue("failureKind").jsonPrimitive.content),
                    message = obj.getValue("message").jsonPrimitive.content,
                )
            }
            return StashOutput(
                stashedCount = obj.getValue("stashedCount").jsonPrimitive.content.toInt(),
            )
        }
    }

    private val descriptor = StepDescriptor(
        stepId = "core.stash",
        name = "stash",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<StashInput, TypedStepOutput> =
        StepHandler { input, ctx ->
            val ops: StashOperations = ctx.capabilities.get(STASH_OPERATIONS_CAPABILITY)
            when (val result = ops.stash(input)) {
                is StashSuccess -> StashOutput(stashedCount = result.entries.size)
                is StashFailed -> StashFailureOutput(
                    failureKind = result.failureKind,
                    message = result.message,
                )
                // StashRestoredResult is the unstash success case; cannot happen from .stash().
                is StashRestoredResult -> StashFailureOutput(
                    failureKind = FailureKind.ENGINE,
                    message = "core.stash adapter returned an unstash result for a stash call",
                )
            }
        }

    val definition: StepDefinition<StashInput, TypedStepOutput> =
        object : StepDefinition<StashInput, TypedStepOutput> {
            override val contract: StepContract<StashInput, TypedStepOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(STASH_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<StashInput, TypedStepOutput> =
                capabilityRoutedHandler
        }

    /** Registers the Step through the same open registry seam as any external plugin. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}

/**
 * `core.unstash` Step (WU-LPR-089 / Tier B #2 — paired with [CoreStashStep]).
 *
 * Restores files from a previously-produced stash into the current stage
 * workspace. Existing files are OVERWRITTEN (matching Jenkins `unstash`).
 *
 * Jenkins signature: `unstash(name)` (Pipeline-K extends with optional `into`).
 *
 * Capability discipline, effect class and replay policy mirror [CoreStashStep].
 */
object CoreUnstashStep {

    val KEY: PluginStepId = PluginStepId("core.unstash")

    /** Successful typed output: per-file entries flow through the StashRestored event. */
    data class UnstashOutput(
        val restoredCount: Int,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Success
    }

    /** Typed failure output (USER for missing stash, SCRIPT for illegal `into`, INFRASTRUCTURE for IO). */
    data class UnstashFailureOutput(
        val failureKind: FailureKind,
        val message: String,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Failure(
            dev.rubentxu.pipeline.v2.domain.PipelineFailure(failureKind, message),
        )
    }

    private val inputCodec = object : StepCodec<UnstashInput> {
        override fun encode(value: UnstashInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("unstash"))
                        put("name", JsonPrimitive(value.name))
                        value.into?.takeIf { it.isNotBlank() }?.let {
                            put("into", JsonPrimitive(it))
                        }
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): UnstashInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "unstash") {
                "core.unstash payload kind must be 'unstash'"
            }
            return UnstashInput(
                name = obj.getValue("name").jsonPrimitive.content,
                into = obj["into"]?.jsonPrimitive?.content,
            )
        }
    }

    private val outputCodec = object : StepCodec<TypedStepOutput> {
        override fun encode(value: TypedStepOutput): EncodedStepValue =
            when (value) {
                is UnstashOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("unstash"))
                            put("restoredCount", JsonPrimitive(value.restoredCount))
                        },
                    ),
                )
                is UnstashFailureOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("unstash"))
                            put("outcome", JsonPrimitive("FAILED"))
                            put("failureKind", JsonPrimitive(value.failureKind.name))
                            put("message", JsonPrimitive(value.message))
                        },
                    ),
                )
                else -> throw IllegalArgumentException(
                    "core.unstash output codec cannot encode ${value::class.simpleName}",
                )
            }

        override fun decode(encoded: EncodedStepValue): TypedStepOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "unstash") {
                "core.unstash output payload kind must be 'unstash'"
            }
            val outcome = obj["outcome"]?.jsonPrimitive?.content
            if (outcome == "FAILED") {
                return UnstashFailureOutput(
                    failureKind = FailureKind.valueOf(obj.getValue("failureKind").jsonPrimitive.content),
                    message = obj.getValue("message").jsonPrimitive.content,
                )
            }
            return UnstashOutput(
                restoredCount = obj.getValue("restoredCount").jsonPrimitive.content.toInt(),
            )
        }
    }

    private val descriptor = StepDescriptor(
        stepId = "core.unstash",
        name = "unstash",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<UnstashInput, TypedStepOutput> =
        StepHandler { input, ctx ->
            val ops: StashOperations = ctx.capabilities.get(STASH_OPERATIONS_CAPABILITY)
            when (val result = ops.unstash(input)) {
                is StashRestoredResult -> UnstashOutput(restoredCount = result.entries.size)
                is StashFailed -> UnstashFailureOutput(
                    failureKind = result.failureKind,
                    message = result.message,
                )
                // StashSuccess is the .stash() success case; cannot happen from .unstash().
                is StashSuccess -> UnstashFailureOutput(
                    failureKind = FailureKind.ENGINE,
                    message = "core.unstash adapter returned a stash result for an unstash call",
                )
            }
        }

    val definition: StepDefinition<UnstashInput, TypedStepOutput> =
        object : StepDefinition<UnstashInput, TypedStepOutput> {
            override val contract: StepContract<UnstashInput, TypedStepOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(STASH_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<UnstashInput, TypedStepOutput> =
                capabilityRoutedHandler
        }

    /** Registers the Step through the same open registry seam as any external plugin. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
