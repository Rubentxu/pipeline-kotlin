package example.saga

import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome
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
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_RUNTIME_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaRuntime
import dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason
import dev.rubentxu.pipeline.v2.domain.step.BODY_SLOT_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * What a failed call MEANS for the provider that failed.
 *
 * The whole demonstration rests on this being the PROVIDER's contract and not the plugin's
 * convenience: a provider that promises "a failed call left nothing behind" lets the engine
 * move on to the older obligations, and a provider that cannot promise that leaves the engine
 * with nothing it may safely undo — so it stops and asks a human.
 *
 * It is a closed set and not a String because a typo in a String would silently mean the
 * OPPOSITE: the plugin reads an unknown contract as "nobody can say", so a scenario that meant
 * to compensate would stop compensating, for a reason the script never asked for. The name on
 * the wire is the enum's own, so the script and the plugin cannot drift apart.
 */
enum class SagaProviderContract(
    /** The name the step's input carries. */
    val wireName: String,
) {
    /** The call left nothing behind, so there is nothing to undo. */
    FailureLeavesNoEffect("FailureLeavesNoEffect"),

    /** Nobody can say whether the call took effect. */
    FailureMayHaveApplied("FailureMayHaveApplied"),
}

/**
 * One action of the chain, as the plugin declares it.
 *
 * The two slots are named by the PLUGIN STEP KEY of a child of this Step's own body, and that
 * is the whole address: the engine resolves the name against the body, so a name that does not
 * exist there is a contract violation the engine reports, not a body the plugin hoped for.
 */
@Serializable
data class SagaChainAction(
    val actionId: String,
    val compensationId: String,
    val resourceRef: String,
    /** The body that performs the forward effect. */
    val forwardSlot: String,
    /** The body that undoes it. */
    val compensateSlot: String,
)

@Serializable
data class SagaChainInput(
    val sagaId: String,
    /** The actions, in the order the chain performs them. */
    val actions: List<SagaChainAction>,
    /**
     * What a FAILED call means for the provider that failed.
     *
     * `FailureLeavesNoEffect`: the call left nothing behind, so the engine can move on to the
     * older obligations.
     *
     * `FailureMayHaveApplied`: nobody can say, so the engine must stop and ask a human. A
     * provider that cannot promise the first is the reason `NeedsIntervention` exists.
     */
    val providerContract: String,
)

/**
 * What the chain did, and what the Saga ended up being.
 *
 * ## The outcome is TYPED, and that is the point
 *
 * A chain that failed and was compensated is still a FAILED run. The engine compensates the
 * Saga and then the run says what really happened: the failure. A plugin that returned a
 * success here would be claiming that the failure never happened, and the recovery would be
 * indistinguishable from a run that never failed.
 */
@Serializable
data class SagaChainOutput(
    /** What each forward body produced, in the order the bodies ran. */
    val forwardOutcomes: List<String>,
    /** The actions the ENGINE compensated, in the order it compensated them. */
    val compensated: List<String>,
    /** The Saga's state when the loop ended: `Settled` or `NeedsIntervention`. */
    val terminal: String,
    /** The intervention reason, when the Saga did not settle. */
    val reason: String?,
    /** The failure the chain ended with, when it ended with one. */
    val failure: String?,
) : TypedStepOutput {
    override val outcome: StepOutcome
        get() = failure?.let {
            StepOutcome.Failure(PipelineFailure(kind = FailureKind.PLUGIN, message = it))
        } ?: StepOutcome.Success
}

/**
 * A Saga CHAIN, performed by an external plugin, undone by the engine.
 *
 * ## What this artifact proves
 *
 * That the whole contract works from OUTSIDE, end to end, with a real body behind every slot:
 *
 *  1. The plugin makes a DURABLE promise before each effect, and the promise is the first
 *     thing that happens — not a note written afterwards.
 *  2. The plugin performs the forward effect through the SAME seam the engine will use to undo
 *     it. There is one dispatcher, so a compensation is a normal child of the body and its
 *     journal rows are the rows it would have had.
 *  3. The plugin REPORTS what happened, and it reports the truth: a body that failed says the
 *     effect was ABSENT, so the engine knows there is nothing to undo instead of stopping at
 *     an unknown outcome.
 *  4. The plugin ASKS the engine to compensate, and the engine decides what is owed and in
 *     which order. The plugin never compensates its own resource: a second authority here would
 *     be a second, divergent copy of the rule.
 *
 * ## What it does NOT do
 *
 * It does not decide the compensation order, and it does not read the durable table. It sees
 * three published ports and one seam, and it gets back a report that says which actions were
 * compensated, in which order, and whether a human is needed.
 */
object SagaChainStepDefinition : StepDefinition<SagaChainInput, SagaChainOutput> {

