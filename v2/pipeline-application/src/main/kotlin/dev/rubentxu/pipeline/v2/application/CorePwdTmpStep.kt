package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
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
 * Typed input payload of `core.pwd.tmp` (S2-A6 / G3T).
 *
 * The Step has no input parameters — the per-operation identity comes from the
 * canonical [dev.rubentxu.pipeline.v2.application.durable.OpId.format] which the
 * adapter receives from the runtime bridge (NOT from the Step contract). The
 * legacy `tmp=true` input was a boolean discriminator; the registry path replaces
 * it with the operation identity that the durable coordinator already owns, hidden
 * behind the [TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY] seam.
 */
data object PwdTmpInput

/**
 * Typed output payload of `core.pwd.tmp` — APPROVED_ARCHITECTURAL_DELTA (S2-A6 / G3T, decision D2).
 *
 * Same typed output as `core.pwd`: `PwdOutput(path: String)` with `outcome = Success`.
 * The output path is the absolute path of the deterministic temp workspace
 * (sha256(opId) under `workspaceRoot`). Reuse of the typed shape keeps `core.pwd`
 * and `core.pwd.tmp` consumable by the same downstream typed seams.
 */
typealias PwdTmpOutput = PwdOutput

/**
 * Registry candidate for `core.pwd.tmp` (S2-A6 / G3T, corrected post-review).
 *
 * This Step is **pure policy**: it declares what the engine must do (capability
 * admission + typed contract + canonical output envelope) and delegates ALL
 * infrastructure (filesystem, event emission, opId-based identity derivation)
 * to the [TemporaryWorkspaceOperations] typed seam, exposed through the
 * [TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY] token.
 *
 * ## What the handler DOES NOT touch
 *
 * ```text
 * Files.*                    -> only TemporaryWorkspaceOperationsAdapter
 * EventSink                  -> only TemporaryWorkspaceOperationsAdapter
 * CanonicalRuntimeContext    -> never
 * OpId                       -> never
 * sha256 / MessageDigest     -> never
 * ```
 *
 * Concretely, the handler is:
 *
 * ```kotlin
 * val ops: TemporaryWorkspaceOperations =
 *     ctx.capabilities.get(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY)
 * val result = ops.resolveOrCreate()
 * PwdTmpOutput(path = result.path)
 * ```
 *
 * That's it. The deterministic `tmp-pwd-<sha256(opId.format())>` derivation
 * (D5–D12), the directory creation, and the `PwdResolved` event emission all
 * live in [dev.rubentxu.pipeline.v2.application.durable.TemporaryWorkspaceOperationsAdapter].
 *
 * ## Why a single capability, not three
 *
 * AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (symmetric with
 * `core.sh → ShellOperations`). Three capabilities on a single effectful Step
 * leak the runtime concerns into the contract: the handler would re-derive
 * `sha256` from `OpId`, manually call `Files.createDirectories`, and manually
 * emit `PwdResolved` — all of which are runtime/bridge concerns, not Step
 * policy. The post-correction design collapses these into one
 * `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY` and keeps the handler
 * capability-only.
 *
 * Specifically, **`DURABLE_OPERATION_IDENTITY_CAPABILITY` is NOT part of this
 * Step's contract** — the handler does not need the durable OpId to do its
 * work; the bridge adapter does, and only there.
 *
 * ## Determinism laws (D5–D12, S2-A6 / G3T)
 *
 * Lives in the adapter. Proven by the family-level fitness matrix (24 rows):
 * same OpId ⇒ same path; distinct stepIndex / branchIndex / bodyPath ⇒ distinct
 * paths; path under workspaceRoot; 64 lowercase hex suffix; idempotent; one
 * `PwdResolved` per fresh execution.
 *
 * ## Capability-routed handler discipline (LB-02 / G3-A4.2)
 *
 * - declared capability = used capability = `{TEMPORARY_WORKSPACE_OPERATIONS}`
 * - no `CanonicalRuntimeContext` reach
 * - no coordinator / journal / event sink reach
 * - the substrate (sha256 + Files + PwdResolved) is the adapter's responsibility
 *
 * ## Scope firewall
 *
 * - `core.pwd` legacy membership is untouched (`LEGACY_PLUGIN_IDS` still
 *   contains `"core.pwd"` and the legacy dispatcher remains the production
 *   authority until a separate G4 flip).
 * - the public DSL (`PipelineDsl.pwd(tmp=true)`) is NOT rewired yet — that
 *   lower-binding is S2-A6 / G3R (LFC-2R runtime-return consumer). For this
 *   slice the Step is reachable only through the generic registry path.
 *
 * ## Replay law
 *
 * `Effect.WRITES_WORKSPACE` is declared; `ReplayPolicy.MEMOIZED` is declared
 * too — fresh/rerun creates (idempotently) the temp workspace; resume/reuse
 * reproduces the persisted observation (same path, no second directory).
 */
object CorePwdTmpStep {

    val KEY: PluginStepId = PluginStepId("core.pwd.tmp")

    private val inputCodec = object : StepCodec<PwdTmpInput> {
        override fun encode(value: PwdTmpInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {}),
            )

        override fun decode(encoded: EncodedStepValue): PwdTmpInput {
            // Envelope structure only: legacy-durable empty JSON object — same envelope
            // shape as the empty `{}` we use for `core.isUnix`, by analogy. A future
            // compiler/PSI lowering that wants to discriminate may write a richer
            // envelope here; this slice keeps the unit shape to match the legacy
            // "no input parameters" semantics of pwd(tmp=true).
            Json.parseToJsonElement(encoded.value).jsonObject
            return PwdTmpInput
        }
    }

    private val outputCodec = object : StepCodec<PwdTmpOutput> {
        override fun encode(value: PwdTmpOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("pwd.tmp"))
                        put("path", JsonPrimitive(value.path))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): PwdTmpOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "pwd.tmp") {
                "core.pwd.tmp output payload kind must be 'pwd.tmp'"
            }
            return PwdTmpOutput(obj.getValue("path").jsonPrimitive.content)
        }
    }

    // Distinct from CorePwdStep (READ_ONLY + MEMOIZED): the tmp directory is
    // physically created, so the effect class MUST reflect that. Replay law
    // (D4 frozen at G2 for the family): idempotent mkdir on fresh/rerun,
    // persisted observation on resume/reuse.
    private val descriptor = StepDescriptor(
        stepId = "core.pwd.tmp",
        name = "pwd.tmp",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    /**
     * Pure policy handler — capability-only.
     *
     * No `Files`, no `EventSink`, no `OpId`, no `sha256`. The single capability
     * read is the typed [TemporaryWorkspaceOperations] port, which the adapter
     * binds to the runtime context.
     */
    private val capabilityRoutedHandler: StepHandler<PwdTmpInput, PwdTmpOutput> =
        StepHandler { _, ctx ->
            val ops: TemporaryWorkspaceOperations =
                ctx.capabilities.get(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY)
            val result: TempWorkspaceResult = ops.resolveOrCreate()
            PwdTmpOutput(path = result.path)
        }

    val definition: StepDefinition<PwdTmpInput, PwdTmpOutput> =
        object : StepDefinition<PwdTmpInput, PwdTmpOutput> {
            override val contract: StepContract<PwdTmpInput, PwdTmpOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(
                    TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY,
                ) as Set<StepCapability>,
            )

            override val handler: StepHandler<PwdTmpInput, PwdTmpOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
