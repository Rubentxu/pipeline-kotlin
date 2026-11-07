package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.runtime.ArchiveArtifactsInput
import dev.rubentxu.pipeline.v2.runtime.CoreArchiveArtifactsWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreDeleteDirWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreFileExistsInput
import dev.rubentxu.pipeline.v2.runtime.CoreFileExistsWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreInputInput
import dev.rubentxu.pipeline.v2.runtime.CoreInputWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreLockInput
import dev.rubentxu.pipeline.v2.runtime.CoreLockWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreReadFileInput
import dev.rubentxu.pipeline.v2.runtime.CoreReadFileWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreShellInput
import dev.rubentxu.pipeline.v2.runtime.CoreShellWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreWorkflowControlInput
import dev.rubentxu.pipeline.v2.runtime.CoreWorkflowControlWireCodec
import dev.rubentxu.pipeline.v2.runtime.CoreWriteFileInput
import dev.rubentxu.pipeline.v2.runtime.CoreWriteFileWireCodec
import dev.rubentxu.pipeline.v2.runtime.DeleteDirInput
import dev.rubentxu.pipeline.v2.runtime.durable.credentials.CredentialBindingsPayload
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionIdentityInput
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator
import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageOption
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.PostSpec
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.decideShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcomeFold
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.toSpec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInputCodec
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.ScmGitCheckoutKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Transitional application adapter from the current DSL aggregate to the
 * canonical executable IR. It deliberately bypasses PipelineDefinition and
 * SpecDefinitionMapper; validator/planner migration is
 * StepSpec.WriteFile is re-mapped to `core.file.writeFile` with typed payload.
 * The checkout lowers to the SCM/Git plugin's own step (`scm-git.checkout`):
 * the plugin owns that key and the wire format of its input, and this compiler
 * projects the domain's SCM carrier onto the plugin's codec instead of writing
 * the payload itself. The compiler therefore never invents a key for a step it
 * does not implement — it asks the step's owner for the one it registered.
 * Body-aware steps:
 * - catchError / warnError: compiled to [BlockStepNode] with canonical Body-execution IR.
 *   The scope's contract travels in the block payload ([CoreWorkflowControlWireCodec]) and
 *   its children travel in the block BODY, so the scope is executed by the canonical
 *   engine and NOT translated into a shell script.
 * - timeout / retry / dir / withCredentials: compiled to [BlockStepNode] with canonical
 * Body-execution IR (substrate)
 * Unstable: compiled via [rewriteUnstable] to the legacy linear sequence.
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
        // The pipeline's stage scope: sibling stages mint their identity into the
        // pipeline root, so two of them that normalize to the same token would share
        // one StageId. The scope is where that collision is caught.
        val stageScope = IdentityScope("the pipeline's stage scope")
        val stages = spec.stages.map { stageNode(it, stageScope) }

        return CompiledPipeline(
            id = definitionId,
            source = SourceDescriptor(sourcePath, Digest(sha256(sourceContent))),
            stages = stages,
            pluginLockDigest = pluginLockDigest,
        )
    }

    private fun stageNode(
        stage: dev.rubentxu.pipeline.v2.dsl.StageSpec,
        stageScope: IdentityScope,
    ): StageNode {
        val stageToken = stageScope.tokenFor(stage.name)
        val stageId = StageId(stageToken)
        val body = when {
            stage.steps.size == 1 && stage.steps.single() is StepSpec.Parallel -> {
                val parallel = stage.steps.single() as StepSpec.Parallel
                // Sibling branches mint their identity into the same parallel body,
                // so they share one scope.
                val branchScope = IdentityScope("stage '$stageToken' parallel branch scope")
                StageBody.Parallel(
                    parallel.branches.map { branch ->
                        val branchToken = "$stageToken/branch-${branchScope.tokenFor(branch.name)}"
                        StageNode(
                            id = StageId(branchToken),
                            name = branch.name,
                            body = StageBody.Steps(
                                stepNodes(branch.steps, branchToken),
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
            environment = stage.environment?.let(::EnvironmentSpec) ?: EnvironmentSpec.empty(),
            options = stage.options.toOptions(),
            body = body,
            directives = stage.directives,
            // The declared `post` block becomes typed IR data. The
            // COMPILER only projects; it never decides which finalizer runs —
            // that is the pure PostPlanner's job at execution time.
            post = stage.post.conditions
                .takeIf { it.isNotEmpty() }
                ?.let { declared ->
                    PostSpec(
                        conditions = declared.mapValues { (condition, steps) ->
                            stepNodes(steps, "$stageToken/post-${condition.name}")
                        },
                    )
                },
        )
    }

    /**
     * Linearizes a list of step specs into a flat list of canonical step nodes.
     * Every body-bearing family — catchError/warnError included — lowers through
     * [BlockStepNode] (body-execution IR), so a scope's children keep their own durable
     * rows instead of becoming text inside an invented shell script.
     * Terminal steps → [OpaqueStepNode].
*/
    private fun stepNodes(steps: List<StepSpec>, parentToken: String): List<StepNode> {
        val occurrences = mutableMapOf<String, Int>()
        // Sibling steps mint their identity into the same parent token, so they share
        // one scope.
        val scope = IdentityScope("the body of '$parentToken'")
        // The identities of the WHOLE sibling set are minted BEFORE any of them is
        // compiled.
        //
        // ## WHY THE SET IS MINTED FIRST
        //
        // A name collision is a fact about the SET: two siblings that normalize to the
        // same token would mint the SAME StepId, and the durable journal records that
        // identity as the step's own name. The set is complete before the first payload
        // is encoded, so the refusal about the set can arrive first -- and it is the
        // refusal that names BOTH names.
        //
        // A shape with no canonical payload is a fact about ONE member of the set, and
        // it is refused after. The order is deliberate and it is part of the contract:
        // the compiler refuses the set before it refuses a member of it.
        steps.forEach { scope.tokenFor(it.identityName) }
        return steps.flatMap { step ->
            // The counter follows the INNER name (see [identityName]): two named `sh` steps
            // are `sh-0` and `sh-1`. The name is what tells siblings apart.
            val identity = step.identityName
            val occurrence = occurrences[identity] ?: 0
            occurrences[identity] = occurrence + 1
            stepNode(step, parentToken, occurrence, scope)
        }
    }

    /**
     * The name that tells two siblings apart.
     *
     * A [StepSpec.Slot] is sugar: the step it names is the one that gets an identity, so
     * the occurrence counter follows the INNER name and not the wrapper's. Two named `sh`
     * steps are then `sh-0` and `sh-1`, exactly like two unnamed ones — and the name that
     * tells them apart is carried by the node, not by its position in a string.
     */
    private val StepSpec.identityName: String
        get() = when (this) {
            is StepSpec.Slot -> step.identityName
            else -> name
        }

    /**
     * Lowers a named slot. The step the slot NAMES is the step that runs, and the
     * name is the ADDRESS the plugin will use to reach it later.
     *
     * The name is independent of the kind: two steps of the same kind can live in one
     * body as long as the plugin named them apart.
     */
    private fun slotNode(
        step: StepSpec.Slot,
        parentToken: String,
        occurrence: Int,
        scope: IdentityScope,
    ): List<StepNode> {
        if (step.step is StepSpec.Slot) {
            error("A slot cannot name another slot: '${step.slotName}'")
        }
        val named = stepNode(step.step, parentToken, occurrence, scope)
        if (named.size != 1) {
            error(
                "A slot names exactly one step: '${step.slotName}' lowered to " +
                    "${named.size} steps",
            )
        }
        return listOf(named.single().withSlot(PluginStepId(step.slotName)))
    }

    /**
     * Converts one StepSpec into one or more canonical StepNode IR nodes.
     * Body-aware steps → BlockStepNode; terminal steps → OpaqueStepNode.
*/
    private fun stepNode(
        step: StepSpec,
        parentToken: String,
        occurrence: Int,
        scope: IdentityScope,
    ): List<StepNode> {
        return when (step) {
            // Carries the plugin StepKey + already
            // encoded input verbatim. The compiler NEVER knows a concrete external StepKey, never
            // resolves a StepDefinition, never decodes/re-encodes the typed input. Runtime
            // registry/preparation resolves it. Lowering to StructuralRegistry is the SAME family as
            // core.echo / core.sh.
            is StepSpec.RegistryStepSpec -> listOf(
                OpaqueStepNode(
                    id = StepId("$parentToken/${scope.tokenFor(step.name)}-$occurrence"),
                    pluginStepId = step.stepKey,
                    payload = VersionedStepPayload(
                        step.schemaVersion,
                        step.encodedInput.value,
                    ),
                ),
            )
            // Generic open-registry Block lowering. The plugin StepKey is carried
            // verbatim into a BlockStepNode; the canonical body children are compiled
            // recursively (the SAME lowering every core Block Step uses). Runtime admission
            // resolves the declared BodyExecutionPolicy from the open registry and rejects
            // fail-closed BEFORE any child runs. The compiler never knows a concrete key.
            is StepSpec.RegistryBlockSpec -> {
                val token = scope.tokenFor(step.name)
                listOf(
                    BlockStepNode(
                        id = StepId("$parentToken/$token-body-$occurrence"),
                        pluginStepId = step.stepKey,
                        payload = VersionedStepPayload(
                            step.schemaVersion,
                            step.encodedInput.value,
                        ),
                        body = stepNodes(step.body, "$parentToken/$token-body-$occurrence"),
                    ),
                )
            }
            is StepSpec.Slot -> slotNode(step, parentToken, occurrence, scope)
            is StepSpec.WriteFile -> listOf(
                writeFileNode(step, "$parentToken/${scope.tokenFor(step.name)}-$occurrence"),
            )
            // A control scope is a body-bearing step, exactly like `dir` or `retry`.
            // It used to be rewritten into a linear sequence of shell segments; now it
            // lowers through the SAME block machinery every other body family uses, and
            // its contract travels in the block payload.
            is StepSpec.CatchError -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            is StepSpec.WarnError -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            is StepSpec.TimeoutBlock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            is StepSpec.RetryBlock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            is StepSpec.Dir -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            is StepSpec.WithCredentialsBlock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            is StepSpec.Timestamps -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            is StepSpec.WithEnv -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            // WaitUntil projects to canonical BlockStepNode (body-owning).
            // The else -> OpaqueStepNode catch-all MUST NOT fire for this key.
            is StepSpec.WaitUntilBlock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            // Core.lock lowers through the SAME block machinery; the
            // Wire payload is produced exclusively by CoreLockWireCodec
            // The else -> OpaqueStepNode catch-all MUST NOT fire for this key.
            is StepSpec.Lock -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            // Core.input lowers through the SAME block machinery; the
            // Wire payload is produced exclusively by CoreInputWireCodec
            // The else -> OpaqueStepNode catch-all MUST NOT fire for this key.
            is StepSpec.Input -> blockStepNode(
                step = step,
                parentToken = parentToken,
                occurrence = occurrence,
                scope = scope,
            )
            // `unstable` mints a FIXED token ('unstable'), not one derived from an
            // author-controlled name, so it has no scope to mint into.
            is StepSpec.Unstable -> rewriteUnstable(
                message = step.message,
                parentToken = parentToken,
                occurrence = occurrence,
            )
            // StepSpec.Milestone has NO branch here, on purpose. The DSL
            // `milestone(ordinal, label)` lowers directly to StepSpec.RegistryStepSpec
            // (the open-world registry path), so the legacy shape carries no canonical
            // payload of its own and the generic arm refuses it.
            // The checkout is NOT a core Step: the SCM/Git plugin IMPLEMENTS it, under
            // the key `scm-git.checkout`, and the plugin owns that key AND the wire
            // format of its input. The core DSL's surface (`git(..)`, `checkout(..)`)
            // is what lowers to it, so the compiler names the key the step is
            // REGISTERED under — the one `RegistryStepMetadataResolver` can resolve —
            // instead of minting a `core.checkout` that no registry has ever contained.
            //
            // The old shape had two defects and they were the same defect seen twice:
            // the key it minted belonged to no step, and the payload it wrote by hand
            // was a second version of a format the plugin's codec already owned. A
            // checkout built by the core DSL compiled to a step that could not run.
            //
            // This branch is NOT a dispatch mechanism for plugin steps: a NEW official
            // step still arrives through `registryStep` (the plugin's own façade) and
            // needs no case here. What lowers here is the CORE's own surface.
            is StepSpec.Checkout -> listOf(
                OpaqueStepNode(
                    id = StepId("$parentToken/${scope.tokenFor(step.name)}-$occurrence"),
                    pluginStepId = ScmGitCheckoutKey.VALUE,
                    payload = VersionedStepPayload(PAYLOAD_SCHEMA_VERSION, checkoutPayload(step.scm)),
                ),
            )
            else -> opaqueNode(step, parentToken, occurrence, scope)
        }
    }

    /**
     * Lowers a terminal step into the one canonical opaque node.
     *
     * The identity is minted ONCE here and travels into the payload encoder, so a
     * refusal can name the step the author has to look at instead of saying "some
     * step somewhere".
     */
    private fun opaqueNode(
        step: StepSpec,
        parentToken: String,
        occurrence: Int,
        scope: IdentityScope,
    ): List<StepNode> {
        val stepId = StepId("$parentToken/${scope.tokenFor(step.name)}-$occurrence")
        return listOf(
            OpaqueStepNode(
                id = stepId,
                pluginStepId = PluginStepId("core.${step.name}"),
                payload = VersionedStepPayload(
                    PAYLOAD_SCHEMA_VERSION,
                    encodePayload(step, stepId),
                ),
            ),
        )
    }

    /**
     * Compiles a body-aware StepSpec into a canonical [BlockStepNode].
     * The body steps are compiled recursively via [stepNodes] to produce the flat
     * canonical child sequence. The step's DECLARED shape (which context it projects,
     * which fold it applies) lives in the [dev.rubentxu.pipeline.v2.domain.StepDescriptor]
     * looked up from `StepDescriptorRegistry.standard`; the VALUES the scope declared
     * travel in the block payload.
     * BlockStepNode is the canonical representation
     * for all block-type steps. `BodyExecutionEngine` handles the body children with
     * proper per-child journal rows and context-stack restoration.
     */
    /**
     * The same node, addressed by [slot].
     *
     * The name travels with the node because that is where the engine reads it: the node
     * IS the step, and the slot is one of its attributes.
     */
    private fun StepNode.withSlot(slot: PluginStepId): StepNode = when (this) {
        is OpaqueStepNode -> copy(slot = slot)
        is BlockStepNode -> copy(slot = slot)
    }

    private fun blockStepNode(
        step: StepSpec,
        parentToken: String,
        occurrence: Int,
        scope: IdentityScope,
    ): List<StepNode> {
        val tokenPrefix = scope.tokenFor(step.name)
        val stepId = StepId("$parentToken/${tokenPrefix}-body-$occurrence")

        val body = stepNodes(when (step) {
            is StepSpec.CatchError -> step.steps
            is StepSpec.WarnError -> step.steps
            is StepSpec.TimeoutBlock -> step.steps
            is StepSpec.RetryBlock -> step.steps
            is StepSpec.Dir -> step.steps
            is StepSpec.WithCredentialsBlock -> step.steps
            is StepSpec.WithEnv -> step.steps
            is StepSpec.Timestamps -> step.steps
            is StepSpec.WaitUntilBlock -> step.body
            // The lock body children compile like every other block
            // family member; missing this arm would silently drop the body.
            is StepSpec.Lock -> step.steps
            // Same law for the input body.
            is StepSpec.Input -> step.steps
            // The fallback used to be `emptyList()`, which compiled a body-bearing Step
            // with NO children and no complaint. A family that reaches this arm has no
            // canonical body, so the compilation refuses instead of shipping an empty
            // scope that would silently swallow its own content.
            else -> error(
                "Step '${step.name}' has no canonical body. A body-bearing Step must declare " +
                    "its children; refusing to compile a scope with an empty body.",
            )
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
        // A control scope carries its contract in the payload and its children in the
        // body. The compiler does NOT hand-write that JSON: CoreWorkflowControlWireCodec
        // is THE single authority for the format, the same law as core.lock, core.input
        // and core.waitUntil. Before the cut this table had no arm for the control
        // families at all and answered `{}`, so the scope's declared result travelled
        // nowhere and the compiler had to invent a shell script to carry it.
        is StepSpec.CatchError ->
            CoreWorkflowControlWireCodec.encode(step.toCoreWorkflowControlInput()).value
        is StepSpec.WarnError ->
            CoreWorkflowControlWireCodec.encode(step.toCoreWorkflowControlInput()).value
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
        // Core.retry projects its contract (attempt count) into the
        // payload so the durable coordinator can loop body attempts without
        // Interpreting StepSpec. Only maxAttempts is consumed (audit: the
        // retrofit retry policy's baseMs/jitterMs had no runtime consumer and
        // the surface was removed; the block form projects count only).
        // Core.timeout projects its deadline into the payload so the
        // durable coordinator can budget child dispatches without interpreting
        // StepSpec. Unit: DSL produces SECONDS/MINUTES/HOURS (Jenkins time/unit).
        is StepSpec.TimeoutBlock -> {
            val seconds = when (step.unit.uppercase()) {
                "SECONDS", "SECOND" -> step.time
                "MINUTES", "MINUTE" -> Math.multiplyExact(step.time, 60L)
                "HOURS", "HOUR" -> Math.multiplyExact(step.time, 3600L)
                else -> error("Unsupported timeout unit '${step.unit}' for core.timeout")
            }
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", "timeout")
                put("seconds", seconds)
            })
        }
        is StepSpec.RetryBlock -> Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "retry")
            put("maxAttempts", step.count)
        })
        // The compiler does NOT hand-write the waitUntil block
        // wire JSON. CoreWaitUntilWireCodec is THE single authority for that format,
        // The same law as core.lock and core.input. The encoder
        // is byte-identical to the JSON this branch used to build, so no durable
        // body identity moves (BodyStructureDigest appends the payload string).
        is StepSpec.WaitUntilBlock ->
            CoreWaitUntilWireCodec.encode(step.toCoreWaitUntilInput()).value
        // The compiler does NOT hand-write the core.lock wire JSON.
        // CoreLockWireCodec is THE single authority for that format; this is the
        // divergence core.sh suffered (kind=sh|shell, command|script) refused in
        // advance.
        is StepSpec.Lock -> CoreLockWireCodec.encode(step.toCoreLockInput()).value
        // Same single-authority law as core.lock. The compiler knows
        // the StepSpec.Input -> CoreInputInput transformation (a DOMAIN decision)
        // and delegates the ENCODING to CoreInputWireCodec.
        is StepSpec.Input -> CoreInputWireCodec.encode(step.toCoreInputInput()).value
        // The fallback used to be `"{}"`, an EMPTY contract that no reader could
        // distinguish from a family that had nothing to declare. Every body-bearing
        // family has an arm above; one that reaches this arm has no canonical
        // contract, and the compilation refuses instead of shipping one.
        else -> error(
            "Step '${step.name}' has no canonical block contract. A body-bearing Step must " +
                "declare its payload; refusing to compile one with an empty contract.",
        )
    }

    /**
     * The compiler knows the `StepSpec.CatchError -> CoreWorkflowControlInput`
     * transformation, which is a DOMAIN decision (what the scope declares); it does not
     * know the wire format, which is an ENCODING decision owned by
     * [CoreWorkflowControlWireCodec]. Pure, total, one direction.
     *
     * The two defaults are POLICY, inherited verbatim from the projection this replaces:
     * a scope that declares no `buildResult` absorbs the failure as `UNSTABLE`, and a
     * scope that declares no `stageResult` inherits whatever `buildResult` said. They are
     * now expressed over the closed [CatchErrorBuildResult] type instead of over a free
     * string that a runtime `else` read as `UNSTABLE` whatever it was.
     */
    private fun StepSpec.CatchError.toCoreWorkflowControlInput(): CoreWorkflowControlInput {
        val effectiveBuildResult = buildResult ?: CatchErrorBuildResult.Unstable
        return CoreWorkflowControlInput(
            fold = BodyOutcomeFold.CONTAINMENT,
            buildResult = effectiveBuildResult,
            stageResult = stageResult ?: effectiveBuildResult,
            message = message,
            // The DSL exposes no interruption flag for `catchError`; the family declares
            // that it contains them, exactly as the projection it replaces did.
            catchInterruptions = true,
        )
    }

    /**
     * The same transformation for `warnError`, whose two results are FIXED by the family:
     * a warning scope absorbs the failure as `UNSTABLE` and reports the stage as
     * `UNSTABLE`. The author cannot say otherwise, so the compiler does not invent a
     * value for it either — it states the family's own.
     */
    private fun StepSpec.WarnError.toCoreWorkflowControlInput(): CoreWorkflowControlInput =
        CoreWorkflowControlInput(
            fold = BodyOutcomeFold.OUTPUT_DECORATION,
            buildResult = CatchErrorBuildResult.Unstable,
            stageResult = CatchErrorBuildResult.Unstable,
            message = message,
            catchInterruptions = catchInterruptions,
        )

    /**
     * The compiler knows the `StepSpec.Input -> CoreInputInput`
     * transformation, which is a DOMAIN decision; it does not know the wire
     * format, which is an ENCODING decision owned by [CoreInputWireCodec]. Pure,
     * total, one direction, and it does NOT resolve a blank message here: that
     * rule belongs to `inputIntentOf`, applied once at the Step.
*/
    private fun StepSpec.Input.toCoreInputInput(): CoreInputInput = CoreInputInput(
        message = message,
        ok = ok,
        submitter = submitter,
        id = id,
        timeoutSeconds = timeoutSeconds,
    )

    /**
     * The compiler knows the `StepSpec.Lock -> CoreLockInput`
     * transformation, which is a DOMAIN decision (what the step takes as
     * input); it does not know the wire format, which is an ENCODING decision
     * owned by [CoreLockWireCodec]. Pure, total, one direction, no defaults
     * invented here: every field maps verbatim.
*/
    private fun StepSpec.Lock.toCoreLockInput(): CoreLockInput = CoreLockInput(
        resource = resource,
        timeoutSeconds = timeoutSeconds,
        reason = reason,
        skipIfLocked = skipIfLocked,
    )

    /**
     * The compiler knows the `StepSpec.WaitUntilBlock ->
     * CoreWaitUntilInput` transformation, which is a DOMAIN decision (what the
     * block declares); it does not know the wire format, which is an ENCODING
     * decision owned by [CoreWaitUntilWireCodec]. Pure, total, one direction, no
     * defaults invented here: every field maps verbatim.
*/
    private fun StepSpec.WaitUntilBlock.toCoreWaitUntilInput(): CoreWaitUntilInput = CoreWaitUntilInput(
        initialRecurrencePeriod = initialRecurrencePeriod,
        maxBackoffMs = maxBackoffMs,
    )

    /**
     * The compiler knows the `StepSpec.Shell -> CoreShellInput` transformation,
     * which is a DOMAIN decision (what the step declares); it does not know the
     * wire format, which is an ENCODING decision owned by [CoreShellWireCodec].
     * Pure, total, one direction, no defaults invented here: every field maps
     * verbatim.
    */
    private fun StepSpec.Shell.toCoreShellInput(): CoreShellInput = coreShellInput(
        command = command,
        isScriptBlock = isScriptBlock,
        returnStdout = returnStdout,
    )

    /**
     * The same DOMAIN decision for a command the compiler builds itself (the
     * inlined shell wrappers): it is the declaration the DSL makes, spelled the
     * same way. `returnStdout` is the only return vocabulary the declarative
     * dialect has, so the interior's return mode is NONE or STDOUT.
    */
    private fun coreShellInput(command: String, isScriptBlock: Boolean, returnStdout: Boolean): CoreShellInput =
        CoreShellInput(
            command = ShellCommand(
                script = command,
                returnMode = decideShellReturnMode(returnStdout = returnStdout, returnStatus = false),
            ),
            isScriptBlock = isScriptBlock,
        )

    /**
     * The compiler knows the `StepSpec.WriteFile -> CoreWriteFileInput`
     * transformation, which is a DOMAIN decision (what the step declares); it does
     * not know the wire format, which is an ENCODING decision owned by
     * [CoreWriteFileWireCodec]. Pure, total, one direction, no defaults invented
     * here: every field maps verbatim.
     *
     * The projection INHERITS the family's own invariant instead of re-deriving
     * it: [CoreWriteFileInput] refuses a blank `file`, so a pipeline that declares
     * one is refused at COMPILE time instead of producing a payload the runtime
     * would reject when the step runs. It is the same refusal, with the same
     * message, by the same authority — it only arrives earlier, at the door that
     * accepts the shape.
     */
    private fun StepSpec.WriteFile.toCoreWriteFileInput(): CoreWriteFileInput = CoreWriteFileInput(
        file = file,
        text = text,
        encoding = encoding,
    )

    /**
     * The compiler knows the `StepSpec.ReadFile -> CoreReadFileInput`
     * transformation, which is a DOMAIN decision (what the step declares); it does
     * not know the wire format, which is an ENCODING decision owned by
     * [CoreReadFileWireCodec]. Pure, total, one direction, no defaults invented
     * here: every field maps verbatim.
     */
    private fun StepSpec.ReadFile.toCoreReadFileInput(): CoreReadFileInput = CoreReadFileInput(
        file = file,
        encoding = encoding,
    )

    /**
     * The compiler knows the `StepSpec.FileExists -> CoreFileExistsInput`
     * transformation, which is a DOMAIN decision (what the step declares); it does
     * not know the wire format, which is an ENCODING decision owned by
     * [CoreFileExistsWireCodec]. Pure, total, one direction, no defaults invented
     * here: every field maps verbatim.
     */
    private fun StepSpec.FileExists.toCoreFileExistsInput(): CoreFileExistsInput = CoreFileExistsInput(
        file = file,
    )

    /**
     * The compiler knows the `StepSpec.DeleteDir -> DeleteDirInput` transformation,
     * which is a DOMAIN decision (what the step declares); it does not know the
     * wire format, which is an ENCODING decision owned by
     * [CoreDeleteDirWireCodec]. Pure, total, one direction: the path maps
     * verbatim, including the family's own default of `"."`.
     */
    private fun StepSpec.DeleteDir.toCoreDeleteDirInput(): DeleteDirInput = DeleteDirInput(path = path)

    /**
     * The compiler knows the `StepSpec.ArchiveArtifacts -> ArchiveArtifactsInput`
     * transformation, which is a DOMAIN decision (what the step declares); it does
     * not know the wire format, which is an ENCODING decision owned by
     * [CoreArchiveArtifactsWireCodec].
     *
     * The two nullable flags carry the DSL's OWN default: a `null` means "not
     * declared", and what "not declared" means is `false` — the value the DSL
     * itself passes, and the value this projection's predecessor has always
     * written. The wire carries the DECLARED boolean in both cases, so the bytes
     * do not move.
     */
    private fun StepSpec.ArchiveArtifacts.toCoreArchiveArtifactsInput(): ArchiveArtifactsInput =
        ArchiveArtifactsInput(
            artifacts = artifacts,
            allowEmptyArchive = allowEmptyArchive ?: false,
            excludes = excludes,
            fingerprint = fingerprint ?: false,
            name = artifactName,
        )

    /**
     * The typed node for a `writeFile`, built in ONE place.
     *
     * The stage level and the workflow-control scope level project the SAME
     * capability through this function: one plugin step id, one payload schema
     * version, one payload vocabulary. A second construction site would be a
     * second authority for writing files.
     *
     * The ENCODING is not this function's decision either: [CoreWriteFileWireCodec]
     * is THE single authority for the `core.file.writeFile` wire format, and the
     * bytes it writes are the bytes this node has always carried, so no durable
     * body identity moves.
     */
    private fun writeFileNode(step: StepSpec.WriteFile, stepId: String): OpaqueStepNode =
        OpaqueStepNode(
            id = StepId(stepId),
            pluginStepId = PluginStepId("core.file.writeFile"),
            payload = VersionedStepPayload(
                PAYLOAD_SCHEMA_VERSION,
                CoreWriteFileWireCodec.encode(step.toCoreWriteFileInput()).value,
            ),
        )

    /**
     * Pre-compiler rewrite for unstable(message).
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

    // MilestonePayload removed (LEGACY_REMOVED). The canonical envelope for
    // core.milestone is produced by the DSL `milestone(...)` function (scripting-api) which
    // lowers to StepSpec.RegistryStepSpec; the runtime decoder/handler is CoreMilestoneStep
    // via the registry. No compiler-side payload construction needed.

    /**
     * The compiler does NOT hand-write the `core.sh` wire JSON.
     * [CoreShellWireCodec] is THE single authority for that format: the compiler
     * knows the DOMAIN decision (which command, whether it is a script block, and
     * whether stdout is returned) and delegates the ENCODING to the codec. The
     * bytes are the ones this function has always written, so no durable body
     * identity moves.
    */
    private fun shellPayload(command: String, isScriptBlock: Boolean, returnStdout: Boolean): String =
        CoreShellWireCodec.encodeDeclarative(coreShellInput(command, isScriptBlock, returnStdout)).value

    /**
     * The payload of a checkout step: the bytes the SCM plugin's OWN codec writes.
     *
     * The compiler does not translate the carrier field by field. It projects the
     * domain's [GitScm] onto the codec that owns the format, so the bytes a
     * checkout built by the core DSL carries are the SAME bytes the plugin's own
     * façade produces for the same checkout — which is what makes the two paths
     * one step instead of two spellings that can drift apart.
     *
     * The projection is EXHAUSTIVE over the sealed [Scm] family on purpose. A
     * second kind of SCM breaks this compilation instead of falling into a
     * default that would compile somebody else's checkout with the wrong
     * defaults and a URL nobody declared.
     */
    private fun checkoutPayload(scm: Scm): String {
        val git: GitScm = when (scm) {
            is GitScm -> scm
        }
        return GitCheckoutInputCodec.encode(git).value
    }

    /**
     * The families whose wire a codec already owns, delegated in ONE place.
     *
     * The compiler does NOT hand-write these envelopes: for each family the codec
     * is THE single authority for the format, and the compiler projects the
     * declared step onto the typed input and delegates the ENCODING to that codec.
     * The bytes are the ones this table has always written, so no durable body
     * identity moves.
     *
     * A family that reaches the `null` of this function has no codec to delegate
     * to, and its envelope is written by the dispatch table of [encodePayload] —
     * with the reasons that table carries.
     *
     * The `writeFile` family is NOT in this table on purpose: its producer is
     * [writeFileNode], which mints a different plugin key (`core.file.writeFile`)
     * and therefore does not travel through [encodePayload]. An arm here would be
     * a writer that never writes, and a writer that never writes can disagree with
     * the live one for ever without anything noticing — which is exactly the
     * defect this delegation retires.
     */
    private fun delegatedPayload(step: StepSpec): String? = when (step) {
        is StepSpec.ReadFile -> CoreReadFileWireCodec.encode(step.toCoreReadFileInput()).value
        is StepSpec.FileExists -> CoreFileExistsWireCodec.encode(step.toCoreFileExistsInput()).value
        is StepSpec.DeleteDir -> CoreDeleteDirWireCodec.encode(step.toCoreDeleteDirInput()).value
        is StepSpec.ArchiveArtifacts ->
            CoreArchiveArtifactsWireCodec.encode(step.toCoreArchiveArtifactsInput()).value
        else -> null
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "reason: dispatch table over a closed StepSpec hierarchy — one branch " +
            "per StepSpec subtype, each naming the canonical envelope of its " +
            "core Step. Splitting the table would scatter one envelope per " +
            "file and make the shape of a Step unreadable in one place."
    )
    private fun encodePayload(step: StepSpec, stepId: StepId): String {
        // The compiler does NOT hand-write the `core.sh` wire JSON here either:
        // CoreShellWireCodec is THE single authority for that format, and the
        // bytes it writes are the ones this branch has always written.
        if (step is StepSpec.Shell) {
            return CoreShellWireCodec.encodeDeclarative(step.toCoreShellInput()).value
        }
        // The checkout is the same case: GitCheckoutInputCodec is THE single
        // authority for the `scm-git.checkout` input format, and the bytes it
        // writes are the bytes the plugin's own handler decodes.
        if (step is StepSpec.Checkout) {
            return checkoutPayload(step.scm)
        }
        // The families whose wire a codec owns are delegated the same way: the
        // compiler projects the declared step onto the typed input and the codec
        // writes the bytes. This is the ONE place that names them, so a family
        // cannot be written by hand in the table below and by its codec here.
        delegatedPayload(step)?.let { return it }
        val payload = buildJsonObject {
            put("kind", step.name)
            when (step) {
                is StepSpec.Echo -> put("text", step.text)
                is StepSpec.Error -> {
                    put("message", step.message)
                    // `failureKind` is typed, and the wire is not. `FailureKind.name` IS
                    // the historical token for every case, so this projection is byte-identical to
                    // the String it replaces — which is the point. The String that used to live
                    // here was the last place a typed decision was degraded before it was
                    // re-parsed by `CoreErrorStep`'s decoder.
                    put("failureKind", step.failureKind.name)
                }
                is StepSpec.Sleep -> put("seconds", step.seconds)

                is StepSpec.Unstable -> {
                    put("kind", "unstable")
                    put("message", step.message)
                }

                // Canonical envelope for core.pwd. The
                // CorePwdStep.inputCodec reads {"kind":"pwd","tmp":<bool>} and
                // decodes tmp=true as a PWD_TMP_TRUE_DISPOSITION failure
                // (fail-closed). The generic arm used to produce the legacy
                // {"kind":"pwd","declarativeValue":"StepSpec.Pwd(tmp=…)"} form
                // (plugin step id was correct but envelope did not carry tmp);
                // the typed decode default-tolerated absent `tmp`, which masked
                // the canonical envelope shape.
                is StepSpec.Pwd -> {
                    put("kind", "pwd")
                    put("tmp", JsonPrimitive(step.tmp))
                }
                // Canonical envelope for core.cleanWs.
                // The CoreCleanWsStep.inputCodec reads
                // {"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}.
                // The generic arm used to produce
                // {"kind":"cleanWs","declarativeValue":"StepSpec.CleanWs(deleteDirs=…, patterns=…)"}
                // which made the typed decode fall back to defaults (empty
                // Patterns), erasing the user's glob selectors
                // Incidentally passed because `patterns=[]` is
                // its own valid default; the defect was not discriminated but
                // was structurally identical to the deleteDir / pwd family.
                // Locking the canonical envelope here closes the structural
                // class of defect, not just one symptom.
                is StepSpec.CleanWs -> {
                    put("kind", "cleanWs")
                    put("deleteDirs", JsonPrimitive(step.deleteDirs))
                    put("patterns", JsonArray((step.patterns ?: emptyList()).map { JsonPrimitive(it) }))
                }
                // `StepSpec.Checkout` has NO branch here, on purpose. Its payload
                // is the SCM plugin's codec's bytes, and the early return in
                // `encodePayload` is the only path that produces them: a branch in
                // this table would be a SECOND writer of a format that already has
                // one owner, and the two would be free to disagree.
                // Canonical envelope for core.load.
                // core.load is DEFERRED + UNSUPPORTED (no CoreLoadStep exists,
                // admission fail-closes); the envelope must still be canonical
                // because the field shape is the contract — `{"kind":"load",
                // "path":<str>}`. The body of `load(path)` is a path reference,
                // not a nested block, so this branch lives in encodePayload and
                // NOT in blockPayload (no `BlockStepNode` body to project).
                is StepSpec.Load -> {
                    put("kind", "load")
                    put("path", step.path)
                }
                // Canonical envelope for core.ansiColor.
                // The step is body-aware; the nested `steps` are lowered
                // recursively by the canonical `stepNodes` machinery and live
                // inside a [BlockStepNode] body — the envelope itself carries
                // only the color-map name. core.ansiColor has no production
                // Step key today; the envelope is kept canonical so any future
                // adapter that consumes it (Jenkins ansiColor plugin shape) can
                // decode deterministically.
                is StepSpec.AnsiColor -> {
                    put("kind", "ansiColor")
                    put("colorMapName", step.colorMapName)
                }
                // Canonical envelope for core.node
                // (LOCAL_no_op semantics — emits AgentResolved, executes inner
                // body as-is). The step is body-aware; the inner `steps` are
                // lowered by `stepNodes` and live inside a [BlockStepNode]
                // body. The envelope carries only the agent label.
                // Jenkins `node(label?: String) { block }`.
                is StepSpec.NodeNoOp -> {
                    put("kind", "node")
                    step.label?.let { put("label", it) }
                }
                else -> refuseWithoutCanonicalPayload(step, stepId)
            }
        }
        return Json.encodeToString(JsonObject.serializer(), payload)
    }

    /**
     * A DSL variant with NO canonical payload is refused, not invented.
     *
     * ## WHY A REFUSAL AND NOT A `declarativeValue`
     *
     * The arm this replaces wrote `step.toString()` into the payload. That string
     * is not a wire format: it is a note about a shape, carried as if it were
     * data. The run then failed LATER, in the canonical admission analysis,
     * with a message about a plugin id nobody registered -- so the author never
     * learned that the shape they wrote has no lowering at all.
     *
     * A refusal arrives BEFORE the run, and it says the three things the author
     * needs: the TYPE of the variant, the STEP it was written in, and the REASON
     * it cannot be lowered. `compileOrRefuse` prints the message verbatim as
     * `Error: <message>` and exits 1, so the author reads the reason instead of
     * a stack trace.
     *
     * ## WHY THE STEP ID TRAVELS IN
     *
     * The identity is minted by the caller, because only the caller knows the
     * scope and the occurrence. Without it the message could only name the
     * variant, and a pipeline that uses the same shape twice would leave the
     * author guessing which one was refused.
     *
     * @param step the variant that reached the arm with no lowering.
     * @param stepId the identity the compiler minted for it.
     * @throws IllegalStateException always: this function has no other outcome.
     */
    private fun refuseWithoutCanonicalPayload(step: StepSpec, stepId: StepId): Nothing =
        error(
            "the DSL variant '${step::class.simpleName ?: step.type}' (type '${step.type}') " +
                "at step '${stepId.value}' has no canonical payload: the compiler refuses " +
                "to invent one, because a payload the runtime cannot read is a step that " +
                "only fails when the run reaches it",
        )

    // Stage options surface carries ONLY timeout (the only option
    // with a runtime interpreter, projected via projectShellOptions). retry/skip were
    // removed from the DSL surface; unrepresentable instead of accepted-and-dropped.
    // The typed value now goes straight into the [StageOption] ADT. It used
    // to be flattened to `OptionSpec("timeout", it.toString())` and recovered by
    // the interpreter with a name filter plus `toLongOrNull`, which made the
    // carrier survive only as text. A non-positive value is rejected by the
    // ADT's own invariant rather than becoming a stage that fails at run time.
    // The KDoc here used to say this conversion was total. It was not:
    // `OptionsSpec` had no validation, so `Math.multiplyExact` was a live
    // `ArithmeticException` reachable from `OptionsSpec(timeout = Long.MAX_VALUE)`
    // and from `options { timeout(Long.MAX_VALUE) }`. Both doors now consult one
    // shared bound (`StageTimeout`, inside the DSL module), so the seconds reaching
    // this function are already in `1..Long.MAX_VALUE / 1_000` and the multiply
    // cannot overflow. `multiplyExact` is therefore KEPT: it is now an assertion
    // that the boundary holds rather than a crash site, and deleting it would
    // remove the check that would catch a future door opened without the bound.
    private fun dev.rubentxu.pipeline.v2.dsl.OptionsSpec?.toOptions(): List<StageOption> {
        if (this == null) return emptyList()
        return buildList {
            timeout?.let { add(StageOption.Timeout(Math.multiplyExact(it, 1_000L))) }
        }
    }

    /** Shared utility. Same bytes, same lowercase hex as the previous local helper */
    private fun sha256(value: String): String = Sha256.ofText(value)
}

/**
 * The normalization that turns a DSL name into an identity token.
 *
 * It is deliberately lossy —lower case, and every run of non-alphanumerics becomes a
 * single dash— and it is NOT injective: `build+test`, `build test` and `build_test` all
 * normalize to `build-test`. [IdentityScope] is the piece that refuses to let two
 * DIFFERENT names share one token.
 */
private fun stableToken(value: String): String = value
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .ifBlank { error("DSL names must contain at least one alphanumeric character: '$value'") }

/**
 * The identity scope of a set of siblings: the names that live under the same parent and
 * therefore mint their identities into the same parent token.
 *
 * The compiler is the authority that MINTS identities, so it is also the place where a
 * collision has to be caught: two different names that normalize to the same token would
 * mint the SAME [dev.rubentxu.pipeline.v2.domain.StageId] or
 * [dev.rubentxu.pipeline.v2.domain.StepId], and the durable journal records that identity
 * as the step's own name — so the two steps would be indistinguishable after the fact.
 *
 * The scope REFUSES that collision at compile time, naming both original names and the
 * trajectory where they collided. It refuses only between DIFFERENT names: the same name
 * repeated in one body is legitimate, and the occurrence counter already tells those
 * siblings apart.
 *
 * The IR validator keeps its own, independent check on the IR it receives; this scope is
 * the compiler's, and it exists so that the collision is caught where the identity is
 * born — with a diagnostic that names the two names — instead of downstream.
 *
 * RESUME COMPATIBILITY. The refusal changes NOTHING about the identities that do not
 * collide: the id scheme, `COMPILER_VERSION` and the `definitionId` are untouched, and
 * the tokens themselves are the same tokens as before. Every pipeline that compiles
 * today therefore keeps its ids byte-identical, and a durable journal written by an
 * earlier run resumes against them unchanged. That is why the answer to a collision is
 * to REFUSE the pipeline and not to renumber it: an injective id scheme would have
 * moved the id of every colliding pipeline and, with `COMPILER_VERSION` inside the
 * `definitionId`, its definition digest too.
 */
private class IdentityScope(private val trajectory: String) {
    private val origins = mutableMapOf<String, String>()

    /**
     * The identity token of [original] inside this scope.
     *
     * @throws IllegalStateException when a DIFFERENT name already owns that token.
     */
    fun tokenFor(original: String): String {
        val token = stableToken(original)
        val owner = origins.putIfAbsent(token, original)
        if (owner != null && owner != original) {
            error(
                "DSL names '$owner' and '$original' normalize to the same identity token " +
                    "'$token' in $trajectory: two different names cannot share one identity. " +
                    "Rename one of them.",
            )
        }
        return token
    }
}
