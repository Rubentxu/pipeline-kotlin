package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.PwdResolved
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.pwd` (S2-A6 / G1).
 *
 * The legacy wire payload for `core.pwd(false)` is `{"kind":"pwd"}` (compiler
 * `encodePayload` else-branch); the `tmp` field defaults to `false`. The
 * codec preserves the legacy envelope structure and decodes the `tmp` flag.
 *
 * `tmp=true` is the PWD_TMP_TRUE_DISPOSITION blocker: the candidate MUST
 * reject it at decode time (fail-closed) so the StepKey authority cannot be
 * flipped for `core.pwd` while `tmp=true` remains a pre-existing legacy-only
 * branch with side effects and a non-deterministic timestamp path.
 */
data class PwdInput(
    val tmp: Boolean = false,
) {
    init {
        require(!tmp) {
            "core.pwd tmp=true is unsupported in this registry candidate; " +
                "see PWD_TMP_TRUE_DISPOSITION in S2-A6/G0 characterization receipt"
        }
    }
}

/**
 * TYPED_RUNTIME_OUTPUT — APPROVED_ARCHITECTURAL_DELTA (S2-A6 / G2, decision D2).
 *
 * Legacy durable path produces NO typed output (`StepOutcome.Success` only; the
 * path is observable solely through the `PwdResolved` event). The candidate
 * deliberately exposes the resolved absolute path as a typed result because the
 * product baseline requires `pwd` to be a runtime-valued Step (the eventual
 * reconnection of `PipelineDsl.pwd` to a real canonical workspace observation
 * depends on it; cf. `LFC2_HONEST_DSL_CLOSURE.md` `pwd() real return` row).
 *
 * Durable law (G2): fresh/rerun OBSERVE the canonical stage workspace;
 * resume/reuse REPRODUCE the persisted observation (`PwdOutput` + original
 * `PwdResolved`) without re-executing the handler or re-reading
 * `WorkspaceIdentity`. A stale observation after a workspaceRoot change is
 * correct durable semantics, not a bug — matches `core.isUnix` precedent
 * (D4 frozen at `{READ_ONLY} + MEMOIZED`).
 */
data class PwdOutput(
    val path: String,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/**
 * Registry candidate for `core.pwd` (S2-A6 / G1 registry seam proof).
 *
 * G1 registers this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`, the legacy
 * decoder, the metadata row, or the legacy dispatcher:
 * `StructuralFamilyResolver`'s legacy-membership-wins rule keeps LegacyCore as
 * the production authority until the G3/G4 flip. Counters stay 7 / 7 / 7.
 *
 * The `tmp=false` truth is PATH_B verbatim (G0 characterization, receipt
 * `S2_A6_CORE_PWD_G0_CHARACTERIZATION_RECEIPT.md`): the resolved absolute path
 * comes from `WorkspaceIdentity.workspaceRoot`, which the capability bridge
 * populates from `context.shOptions.workspaceRoot` — the SAME source the
 * legacy `pwdContext()` consumed.
 *
 * Capability separation (mirrors `core.isUnix` precedent):
 * - [WORKSPACE_IDENTITY_CAPABILITY] owns the canonical workspace OBSERVATION
 *   (`workspaceRoot: Path`); the bridge derives it from the runtime context,
 *   the handler never reaches `user.dir` / `controlDirRoot` directly.
 * - The Step owns the projection POLICY (absolute path string + sha256).
 * - [EVENT_SINK_CAPABILITY] publishes the durable `PwdResolved` observation.
 *
 * Adjacent guarantees: input codec preserves the legacy `{"kind":"pwd"}`
 * envelope; the handler is total (no exceptions as semantics outside the
 * `tmp=true` rejection at decode); `PwdResolved` fields mirror the legacy
 * dispatcher byte-for-byte (uuid eventId, sequence 0L, sha256 hex of path,
 * `workspaceRoot` echoed alongside the resolved path).
 *
 * **Scope firewall**: `tmp=true` is rejected at decode (see [PwdInput.init])
 * because the legacy `tmp=true` path mints a `tmp-pwd-<timestamp>` directory
 * with non-deterministic naming — incompatible with `MEMOIZED` replay and
 * with `READ_ONLY` effect classification. This is the
 * PWD_TMP_TRUE_DISPOSITION blocker; the StepKey authority cannot be flipped
 * for `core.pwd` until a separate disposition lands.
 */
object CorePwdStep {

    val KEY: PluginStepId = PluginStepId("core.pwd")

    private val inputCodec = object : StepCodec<PwdInput> {
        override fun encode(value: PwdInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("pwd"))
                        // tmp=true is rejected at PwdInput.init, so we always emit false here.
                        put("tmp", JsonPrimitive(value.tmp))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): PwdInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "pwd") {
                "core.pwd payload kind must be 'pwd'"
            }
            val tmp = obj["tmp"]?.jsonPrimitive?.booleanOrNull ?: false
            // Fail-closed at decode: tmp=true is the PWD_TMP_TRUE_DISPOSITION blocker.
            require(!tmp) {
                "core.pwd tmp=true is unsupported in this registry candidate; " +
                    "see PWD_TMP_TRUE_DISPOSITION in S2-A6/G0 characterization receipt"
            }
            return PwdInput(tmp = false)
        }
    }

    private val outputCodec = object : StepCodec<PwdOutput> {
        override fun encode(value: PwdOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("pwd"))
                        put("path", JsonPrimitive(value.path))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): PwdOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "pwd") {
                "core.pwd output payload kind must be 'pwd'"
            }
            return PwdOutput(obj.getValue("path").jsonPrimitive.content)
        }
    }

    // Byte-equivalent to the legacy CanonicalCoreStepMetadata["core.pwd"] row for tmp=false.
    // The legacy metadata declared {READ_ONLY} + MEMOIZED even though the legacy dispatcher
    // actually CREATED a directory when tmp=true — that gap is documented as
    // PWD_TMP_TRUE_DISPOSITION; this descriptor inherits the declared truth for tmp=false only.
    private val descriptor = StepDescriptor(
        stepId = "core.pwd",
        name = "pwd",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<PwdInput, PwdOutput> =
        StepHandler { _, ctx ->
            val workspace: WorkspaceIdentity = ctx.capabilities.get(WORKSPACE_IDENTITY_CAPABILITY)
            val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
            val path = workspace.workspaceRoot.toAbsolutePath().toString()
            sink.append(
                PwdResolved(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    path = path,
                    workspaceRoot = workspace.workspaceRoot.toAbsolutePath().toString(),
                    sha256 = sha256(path),
                ),
            )
            PwdOutput(path = path)
        }

    internal fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    val definition: StepDefinition<PwdInput, PwdOutput> =
        object : StepDefinition<PwdInput, PwdOutput> {
            override val contract: StepContract<PwdInput, PwdOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(
                    WORKSPACE_IDENTITY_CAPABILITY,
                    EVENT_SINK_CAPABILITY,
                ) as Set<StepCapability>,
            )

            override val handler: StepHandler<PwdInput, PwdOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
