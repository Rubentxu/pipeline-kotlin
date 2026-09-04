package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AgentSpec
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.DefinitionIdentityInput
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator
import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.OptionSpec
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Transitional application adapter from the current DSL aggregate to the
 * canonical executable IR. It deliberately bypasses PipelineDefinition and
 * SpecDefinitionMapper; validator/planner migration is LFC1-005.
 */
object DslCompiledPipelineCompiler {

    private const val COMPILER_VERSION = "dsl-compiler-v1"
    private const val PAYLOAD_SCHEMA_VERSION = "dsl-v1"

    fun compile(
        spec: PipelineSpec,
        sourcePath: String,
        sourceContent: String,
        pluginLockDigest: Digest,
    ): CompiledPipeline {
        require(sourcePath.isNotBlank()) { "sourcePath must not be blank" }
        val definitionId = DeterministicIdGenerator.definitionId(
            DefinitionIdentityInput(
                source = sourceContent,
                compatibilityVersion = COMPILER_VERSION,
                semanticInputs = mapOf("pluginLockDigest" to pluginLockDigest.value),
            ),
        )
        val stages = spec.stages.map(::stageNode)

        return CompiledPipeline(
            id = definitionId,
            source = SourceDescriptor(sourcePath, Digest(sha256(sourceContent))),
            stages = stages,
            pluginLockDigest = pluginLockDigest,
        )
    }

    private fun stageNode(stage: dev.rubentxu.pipeline.v2.dsl.StageSpec): StageNode {
        val stageToken = stableToken(stage.name)
        val stageId = StageId(stageToken)
        val body = when {
            stage.steps.size == 1 && stage.steps.single() is StepSpec.Parallel -> {
                val parallel = stage.steps.single() as StepSpec.Parallel
                StageBody.Parallel(
                    parallel.branches.map { branch ->
                        StageNode(
                            id = StageId("$stageToken/branch-${stableToken(branch.name)}"),
                            name = branch.name,
                            body = StageBody.Steps(
                                stepNodes(branch.steps, "$stageToken/branch-${stableToken(branch.name)}"),
                            ),
                        )
                    },
                )
            }
            stage.steps.any { it is StepSpec.Parallel } ->
                error("Stage '${stage.name}' cannot mix a parallel body with sibling steps")
            else -> StageBody.Steps(
                stepNodes(stage.steps, stageToken),
            )
        }

        return StageNode(
            id = stageId,
            name = stage.name,
            agent = stage.agent?.let { AgentSpec(it.label, it.remoteUri) },
            environment = stage.environment?.let(::EnvironmentSpec) ?: EnvironmentSpec.empty(),
            options = stage.options.toOptions(),
            body = body,
        )
    }

    private fun stepNodes(steps: List<StepSpec>, parentToken: String): List<StepNode> {
        val occurrences = mutableMapOf<String, Int>()
        return steps.map { step ->
            val occurrence = occurrences.merge(step.name, 1, Int::plus)!! - 1
            stepNode(step, parentToken, occurrence)
        }
    }

    private fun stepNode(step: StepSpec, parentToken: String, occurrence: Int): StepNode {
        val stepId = StepId("$parentToken/${stableToken(step.name)}-$occurrence")
        return OpaqueStepNode(
            id = stepId,
            pluginStepId = PluginStepId("core.${step.name}"),
            payload = VersionedStepPayload(PAYLOAD_SCHEMA_VERSION, encodePayload(step)),
        )
    }

    private fun encodePayload(step: StepSpec): String {
        val payload = buildJsonObject {
            put("kind", step.name)
            when (step) {
                is StepSpec.Echo -> put("text", step.text)
                is StepSpec.Shell -> {
                    put("command", step.command)
                    put("isScriptBlock", step.isScriptBlock)
                    put("returnStdout", step.returnStdout)
                }
                is StepSpec.Error -> {
                    put("message", step.message)
                    put("failureKind", step.failureKind)
                }
                is StepSpec.Sleep -> put("seconds", step.seconds)
                else -> put("declarativeValue", step.toString())
            }
        }
        return Json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun dev.rubentxu.pipeline.v2.dsl.OptionsSpec?.toOptions(): List<OptionSpec> {
        if (this == null) return emptyList()
        return buildList {
            timeout?.let { add(OptionSpec("timeout", it.toString())) }
            retry?.let { add(OptionSpec("retry", it.count.toString())) }
            if (skip) add(OptionSpec("skip", "true"))
        }
    }

    private fun stableToken(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { error("DSL names must contain at least one alphanumeric character: '$value'") }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
