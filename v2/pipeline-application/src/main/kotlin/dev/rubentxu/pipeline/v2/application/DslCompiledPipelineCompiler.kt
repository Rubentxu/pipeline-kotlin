package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialBindingsPayload
import dev.rubentxu.pipeline.v2.domain.AgentSpec
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
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
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.toSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Transitional application adapter from the current DSL aggregate to the
 * canonical executable IR. It deliberately bypasses PipelineDefinition and
 * SpecDefinitionMapper; validator/planner migration is LFC1-005.
 *
 * LFC1-007: StepSpec.WriteFile is re-mapped to `core.file.writeFile` with typed payload.
 * Body-aware steps:
 * - catchError / warnError: compiled via [rewriteWorkflowControl] to the legacy linear
 *   sequence (core.emit.event enter + core.sh + core.emit.event trigger) until EM-5/EM-6
 *   semantics (failure suppression, UNSTABLE classification) are fully implemented.
 * - timeout / retry / dir / withCredentials: compiled to [BlockStepNode] with canonical
 *   body-execution IR (EM-4 substrate, JEP-020/021/022/029).
 * Unstable: compiled via [rewriteUnstable] to the legacy linear sequence.
 */
object DslCompiledPipelineCompiler {

    private const val COMPILER_VERSION = "dsl-compiler-v1"
    private const val PAYLOAD_SCHEMA_VERSION = "dsl-v1"