    val KEY = PluginStepId("example.saga.chain")

    /**
     * The contract the script declared, as the closed set it is.
     *
     * An unknown name is REFUSED rather than guessed. The two contracts lead to opposite
     * worlds — one compensates and the other stops — so reading an unknown contract as
     * "nobody can say" would be a guess about the provider's guarantees, made in the
     * direction that silently leaves work undone. A refusal says what the script did not
     * say.
     */
    fun contractOf(wireName: String): SagaProviderContract =
        SagaProviderContract.entries.firstOrNull { it.wireName == wireName }
            ?: throw IllegalArgumentException(
                "the provider contract '$wireName' is not one of " +
                    SagaProviderContract.entries.joinToString(", ") { it.wireName },
            )

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "saga-chain",
            configRef = "",
            pluginId = "example.saga",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            // The Step writes promises and reports effects into the engine's durable store, and
            // the bodies it dispatches do real work. `READ_ONLY` would be a lie the replay
            // policy would then believe.
            effects = listOf(Effect.WRITES_DURABLE_STATE),
            // Re-run rather than memoise: the arm is idempotent, and skipping it would mean a
            // resumed run never re-asserts its promise.
            replayPolicy = ReplayPolicy.RERUN,
            // The Step DECLARES its body, and the declaration says WHO runs it: the handler
            // does, one child at a time, by name. Without this the step is not canonical and
            // the canonical bridge refuses the script — which is the right answer, because a
            // body whose owner nobody declared is a body nobody is accountable for.
            body = StepBody.Declared(
                invocation = BodyInvocationPolicy.ONCE,
                execution = BodyExecution(
                    owner = BodyExecutionOwner.HANDLER_CONTINUATION,
                    policy = BodyExecutionPolicy.Sequential,
                ),
                introduces = null,
            ),
        ),
        inputCodec = SagaChainCodec,
        outputCodec = SagaChainOutputCodec,
        // FOUR capabilities, none of them supplied by this plugin: the host knows the run, the
        // durable store and the lease. The intent port makes the promise, the plan port moves it
        // along its lifecycle, the runtime port runs the engine's compensation loop, and the
        // slot seam is the only way to reach a body of this Step.
        requiredCapabilities = setOf(
            SAGA_COMPENSATION_CAPABILITY,
            SAGA_PLAN_CAPABILITY,
            SAGA_RUNTIME_CAPABILITY,
            BODY_SLOT_CAPABILITY,
        ),
    )

    override val handler = StepHandler<SagaChainInput, SagaChainOutput> { input, context ->
        val operations: CompensationOperations =
            context.capabilities.get(SAGA_COMPENSATION_CAPABILITY)
        val plan: SagaPlanOperations = context.capabilities.get(SAGA_PLAN_CAPABILITY)
        val runtime: SagaRuntime = context.capabilities.get(SAGA_RUNTIME_CAPABILITY)
        val slots: BodySlotInvoker = context.capabilities.get(BODY_SLOT_CAPABILITY)

        val sagaId = SagaId.of(input.sagaId)
        val contract = contractOf(input.providerContract)
        val forwardOutcomes = mutableListOf<String>()
        var failure: String? = null

        for (action in input.actions) {
            val declaration = declarationOf(sagaId, action)

            // 1. The promise, DURABLY, before the effect leaves the process.
            when (operations.arm(declaration)) {
                is CompensationArmOutcome.Armed,
                is CompensationArmOutcome.AlreadyArmed,
                -> Unit

                is CompensationArmOutcome.PlanDivergence -> {
                    // A different plan under the same key: the promise is not this one, and
                    // pretending otherwise would compensate something else.
                    failure = "the promise for ${action.actionId} diverges from the armed plan"
                    break
                }

                is CompensationArmOutcome.Rejected -> {
                    failure = "the promise for ${action.actionId} was rejected"
                    break
                }
            }

            // 2. The write-ahead of the forward effect. A refusal means the durable state does
            //    not know that an effect is about to happen, so the effect does NOT happen.
            if (plan.beginForward(declaration.key) !is SagaRecordOutcome.Recorded) {
                failure = "the write-ahead for ${action.actionId} was refused"
                break
            }

            // 3. The effect itself, through the SAME seam the engine will use to undo it.
            val body = slots.invoke(PluginStepId(action.forwardSlot), BodyInvocationContext())
            when (val step = (body as? BodyOutcome.Completed)?.outcome) {
                is StepOutcome.Success -> {
                    plan.recordForward(
                        declaration.key,
                        EffectEvidence.Confirmed(EffectReceiptRef.of("receipt-${action.actionId}")),
                    )
                    forwardOutcomes += "CONFIRMED"
                }

                is StepOutcome.Failure -> {
                    // What the failure MEANS is the provider's contract, not the plugin's
                    // convenience. A provider that promises "a failed call left nothing behind"
                    // is reported as ABSENT, and the engine moves on to the older obligations. A
                    // provider that cannot promise that is reported as UNKNOWN, and the engine
                    // stops: an effect that may have happened must not be undone twice, and
                    // must not be declared absent either.
                    if (contract == SagaProviderContract.FailureLeavesNoEffect) {
                        plan.recordForward(declaration.key, EffectEvidence.Absent)
                        forwardOutcomes += "FAILED"
                    } else {
                        plan.recordForward(
                            declaration.key,
                            EffectEvidence.Unknown(UncertaintyReason.ACK_LOST),
                        )
                        forwardOutcomes += "FAILED_UNKNOWN"
                    }
                    failure = "the action ${action.actionId} failed: ${step.failure.message}"
                    break
                }

                is StepOutcome.Unstable -> {
                    // The body ran and nobody can say whether it took effect. That is an
                    // unknown, not an absence, and the engine must treat it as one.
                    plan.recordForward(
                        declaration.key,
                        EffectEvidence.Unknown(UncertaintyReason.ACK_LOST),
                    )
                    forwardOutcomes += "UNSTABLE"
                    failure = "the action ${action.actionId} is unstable: the outcome is not established"
                    break
                }

                null -> {
                    // The body was cancelled: the call may or may not have left the process.
                    plan.recordForward(
                        declaration.key,
                        EffectEvidence.Unknown(UncertaintyReason.CALL_CANCELLED),
                    )
                    forwardOutcomes += "CANCELLED"
                    failure = "the action ${action.actionId} was cancelled"
                    break
                }
            }
        }

        // 4. The engine undoes what the Saga owes, in the order IT decides. The plugin only
        //    hands over the PLAN; the durable state says what is owed, and the order is the
        //    inverse of the order in which the obligations were armed.
        val report = runtime.compensate(
            SagaPlan(
                sagaId = sagaId,
                policy = CompensationPolicy.NotReconcilable,
                actions = input.actions.map { actionPlanOf(it) },
            ),
        )

        val directive = report.directive
        SagaChainOutput(
            forwardOutcomes = forwardOutcomes,
            compensated = report.compensated.map { it.value },
            terminal = when (directive) {
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective.Settled -> "Settled"
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective.NeedsIntervention ->
                    "NeedsIntervention"
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective.Compensate -> "Compensate"
                is dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective.Reconcile -> "Reconcile"
            },
            reason = (directive as? dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective.NeedsIntervention)
                ?.reason?.name,
            failure = failure,
        )
    }

    /**
     * The promise of one action.
     *
     * The fingerprint is DERIVED, and derived from the plan: the same plan must produce the
     * same fingerprint in a later process, or a resumed run would arm a DIFFERENT promise
     * under the same key and be refused as a divergence.
     */
    private fun declarationOf(sagaId: SagaId, action: SagaChainAction): CompensationDeclaration =
        CompensationDeclaration(
            sagaId = sagaId,
            actionId = SagaActionId.of(action.actionId),
            compensationId = CompensationId.of(action.compensationId),
            planFingerprint = fingerprintOf(sagaId, action),
            resourceRef = ResourceRef.of(action.resourceRef),
            kind = CompensationKind.RESTORE,
        )

    /** The action, as the engine's plan describes it. */
    private fun actionPlanOf(action: SagaChainAction): SagaActionPlan = SagaActionPlan(
        actionId = SagaActionId.of(action.actionId),
        compensationId = CompensationId.of(action.compensationId),
        resourceRef = ResourceRef.of(action.resourceRef),
        kind = CompensationKind.RESTORE,
        forwardSlot = PluginStepId(action.forwardSlot),
        compensateSlot = PluginStepId(action.compensateSlot),
    )

    private fun fingerprintOf(sagaId: SagaId, action: SagaChainAction): SagaPlanFingerprint {
        val canonical = listOf(
            "saga-v1",
            sagaId.value,
            action.actionId,
            action.compensationId,
            action.resourceRef,
            action.forwardSlot,
            action.compensateSlot,
            CompensationKind.RESTORE.name,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return SagaPlanFingerprint(digest)
    }
}

/** The codec of the chain's input. */
object SagaChainCodec : StepCodec<SagaChainInput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: SagaChainInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(SagaChainInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): SagaChainInput =
        json.decodeFromString(SagaChainInput.serializer(), encoded.value)
}

/** The codec of the chain's output. */
object SagaChainOutputCodec : StepCodec<SagaChainOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: SagaChainOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(SagaChainOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): SagaChainOutput =
        json.decodeFromString(SagaChainOutput.serializer(), encoded.value)
}
