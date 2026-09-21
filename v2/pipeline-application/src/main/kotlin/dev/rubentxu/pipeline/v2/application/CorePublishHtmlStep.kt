package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
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
 * `core.publishHTML` Step (WU-LPR-090 / Tier B #2).
 *
 * Copies workspace files matching an Ant-style `reportFiles` glob (default
 * recursive match) into the run-scoped reports archive under a sanitized
 * `reportName`, generating an `index.html` wrapper with one `<a>` link per
 * published file. The reports archive is a SIBLING of `stashes/` so that
 * `WorkspaceResolver.cleanupAfterComplete()` does not destroy published
 * reports when the producing stage finishes (F-ARCH-L7).
 *
 * Jenkins signature: `publishHTML(target)` where `target` is an
 * `HtmlPublisherTarget` in upstream Java. Pipeline-K keeps the public DSL
 * surface narrow and typed (see [publishHTML] in PipelineDsl.kt).
 *
 * ## Capability discipline
 *
 * The handler reaches ONLY [PublishHtmlOperations] through the
 * [PUBLISH_HTML_OPERATIONS_CAPABILITY] typed seam. It never touches `Files`,
 * `AntStyleGlob`, `MessageDigest`, or `EventSink` directly — those belong to
 * [PublishHtmlOperationsAdapter].
 *
 * ## Effect classification
 *
 * `Effect.WRITES_WORKSPACE` is declared because the handler physically
 * creates the run-scoped reports archive directory and copies files into it.
 *
 * `ReplayPolicy.MEMOIZED` is declared: fresh/rerun overwrites the existing
 * archive with the current snapshot (idempotent); resume/reuse reproduces the
 * persisted observation without re-publishing.
 */
object CorePublishHtmlStep {

    val KEY: PluginStepId = PluginStepId("core.publishHTML")

    /** Successful typed output: per-file summaries flow through the HtmlReportPublished event. */
    data class PublishHtmlOutput(
        val publishedCount: Int,
        val skipped: Boolean,
        val skipReason: PublishHtmlSkipReason?,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Success
    }

    /** Typed failure output (SCRIPT / INFRASTRUCTURE). */
    data class PublishHtmlFailureOutput(
        val failureKind: FailureKind,
        val message: String,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Failure(
            PipelineFailure(failureKind, message),
        )
    }

    private val inputCodec = object : StepCodec<PublishHtmlInput> {
        override fun encode(value: PublishHtmlInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("publishHTML"))
                        put("name", JsonPrimitive(value.name))
                        put("reportDir", JsonPrimitive(value.reportDir))
                        put("reportFiles", JsonPrimitive(value.reportFiles))
                        if (value.keepAll) put("keepAll", JsonPrimitive(true))
                        if (value.allowMissing) put("allowMissing", JsonPrimitive(true))
                        if (value.escapeUnderscores) put("escapeUnderscores", JsonPrimitive(true))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): PublishHtmlInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "publishHTML") {
                "core.publishHTML payload kind must be 'publishHTML'"
            }
            return PublishHtmlInput(
                name = obj.getValue("name").jsonPrimitive.content,
                reportDir = obj.getValue("reportDir").jsonPrimitive.content,
                reportFiles = obj.getValue("reportFiles").jsonPrimitive.content,
                keepAll = obj["keepAll"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                allowMissing = obj["allowMissing"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                escapeUnderscores = obj["escapeUnderscores"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
        }
    }

    private val outputCodec = object : StepCodec<TypedStepOutput> {
        override fun encode(value: TypedStepOutput): EncodedStepValue =
            when (value) {
                is PublishHtmlOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("publishHTML"))
                            put("publishedCount", JsonPrimitive(value.publishedCount))
                            put("skipped", JsonPrimitive(value.skipped))
                            value.skipReason?.let { put("skipReason", JsonPrimitive(it.name)) }
                        },
                    ),
                )
                is PublishHtmlFailureOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("publishHTML"))
                            put("outcome", JsonPrimitive("FAILED"))
                            put("failureKind", JsonPrimitive(value.failureKind.name))
                            put("message", JsonPrimitive(value.message))
                        },
                    ),
                )
                else -> throw IllegalArgumentException(
                    "core.publishHTML output codec cannot encode ${value::class.simpleName}",
                )
            }

        override fun decode(encoded: EncodedStepValue): TypedStepOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "publishHTML") {
                "core.publishHTML output payload kind must be 'publishHTML'"
            }
            val outcome = obj["outcome"]?.jsonPrimitive?.content
            if (outcome == "FAILED") {
                return PublishHtmlFailureOutput(
                    failureKind = FailureKind.valueOf(obj.getValue("failureKind").jsonPrimitive.content),
                    message = obj.getValue("message").jsonPrimitive.content,
                )
            }
            return PublishHtmlOutput(
                publishedCount = obj.getValue("publishedCount").jsonPrimitive.content.toInt(),
                skipped = obj["skipped"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                skipReason = obj["skipReason"]?.jsonPrimitive?.content?.let {
                    PublishHtmlSkipReason.valueOf(it)
                },
            )
        }
    }

    private val descriptor = StepDescriptor(
        stepId = "core.publishHTML",
        name = "publishHTML",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<PublishHtmlInput, TypedStepOutput> =
        StepHandler { input, ctx ->
            val ops: PublishHtmlOperations = ctx.capabilities.get(PUBLISH_HTML_OPERATIONS_CAPABILITY)
            when (val result = ops.publish(input)) {
                is PublishHtmlPublished -> PublishHtmlOutput(
                    publishedCount = result.entries.size,
                    skipped = false,
                    skipReason = null,
                )
                is PublishHtmlSkipped -> PublishHtmlOutput(
                    publishedCount = 0,
                    skipped = true,
                    skipReason = result.reason,
                )
                is PublishHtmlFailed -> PublishHtmlFailureOutput(
                    failureKind = result.failureKind,
                    message = result.message,
                )
            }
        }

    val definition: StepDefinition<PublishHtmlInput, TypedStepOutput> =
        object : StepDefinition<PublishHtmlInput, TypedStepOutput> {
            override val contract: StepContract<PublishHtmlInput, TypedStepOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(PUBLISH_HTML_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<PublishHtmlInput, TypedStepOutput> =
                capabilityRoutedHandler
        }

    /** Registers the Step through the same open registry seam as any external plugin. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