    private enum class WorkflowControlProjection(val token: String) {
        CatchError("catch-error"),
        WarnError("warn-error"),
    }

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
     * catchError/warnError/unstable → legacy linear sequence via rewriteWorkflowControl/rewriteUnstable.
     * timeout/retry/dir/withCredentials → [BlockStepNode] (EM-4 body-execution IR).
     * Terminal steps → [OpaqueStepNode].
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
     * Body-aware steps → BlockStepNode; terminal steps → OpaqueStepNode.
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
                projection = WorkflowControlProjection.CatchError,
                buildResult = step.buildResult,
                stageResult = step.stageResult,
                message = step.message,
                innerSteps = step.steps,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.WarnError -> rewriteWorkflowControl(
                projection = WorkflowControlProjection.WarnError,
                buildResult = "UNSTABLE", // forced per ADR-0054 §D5
                stageResult = "UNSTABLE",
                message = step.message,
                innerSteps = step.steps,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.TimeoutBlock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.RetryBlock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.Dir -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.WithCredentialsBlock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.Timestamps -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.WithEnv -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.Unstable -> rewriteUnstable(
                message = step.message,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            is StepSpec.Milestone -> listOf(
                OpaqueStepNode(
                    id = StepId("$parentToken/${stableToken(step.name)}-$occurrence"),
                    pluginStepId = PluginStepId("core.milestone"),
                    payload = VersionedStepPayload(
                        PAYLOAD_SCHEMA_VERSION,
                        milestonePayload(step.ordinal, step.label),
                    ),
                ),
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
     * Compiles a body-aware StepSpec into a canonical [BlockStepNode].
     *
     * The body steps are compiled recursively via [stepNodes] to produce the flat
     * canonical child sequence. The step's metadata (buildResult, time/unit, count, etc.)
     * is not encoded in the BlockStepNode itself — it lives in the [StepDescriptor] looked
     * up from [StepDescriptorRegistry.standard] and the runtime [ContextOverlay] pushed
     * by the coordinator when dispatching the block.
     *
     * JEP-029 (body-execution IR): BlockStepNode is the canonical representation
     * for all block-type steps. The coordinator's [CanonicalDurableRunCoordinator.dispatchBody]
     * handles the body children with proper per-child journal rows and context-stack
     * restoration.
     */
    private fun blockStepNode(step: StepSpec, parentToken: String, occurrence: Int): List<StepNode> {
        val tokenPrefix = stableToken(step.name)
        val stepId = StepId("$parentToken/${tokenPrefix}-body-$occurrence")

        val body = stepNodes(when (step) {
            is StepSpec.CatchError -> step.steps
            is StepSpec.WarnError -> step.steps
            is StepSpec.TimeoutBlock -> step.steps
            is StepSpec.RetryBlock -> step.steps
            is StepSpec.Dir -> step.steps
            is StepSpec.WithCredentialsBlock -> step.steps
            else -> emptyList()
        }, "$parentToken/${tokenPrefix}-body-$occurrence")

        return listOf(
            BlockStepNode(
                id = stepId,
                pluginStepId = PluginStepId("core.${step.name}"),
                payload = VersionedStepPayload(PAYLOAD_SCHEMA_VERSION, blockPayload(step)),
                body = body,
            )
        )
    }

    private fun blockPayload(step: StepSpec): String = when (step) {
        is StepSpec.Dir -> Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "dir")
            put("path", step.path)
        })
        is StepSpec.Timestamps -> Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "timestamps")
        })
        is StepSpec.WithEnv -> Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "withEnv")
            put("overrides", JsonArray(step.overrides.map { JsonPrimitive(it) }))
        })
        is StepSpec.WithCredentialsBlock -> CredentialBindingsPayload.encode(
            step.bindings.map { it.toSpec() },
        )
        else -> "{}"
    }

    /**
     * Pre-compiler rewrite for catchError / warnError blocks.
     *
     * Produces a linear sequence of nodes:
     * 1. `core.emit.event(kind="CatchErrorEntered", buildResult, enteredAt)` — entry marker (scope push)
     * 2. the projected inner scope — plain steps inlined into `set +e` shell wrapper(s);
     *    nested catchError/warnError children recursively rewritten INSIDE this scope
     *    (between enter and trigger) so they inherit the parent overlay
     * 3. `core.emit.event(kind="CatchErrorTriggered", emitted=true)` — exit marker (scope pop, always emitted)
     *
     * warnError is catchError(buildResult=UNSTABLE, stageResult=UNSTABLE) plus a
     * visible StageMarkedUnstable projection after the scope closes.
     *
     * Bare unstable children keep their existing semantics: lifted after the trigger
     * for catchError, projected as the warnError StageMarkedUnstable marker otherwise.
     * A shell wrapper runs its inlined steps with `set +e` so failures are captured
     * rather than aborting the script (FIND-DV-DUPL-01); segment boundaries between
     * markers stay covered by the coordinator's catch-error overlay continuation.
     */
    private fun rewriteWorkflowControl(
        projection: WorkflowControlProjection,
        buildResult: String?,
        stageResult: String?,
        message: String?,
        innerSteps: List<StepSpec>,
        parentToken: String,
        occurrence: Int,
    ): List<StepNode> {
        val effectiveBuildResult = buildResult?.uppercase() ?: "UNSTABLE"
        val effectiveStageResult = stageResult?.uppercase() ?: effectiveBuildResult
        val tokenPrefix = projection.token
        val scopeToken = "$parentToken/${tokenPrefix}-body-$occurrence"
        val liftedUnstable = when (projection) {
            WorkflowControlProjection.CatchError -> innerSteps.filterIsInstance<StepSpec.Unstable>()
            WorkflowControlProjection.WarnError -> emptyList()
        }
        val heredocSteps = innerSteps.filterNot { it is StepSpec.Unstable }
        val scopeNodes = projectInnerScope(heredocSteps, scopeToken)

        return buildList {
            // [0] Entry marker — signals scope entry to the coordinator (no event emitted)
            add(emitStep(
                stepId = "$parentToken/${tokenPrefix}-enter-$occurrence",
                eventKind = "CatchErrorEntered",
                payload = buildJsonObject {
                    put("buildResult", effectiveBuildResult)
                    put("stageResult", effectiveStageResult)
                    put("enteredAt", System.currentTimeMillis().toString())
                    // EM-5/EM-6: carry the message so the coordinator's overlay can publish the
                    // CatchErrorTriggered event at the real-failure fold (D5). Nullable/absent OK.
                    message?.let { put("message", it) }
                },
            ))
            // [1..n] Inner scope: plain shell segment(s) and nested catch groups in order
            addAll(scopeNodes)
            // [n+1] Exit marker (always emitted; shell exit code determines whether it "caught")
            add(emitStep(
                stepId = "$parentToken/${tokenPrefix}-trigger-$occurrence",
                eventKind = "CatchErrorTriggered",
                payload = buildJsonObject {
                    put("buildResult", effectiveBuildResult)
                    put("stageResult", effectiveStageResult)
                    put("message", message ?: "")
                    put("emitted", "true")
                },
            ))
            when (projection) {
                WorkflowControlProjection.CatchError -> liftedUnstable.forEachIndexed { innerOccurrence, unstable ->
                    addAll(
                        rewriteUnstable(
                            message = unstable.message,
                            parentToken = scopeToken,
                            occurrence = innerOccurrence,
                        ),
                    )
                }
                WorkflowControlProjection.WarnError -> add(emitStep(
                    stepId = "$parentToken/${tokenPrefix}-unstable-$occurrence",
                    eventKind = "StageMarkedUnstable",
                    payload = buildJsonObject {
                        put("message", message ?: "")
                    },
                ))
            }
        }
    }

    /**
     * Projects the inner steps of a workflow-control scope into canonical nodes.
     *
     * Without structured children the plain steps are inlined into a single
     * `set +e` shell wrapper with the scope's own step id, preserving the
     * historical single-node shape (including the empty retained wrapper).
     * With nested catchError/warnError children the scope is projected as ordered
     * segments: each plain run becomes its own shell wrapper and each structured
     * child is recursively rewritten inside the parent scope.
     *
     * Any other structured step kind fails compilation loudly — the compiler
     * never emits a silent shell comment.
     */
    private fun projectInnerScope(innerSteps: List<StepSpec>, scopeToken: String): List<StepNode> {
        // G1 (INC gate): `error` is also structured — it must abort the inner scope as a typed
        // core.error node so the coordinator's catchError overlay can catch it, never be inlined
        // as a silent shell comment by buildShellScript.
        val hasStructuredChild = innerSteps.any {
            it is StepSpec.CatchError || it is StepSpec.WarnError || it is StepSpec.Error
        }
        if (!hasStructuredChild) {
            return listOf(
                OpaqueStepNode(
                    id = StepId(scopeToken),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload(
                        PAYLOAD_SCHEMA_VERSION,
                        shellPayload(buildShellScript(innerSteps), isScriptBlock = true, returnStdout = false),
                    ),
                ),
            )
        }

        val nodes = mutableListOf<StepNode>()
        val plainRun = mutableListOf<StepSpec>()
        var shellSegment = 0
        var structuredOccurrence = 0

        fun flushPlainRun() {
            if (plainRun.isEmpty()) return
            nodes += OpaqueStepNode(
                id = StepId("$scopeToken-shell-$shellSegment"),
                pluginStepId = PluginStepId("core.sh"),
                payload = VersionedStepPayload(
                    PAYLOAD_SCHEMA_VERSION,
                    shellPayload(buildShellScript(plainRun.toList()), isScriptBlock = true, returnStdout = false),
                ),
            )
            shellSegment++
            plainRun.clear()
        }

        innerSteps.forEach { step ->
            when (step) {
                is StepSpec.CatchError -> {
                    flushPlainRun()
                    nodes += rewriteWorkflowControl(
                        projection = WorkflowControlProjection.CatchError,
                        buildResult = step.buildResult,
                        stageResult = step.stageResult,
                        message = step.message,
                        innerSteps = step.steps,
                        parentToken = scopeToken,
                        occurrence = structuredOccurrence,
                    )
                    structuredOccurrence++
                }
                is StepSpec.WarnError -> {
                    flushPlainRun()
                    nodes += rewriteWorkflowControl(
                        projection = WorkflowControlProjection.WarnError,
                        buildResult = "UNSTABLE", // forced per ADR-0054 §D5
                        stageResult = "UNSTABLE",
                        message = step.message,
                        innerSteps = step.steps,
                        parentToken = scopeToken,
                        occurrence = structuredOccurrence,
                    )
                    structuredOccurrence++
                }
                is StepSpec.Shell, is StepSpec.Echo, is StepSpec.WriteFile -> plainRun += step
                is StepSpec.Error -> {
                    // G1: project a structured `error` to a typed core.error abort node so the
                    // coordinator dispatches a Failure and the enclosing catchError overlay decides
                    // whether to suppress (default UNSTABLE) or propagate. Never a silent shell comment.
                    flushPlainRun()
                    nodes += OpaqueStepNode(
                        id = StepId("$scopeToken/error-$structuredOccurrence"),
                        pluginStepId = PluginStepId("core.error"),
                        payload = VersionedStepPayload(PAYLOAD_SCHEMA_VERSION, encodePayload(step)),
                    )
                    structuredOccurrence++
                }
                else -> throw IllegalStateException(
                    "Workflow-control scope '$scopeToken' cannot compile structured step '${step.name}' " +
                        "into its shell wrapper; only sh/echo/writeFile are embeddable and " +
                        "catchError/warnError/unstable are rewritten. Refusing to emit a silent shell comment.",
                )
            }
        }
        flushPlainRun()
        return nodes
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
     *
     * Fail-closed: a structured step kind that reaches this function has bypassed
     * the pre-compiler rewrite, so compilation fails loudly instead of emitting an
     * invalid silent shell comment.
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
                else -> throw IllegalStateException(
                    "buildShellScript cannot embed structured step '${step.name}' into a " +
                        "workflow-control shell wrapper; the pre-compiler rewrite must project it. " +
                        "Refusing to emit a silent shell comment.",
                )
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
        val payloadMap = payload.entries.associate { it.key to it.value.jsonPrimitive.contentOrNull }
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

    /** ML-R9 T-09: typed canonical milestone payload (ordinal required, label optional). */
    private fun milestonePayload(ordinal: Int, label: String?): String {
        return Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "milestone")
            put("ordinal", ordinal)
            if (label != null) put("label", label)
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
                is StepSpec.ArchiveArtifacts -> {
                    put("kind", "archiveArtifacts")
                    put("artifacts", step.artifacts)
                    put("allowEmptyArchive", step.allowEmptyArchive ?: false)
                    put("excludes", step.excludes)
                    put("fingerprint", step.fingerprint ?: false)
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
