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
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationOperations
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligation
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_COMPENSATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_LIFECYCLE_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SAGA_PLAN_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaLifecycle
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationIdentity
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationPhase
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.saga.UncertaintyReason
import dev.rubentxu.pipeline.v2.domain.step.BODY_SLOT_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    /**
     * The provider RECOGNISES ITS OWN KEYS, so it can be asked twice.
     *
     * The bodies of this provider are idempotent by the operation key the engine hands them:
     * asked a second time about the same key, they answer "already done" instead of doing it
     * again. That is what lets the engine reconcile a lost answer instead of stopping.
     *
     * The answer is about the OPERATION and not about the attempt that asked. "Already done"
     * and "not done" are both answers about the operation; "it failed this time" is an answer
     * about the attempt, and the engine cannot read it as "not done" — a body that fails for
     * reasons of its own would leave an applied effect standing. A provider that can only
     * speak about its attempts does not keep this promise.
     *
     * The declaration is the provider's promise, and it is not free: a body that does NOT
     * recognise its keys must not be asked twice, and asking it twice is how one effect becomes
     * two. A plugin that cannot keep that promise must not pick this contract.
     */
    ReconcilableByKey("ReconcilableByKey"),
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
    /**
     * The actions whose effects the engine KEPT, because the chain succeeded.
     *
     * Empty for a chain that failed: a failed chain ends by undoing, never by keeping.
     *
     * It has a default so an output written by an older build still decodes: a run that
     * started before the closing verb existed must still be readable after it did.
     */
    val kept: List<String> = emptyList(),
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
 *  4. The plugin ASKS the engine to end the Saga, and the engine decides what that means. The
 *     plugin says WHICH ending — undo the effects, or keep them — because only the plugin knows
 *     whether its chain succeeded; and the engine says WHAT it owes, in which order, and what
 *     the durable state allows. The plugin never compensates its own resource: a second
 *     authority here would be a second, divergent copy of the rule.
 *
 *  5. A chain that SUCCEEDED keeps its effects, and the engine records it: the promises are
 *     discharged without being paid, so a resumed run will not undo a Saga that worked.
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
        // along its lifecycle, the lifecycle port ends the Saga — undoing its effects or keeping
        // them — and the slot seam is the only way to reach a body of this Step.
        requiredCapabilities = setOf(
            SAGA_COMPENSATION_CAPABILITY,
            SAGA_PLAN_CAPABILITY,
            SAGA_LIFECYCLE_CAPABILITY,
            BODY_SLOT_CAPABILITY,
        ),
    )

    override val handler = StepHandler<SagaChainInput, SagaChainOutput> { input, context ->
        val operations: CompensationOperations =
            context.capabilities.get(SAGA_COMPENSATION_CAPABILITY, CompensationOperations::class)
        val plan: SagaPlanOperations = context.capabilities.get(SAGA_PLAN_CAPABILITY, SagaPlanOperations::class)
        val saga: SagaLifecycle = context.capabilities.get(SAGA_LIFECYCLE_CAPABILITY, SagaLifecycle::class)
        val slots: BodySlotInvoker = context.capabilities.get(BODY_SLOT_CAPABILITY, BodySlotInvoker::class)

        val sagaId = SagaId.of(input.sagaId)
        val contract = contractOf(input.providerContract)
        val forwardOutcomes = mutableListOf<String>()
        var failure: String? = null

        for (action in input.actions) {
            val declaration = declarationOf(sagaId, action)

            // 1. The promise, DURABLY, before the effect leaves the process.
            val armed: CompensationObligation = when (val outcome = operations.arm(declaration)) {
                is CompensationArmOutcome.Armed -> outcome.obligation
                is CompensationArmOutcome.AlreadyArmed -> outcome.obligation

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
            //
            //    The body is handed the operation's key, so a body that recognises its own
            //    keys can answer "already done" when the engine asks it a second time. The
            //    key is the SAME one the engine will use if it has to reconcile this effect,
            //    and the SAME one a resumed run would use.
            val body = slots.invoke(
                PluginStepId(action.forwardSlot),
                BodyInvocationContext(
                    patch = ExecutionContextPatch.Environment(
                        mapOf(
                            SagaOperationIdentity.OPERATION_KEY_ENV to SagaOperationIdentity.derive(
                                armed.forwardOpId,
                                SagaOperationPhase.FORWARD,
                                armed.compensationId,
                            ),
                        ),
                    ),
                ),
            )
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
                    // provider that cannot promise that is reported as UNKNOWN: an effect that
                    // may have happened must not be undone twice, and must not be declared
                    // absent either.
                    //
                    // A provider that RECOGNISES ITS OWN KEYS is also UNKNOWN, and this is the
                    // part worth reading twice. Being able to recognise a key says NOTHING about
                    // what a failed call left behind: a forward that applied the effect and
                    // then failed would be recorded here as "no effect", and it would never be
                    // undone. The re-ask is what tells the two apart, so the row stays open
                    // until the provider has answered.
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

        // 4. The Saga ENDS, and the CHAIN is what says how.
        //
        //    A chain that succeeded KEEPS its effects: the engine discharges the promises
        //    without paying them, so nothing is owed afterwards and a resumed run will not undo
        //    a Saga that succeeded.
        //
        //    A chain that failed is UNDONE: the engine decides what is owed and in which order.
        //    The plugin only hands over the PLAN; the durable state says what is owed, and the
        //    order is the inverse of the order in which the obligations were armed.
        val sagaPlan = SagaPlan(
            sagaId = sagaId,
            // The policy is the provider's promise, and the plugin is the provider: only a
            // provider whose bodies recognise their own keys may be asked twice.
            policy = if (contract == SagaProviderContract.ReconcilableByKey) {
                CompensationPolicy.Reconcilable
            } else {
                CompensationPolicy.NotReconcilable
            },
            actions = input.actions.map { actionPlanOf(it) },
        )

        val directive: CompensationDirective
        val compensated: List<String>
        val kept: List<String>
        if (failure == null) {
            val completion = saga.complete(sagaPlan)
            directive = completion.directive
            compensated = emptyList()
            kept = completion.kept.map { it.value }
        } else {
            val report = saga.compensate(sagaPlan)
            directive = report.directive
            compensated = report.compensated.map { it.value }
            kept = emptyList()
        }

        // A closing that was REFUSED leaves a Saga that needs a human, and a run that says
        // "success" while its Saga needs a human is a lie about the world. So the refusal
        // becomes this step's failure.
        val refusal = (directive as? CompensationDirective.NeedsIntervention)
            ?.let { "the Saga could not be closed: ${it.reason.name}" }

        SagaChainOutput(
            forwardOutcomes = forwardOutcomes,
            compensated = compensated,
            terminal = when (directive) {
                is CompensationDirective.Settled -> "Settled"
                is CompensationDirective.NeedsIntervention -> "NeedsIntervention"
                is CompensationDirective.Compensate -> "Compensate"
                is CompensationDirective.Reconcile -> "Reconcile"
            },
            reason = (directive as? CompensationDirective.NeedsIntervention)?.reason?.name,
            failure = failure ?: refusal,
            kept = kept,
        )
    }

    /**
     * The promise of one action.
     *
     * The fingerprint is DERIVED, and derived from the plan: the same plan must produce the
     * same fingerprint in a later process, or a resumed run would arm a DIFFERENT promise
     * under the same key and be refused as a divergence.
     *
     * It is derived from the plan THIS declaration is built from, not from a second reading
     * of the action. Two readings of one action are two chances to spell the same plan
     * differently, and the digest is the only place the engine remembers which bodies the
     * promise was made about.
     *
     * The form is the PUBLISHED one (`SagaPlanFingerprint.compute`), not a local copy: a
     * consumer outside this repository has to be able to arrive at the same digest, and it
     * can only do that if there is one form to arrive at.
     */
    private fun declarationOf(sagaId: SagaId, action: SagaChainAction): CompensationDeclaration {
        val plan = actionPlanOf(action)
        return CompensationDeclaration(
            sagaId = sagaId,
            actionId = plan.actionId,
            compensationId = plan.compensationId,
            planFingerprint = SagaPlanFingerprint.compute(sagaId, plan),
            resourceRef = plan.resourceRef,
            kind = plan.kind,
            // The body that will undo the effect. The chain owns that body — it is a child of
            // this step's own body — so the engine can measure it and check, later, that the
            // body that runs is the body that was promised.
            compensateSlot = plan.compensateSlot,
        )
    }

    /** The action, as the engine's plan describes it. */
    private fun actionPlanOf(action: SagaChainAction): SagaActionPlan = SagaActionPlan(
        actionId = SagaActionId.of(action.actionId),
        compensationId = CompensationId.of(action.compensationId),
        resourceRef = ResourceRef.of(action.resourceRef),
        kind = CompensationKind.RESTORE,
        forwardSlot = PluginStepId(action.forwardSlot),
        compensateSlot = PluginStepId(action.compensateSlot),
    )
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
