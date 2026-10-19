package example.saga

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDeclaration
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationOperations
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_COMPENSATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The external consumer of the Saga intent port.
 *
 * ## What this artifact proves
 *
 * That the Saga contract is usable from OUTSIDE the repository, against the PUBLISHED SDK
 * artifact, by a plugin that has no access to anything internal. This JAR compiles against
 * `pipeline-domain` alone and reaches the compensation machinery through ONE published
 * capability. It never sees the event store, the operation journal or the durable table: it
 * declares an intent and reads back what the engine decided.
 *
 * That is the whole claim of the seam. A plugin that could append events or journal operations
 * could forge the very evidence that compensation obligations are judged by, so the port is
 * narrow on purpose and this artifact is where that narrowness is exercised.
 *
 * ## What it does NOT do
 *
 * It does not compensate anything. The obligation is armed and read back, and the forward effect
 * is never performed, because the guarantee under test is that the PROMISE is durable and visible
 * — not that a compensation runs. A compensation that actually fired here would be a second
 * milestone pretending to be the first.
 */
@Serializable
data class SagaObligationInput(
    val sagaId: String,
    val actionId: String,
    val compensationId: String,
    val resourceRef: String,
)

@Serializable
data class SagaObligationOutput(
    /** What the arm returned: `Armed`, `AlreadyArmed`, `PlanDivergence` or `Rejected`. */
    val armOutcome: String,
    /** Whether the obligation was readable back after the arm. */
    val visible: Boolean,
    /** The obligation's status, as read back from the durable store. */
    val status: String,
    /** The identity the compensation will run under. */
    val compensationOpId: String,
    /** What the engine decided about the forward effect. */
    val decision: String,
)

object SagaObligationCodec : StepCodec<SagaObligationInput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: SagaObligationInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(SagaObligationInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): SagaObligationInput =
        json.decodeFromString(SagaObligationInput.serializer(), encoded.value)
}

object SagaObligationOutputCodec : StepCodec<SagaObligationOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: SagaObligationOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(SagaObligationOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): SagaObligationOutput =
        json.decodeFromString(SagaObligationOutput.serializer(), encoded.value)
}

object SagaObligationStepDefinition : StepDefinition<SagaObligationInput, SagaObligationOutput> {

    val KEY = PluginStepId("example.saga.obligation")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "saga-obligation",
            configRef = "",
            pluginId = "example.saga",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            // The Step records a promise in the engine's durable store. That is the effect, and
            // the descriptor says so: `READ_ONLY` would be a lie that the replay policy would
            // then believe.
            effects = listOf(Effect.WRITES_DURABLE_STATE),
            // Re-run rather than memoise: the arm is idempotent, so re-running is safe, and
            // skipping it would mean a resumed run never re-asserts its promise.
            replayPolicy = ReplayPolicy.RERUN,
        ),
        inputCodec = SagaObligationCodec,
        outputCodec = SagaObligationOutputCodec,
        // The ONE capability this plugin needs, and it does not supply it: only the host knows
        // the run, the durable store and the lease.
        requiredCapabilities = setOf(SAGA_COMPENSATION_CAPABILITY),
    )

    override val handler = StepHandler<SagaObligationInput, SagaObligationOutput> { input, context ->
        // The port, handed over as a value. The plugin never sees the runtime context, the
        // event store or the journal: only this narrow port, already bound to this invocation.
        val operations: CompensationOperations =
            context.capabilities.get(SAGA_COMPENSATION_CAPABILITY)

        val declaration = CompensationDeclaration(
            sagaId = SagaId.of(input.sagaId),
            actionId = SagaActionId.of(input.actionId),
            compensationId = CompensationId.of(input.compensationId),
            planFingerprint = planFingerprintOf(input),
            resourceRef = ResourceRef.of(input.resourceRef),
            kind = CompensationKind.RESTORE,
        )

        val armed = operations.arm(declaration)
        // The read is what proves the promise is DURABLE: the answer comes from the store, in
        // the run's own database, and it survives the process that wrote it.
        val stored = operations.read(declaration.key)
        val decision = operations.decide(
            obligation = stored,
            evidence = EffectEvidence.NotAttempted,
            policy = CompensationPolicy.NotReconcilable,
        )

        SagaObligationOutput(
            armOutcome = when (armed) {
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome.Armed -> "Armed"
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome.AlreadyArmed ->
                    "AlreadyArmed"
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome.PlanDivergence ->
                    "PlanDivergence"
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome.Rejected ->
                    "Rejected"
            },
            visible = stored != null,
            status = stored?.status?.name ?: "MISSING",
            compensationOpId = stored?.compensationOpId ?: "",
            decision = decision.toString(),
        )
    }

    /**
     * The fingerprint of the compensation PLAN, derived deterministically from the declaration.
     *
     * Deterministic on purpose: the same plan must produce the same fingerprint in a later
     * process, or a resumed run would arm a DIFFERENT promise under the same key and be refused
     * as a divergence. The content is the plan itself — what is compensated, by which
     * compensation — and nothing about the moment it was armed.
     */
    private fun planFingerprintOf(input: SagaObligationInput): SagaPlanFingerprint {
        val canonical = listOf(
            "saga-v1",
            input.sagaId,
            input.actionId,
            input.compensationId,
            input.resourceRef,
            CompensationKind.RESTORE.name,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return SagaPlanFingerprint(digest)
    }
}

/**
 * ServiceLoader entry point:
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`.
 */
class SagaContributor : StepDefinitionContributor {
    override val id: String = "example.saga"

    override fun definitions(): Iterable<StepDefinition<*, *>> =
        listOf(SagaObligationStepDefinition)
}
