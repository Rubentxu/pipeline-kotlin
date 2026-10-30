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
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_COMPENSATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_PLAN_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The external consumer of the Saga contract.
 *
 * ## What this artifact proves
 *
 * That the Saga contract is usable from OUTSIDE the repository, against the PUBLISHED SDK
 * artifact, by a plugin that has no access to anything internal. This JAR compiles against
 * `pipeline-domain` alone and reaches the Saga machinery through TWO published capabilities.
 * It never sees the event store, the operation journal or the durable table: it declares an
 * intent, reports what it observed, and reads back what the engine decided.
 *
 * That is the whole claim of the seam. A plugin that could append events or journal operations
 * could forge the very evidence that compensation obligations are judged by, so the port is
 * narrow on purpose and this artifact is where that narrowness is exercised.
 *
 * ## What it does NOT do
 *
 * It does not compensate anything, and it does not perform a real effect: there is no provider
 * in this example. The forward effect is REPORTED as confirmed, which is what the plugin would
 * report after a call that really happened, and the guarantee under test is the CONTRACT — that
 * the promise is durable, that the lifecycle can be walked through the published port, and
 * that the engine answers what the Saga owes — not the behaviour of a provider.
 *
 * What actually runs the compensation is the ENGINE, through the same dispatcher that ran the
 * forward effect. A plugin that compensated its own resource here would be a second authority
 * pretending to be the first.
 */
@Serializable
data class SagaObligationInput(
    val sagaId: String,
    val actionId: String,
    val compensationId: String,
    val resourceRef: String,
    /**
     * The body that performs the forward effect.
     *
     * The two slot names are part of the PLAN, and the plan's digest is the only place the
     * engine records them: the durable obligation carries the digest, not the bodies. A
     * step that armed a promise without saying which bodies it was promising could not
     * produce a plan fingerprint at all, and two steps that spelled the same plan
     * differently would have been refused as a divergence.
     */
    val forwardSlot: String,
    /** The body that undoes it. */
    val compensateSlot: String,
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
    /** What the engine says the Saga owes, asked through the PLAN port. */
    val owed: String,
    /** The obligation's status after the lifecycle the plugin walked. */
    val finalStatus: String,
    /** What the forward write-ahead answered: `Recorded` or `Refused(...)`. */
    val forwardWriteAhead: String,
    /** What the forward record answered: `Recorded`, `Refused(...)` or `NotAttempted`. */
    val forwardRecord: String,
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
        // The TWO capabilities this plugin needs, and it does not supply either of them: only
        // the host knows the run, the durable store and the lease. The first port makes the
        // promise; the second one moves the promise along its lifecycle and answers what the
        // Saga owes.
        requiredCapabilities = setOf(SAGA_COMPENSATION_CAPABILITY, SAGA_PLAN_CAPABILITY),
    )

    override val handler = StepHandler<SagaObligationInput, SagaObligationOutput> { input, context ->
        // The port, handed over as a value. The plugin never sees the runtime context, the
        // event store or the journal: only this narrow port, already bound to this invocation.
        val operations: CompensationOperations =
            context.capabilities.get(SAGA_COMPENSATION_CAPABILITY, CompensationOperations::class)
        val plan: SagaPlanOperations =
            context.capabilities.get(SAGA_PLAN_CAPABILITY, SagaPlanOperations::class)

        val sagaId = SagaId.of(input.sagaId)
        val actionPlan = SagaActionPlan(
            actionId = SagaActionId.of(input.actionId),
            compensationId = CompensationId.of(input.compensationId),
            resourceRef = ResourceRef.of(input.resourceRef),
            kind = CompensationKind.RESTORE,
            forwardSlot = PluginStepId(input.forwardSlot),
            compensateSlot = PluginStepId(input.compensateSlot),
        )
        val declaration = CompensationDeclaration(
            sagaId = sagaId,
            actionId = actionPlan.actionId,
            compensationId = actionPlan.compensationId,
            planFingerprint = SagaPlanFingerprint.compute(sagaId, actionPlan),
            resourceRef = actionPlan.resourceRef,
            kind = actionPlan.kind,
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

        // The lifecycle, walked through the PLAN port.
        //
        // There is NO provider in this example, so the effect is not really performed. What is
        // under test is the CONTRACT: that a plugin can declare that an effect is about to
        // happen, report what happened, and read back what the engine says the Saga owes —
        // all of it through a published port, with the durable state owned by the host.
        //
        // The port ANSWERS, and the answer is part of the contract. A plugin that ignores a
        // refusal is a plugin whose effect can run without a durable trace of it, and a crash
        // in that window is a crash nobody can explain afterwards. So the answer is read, and
        // a refused write-ahead means the effect is NOT reported as having happened: a plugin
        // with a real provider would fail the step right there, before the call leaves the
        // process.
        val writeAhead = plan.beginForward(declaration.key)
        val record = if (writeAhead is SagaRecordOutcome.Recorded) {
            plan.recordForward(
                declaration.key,
                EffectEvidence.Confirmed(EffectReceiptRef.of("example-receipt")),
            )
        } else {
            null
        }
        val owed = plan.next(declaration.sagaId, CompensationPolicy.NotReconcilable)

        val finalStatus = operations.read(declaration.key)?.status?.name ?: "MISSING"

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
            owed = owed.toString(),
            finalStatus = finalStatus,
            forwardWriteAhead = answeredBy(writeAhead),
            forwardRecord = answeredBy(record),
        )
    }

    /**
     * What a durable write answered, said in the example's own words.
     *
     * The refusals are named instead of swallowed: a plugin that cannot say why the host
     * refused a write cannot tell the operator either.
     */
    private fun answeredBy(outcome: SagaRecordOutcome?): String = when (outcome) {
        null -> "NotAttempted"
        is SagaRecordOutcome.Recorded -> "Recorded"
        is SagaRecordOutcome.Refused -> "Refused(${outcome.reason::class.simpleName})"
    }

}

/**
 * ServiceLoader entry point:
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`.
 */
class SagaContributor : StepDefinitionContributor {
    override val id: String = "example.saga"

    override fun definitions(): Iterable<StepDefinition<*, *>> =
        listOf(SagaObligationStepDefinition, SagaChainStepDefinition)
}
