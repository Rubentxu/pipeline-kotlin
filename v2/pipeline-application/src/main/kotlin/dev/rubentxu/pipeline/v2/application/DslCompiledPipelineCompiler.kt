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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Transitional application adapter from the current DSL aggregate to the
 * canonical executable IR. It deliberately bypasses PipelineDefinition and
 * SpecDefinitionMapper; validator/planner migration is LFC1-005.
 *
 * LFC1-007: StepSpec.WriteFile is re-mapped to `core.file.writeFile` with typed payload.
 * StepSpec.CatchError / WarnError / Unstable are pre-compiler-rewritten to a linear sequence
 * of `core.emit.event` marker steps + `core.sh` composition + `core.emit.event` post-marker steps.
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

    /**
     * Linearizes a list of step specs into a flat list of canonical step nodes.
     * Workflow-control steps (catchError / warnError / unstable) are pre-compiler-rewritten
     * into a linear sequence of `core.emit.event` + `core.sh` nodes.
     */
    private fun stepNodes(steps: List<StepSpec>, parentToken: String): List<StepNode> {
        val occurrences = mutableMapOf<String, Int>()
        return steps.flatMap { step ->
            val occurrence = occurrences.merge(step.name, 1, Int::plus)!! - 1
            stepNode(step, parentToken, occurrence)
        }
    }

    /**
     * Converts one StepSpec into one or more canonical StepNode IR nodes.
     * Normal steps → 1 node; workflow-control steps → multiple nodes via rewrite.
     */
    private fun stepNode(step: StepSpec, parentToken: String, occurrence: Int): List<StepNode> {
        return when (step) {
            is StepSpec.WriteFile -> listOf(
                OpaqueStepNode(
                    id = StepId("$parentToken/${stableToken(step.name)}-$occurrence"),
                    pluginStepId = PluginStepId("core.file.writeFile"),
                    payload = VersionedStepPayload(
                        PAYLOAD_SCHEMA_VERSION,
                        writeFilePayload(step.file, step.text, step.encoding),
                    ),
                ),
            )
            is StepSpec.CatchError -> rewriteWorkflowControl(
                kind = "catch-error",
                buildResult = step.buildResult,
                stageResult = step.stageResult,
                message = step.message,
                innerSteps = step.steps,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.WarnError -> rewriteWorkflowControl(
                kind = "warn-error",
                buildResult = "UNSTABLE", // forced per ADR-0054 §D5
                stageResult = "UNSTABLE",
                message = step.message,
                innerSteps = step.steps,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.Unstable -> rewriteUnstable(
                message = step.message,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            else -> listOf(
                OpaqueStepNode(
                    id = StepId("$parentToken/${stableToken(step.name)}-$occurrence"),
                    pluginStepId = PluginStepId("core.${step.name}"),
                    payload = VersionedStepPayload(PAYLOAD_SCHEMA_VERSION, encodePayload(step)),
                ),
            )
        }
    }

    /**
     * Pre-compiler rewrite for catchError / warnError blocks.
     *
     * Produces a linear sequence of 3 nodes:
     * 1. `core.emit.event(kind="CatchErrorTriggered", entered=true)` — entry marker
     * 2. `core.sh(isScriptBlock=true)` wrapping the inner steps — executed with `set +e`
     * 3. `core.emit.event(kind="CatchErrorTriggered", emitted=true)` — exit marker (always emitted,
     *    the shell script exit code determines whether the catch was triggered)
     *
     * The shell heredoc runs the inner steps with `set +e` so failures are captured rather than
     * aborting the script. The exit code is propagated so the coordinator sees the failure outcome.
     */
    private fun rewriteWorkflowControl(
        kind: String,
        buildResult: String?,
        stageResult: String?,
        message: String?,
        innerSteps: List<StepSpec>,
        parentToken: String,
        occurrence: Int,
    ): List<StepNode> {
        val effectiveBuildResult = buildResult?.uppercase() ?: "UNSTABLE"
        val effectiveStageResult = stageResult?.uppercase() ?: effectiveBuildResult
        val tokenPrefix = stableToken(kind)

        // Build a single shell script that runs ALL inner steps under `set +e` so failures are
        // captured rather than aborting the script. The coordinator sees one `core.sh` node
        // and its exit code; emitting the inner steps as separate canonical nodes would run
        // them outside `set +e` and abort before the catchError trigger marker could fire
        // (FIND-DV-DUPL-01).
        val innerScript = buildShellScript(innerSteps)
        val shellStepNode = OpaqueStepNode(
            id = StepId("$parentToken/${tokenPrefix}-body-$occurrence"),
            pluginStepId = PluginStepId("core.sh"),
            payload = VersionedStepPayload(
                PAYLOAD_SCHEMA_VERSION,
                shellPayload(innerScript, isScriptBlock = true, returnStdout = false),
            ),
        )

        return listOfNotNull(
            // [0] Entry marker
            emitStep(
                stepId = "$parentToken/${tokenPrefix}-enter-$occurrence",
                eventKind = "CatchErrorTriggered",
                payload = buildJsonObject {
                    put("buildResult", effectiveBuildResult)
                    put("stageResult", effectiveStageResult)
                    put("message", JsonNull) // null allowed
                },
            ),
            // [1] Inner steps wrapped in shell with set +e (single node, no double-emission)
            shellStepNode,
            // [2] Exit marker (always emitted; shell exit code determines whether it "caught")
            emitStep(
                stepId = "$parentToken/${tokenPrefix}-trigger-$occurrence",
                eventKind = "CatchErrorTriggered",
                payload = buildJsonObject {
                    put("buildResult", effectiveBuildResult)
                    put("stageResult", effectiveStageResult)
                    put("message", message ?: "")
                    put("emitted", "true")
                },
            ),
        )
    }

    /**
     * Pre-compiler rewrite for unstable(message).
     *
     * Produces a linear sequence of 2 nodes:
     * 1. `core.emit.event(kind="StageMarkedUnstable", message)` — marks the stage
     * 2. `core.sh("exit 0")` — ensures the step exits 0 so pipeline continues
     */
    private fun rewriteUnstable(
        message: String,
        parentToken: String,
        occurrence: Int,
    ): List<StepNode> = listOfNotNull(
        emitStep(
            stepId = "$parentToken/unstable-$occurrence",
            eventKind = "StageMarkedUnstable",
            payload = buildJsonObject {
                put("message", message)
            },
        ),
        OpaqueStepNode(
            id = StepId("$parentToken/unstable-exit-0-$occurrence"),
            pluginStepId = PluginStepId("core.sh"),
            payload = VersionedStepPayload(
                PAYLOAD_SCHEMA_VERSION,
                shellPayload("exit 0", isScriptBlock = false, returnStdout = false),
            ),
        ),
    )

    /**
     * Constructs a shell script heredoc that executes all inner steps sequentially
     * with `set +e` (continue on error) and explicit exit code propagation.
     */
    private fun buildShellScript(innerSteps: List<StepSpec>): String {
        val commands = innerSteps.map { step ->
            when (step) {
                is StepSpec.Shell -> {
                    val cmd = step.command.replace("'", "'\\''")
                    if (step.isScriptBlock) "sh -c '$cmd'" else cmd
                }
                is StepSpec.Echo -> {
                    val text = step.text.replace("'", "'\\''")
                    "echo '$text'"
                }
                is StepSpec.WriteFile -> {
                    val file = step.file.replace("'", "'\\''")
                    val text = step.text.replace("'", "'\\''")
                    "writeFile('$file', '$text', '${step.encoding}')"
                }
                else -> "// legacy step ${step.name} — pre-compiler should have rewritten this"
            }
        }
        return sequenceOf(
            "set +e",
            "set +o pipefail",
            "__rc=0",
            *commands.mapIndexed { idx, cmd -> "($cmd) || __rc=\$?; : done $idx" }.toTypedArray(),
            "exit \$__rc",
        ).joinToString("; ")
    }

    private fun emitStep(stepId: String, eventKind: String, payload: JsonObject): OpaqueStepNode {
        val payloadMap = payload.entries.associate { it.key to (it.value.toString().let { v -> if (v == "null") null else v }) }
        return OpaqueStepNode(
            id = StepId(stepId),
            pluginStepId = PluginStepId("core.emit.event"),
            payload = VersionedStepPayload(
                PAYLOAD_SCHEMA_VERSION,
                emitEventPayload(eventKind, payloadMap),
            ),
        )
    }

    private fun emitEventPayload(kind: String, fields: Map<String, String?>): String {
        val obj = buildJsonObject {
            put("kind", kind)
            fields.forEach { (k, v) ->
                if (v != null) put(k, v) else put(k, JsonNull)
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun writeFilePayload(file: String, text: String, encoding: String): String {
        return Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "writeFile")
            put("file", file)
            put("text", text)
            put("encoding", encoding)
        })
    }

    private fun shellPayload(command: String, isScriptBlock: Boolean, returnStdout: Boolean): String {
        return Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "sh")
            put("command", command)
            put("isScriptBlock", isScriptBlock)
            put("returnStdout", returnStdout)
        })
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
                is StepSpec.WriteFile -> {
                    put("kind", "writeFile")
                    put("file", step.file)
                    put("text", step.text)
                    put("encoding", step.encoding)
                }
                is StepSpec.CatchError -> {
                    put("kind", "catchError")
                    put("buildResult", step.buildResult ?: "")
                    put("stageResult", step.stageResult ?: "")
                    put("message", JsonNull)
                }
                is StepSpec.WarnError -> {
                    put("kind", "warnError")
                    put("message", step.message)
                }
                is StepSpec.Unstable -> {
                    put("kind", "unstable")
                    put("message", step.message)
                }
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
