package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.LocalExecutionTargetResolver
import dev.rubentxu.pipeline.v2.domain.directive.AdmittedDirective
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.StageDirectiveDecision
import dev.rubentxu.pipeline.v2.domain.directive.StageDirectivePlanner
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetResolver
import dev.rubentxu.pipeline.v2.domain.directive.TargetLeaseResult
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.events.StageSkipped
import java.time.Instant
import java.util.UUID

/**
 * TRAIN H4 / PR-020 — the BEFORE_STAGE directive seam, out of the run loop.
 *
 * Admission plus interpretation of a stage's declared directives used to be ~317 lines inline in
 * `CanonicalDurableRunCoordinator.run`, interleaved with the run's control flow: every denial
 * site wrote a `return@run`, and the skip site wrote a `continue@stagesLoop`. That made the seam
 * impossible to read as a unit and impossible to test without a whole run.
 *
 * The split is the one AGENTS.md asks for everywhere else:
 *
 *  - the PURE decisions were already elsewhere and stay there — [StageDirectivePlanner] admits,
 *    [GateCompositionPlanner] composes, and `gateEvaluator` is injected (S2-A) so this engine
 *    interprets a verdict rather than computing one;
 *  - this engine OBSERVES: it emits the admission, denial, evaluation and skip events, which is
 *    interpretation of a decided outcome;
 *  - it RETURNS a [Verdict] and the coordinator applies the run's control flow. A denied or
 *    unsatisfiable directive must not be able to `return@run` from inside a collaborator: only
 *    the owner of the loop decides what the run does next.
 *
 * The event order is the contract and is preserved exactly: admission events before the decode,
 * the denial event before the run folds, the negative verdict observable BEFORE the skip it
 * causes, and the Unverifiable verdict observable before the fail-closed denial.
 */
internal class BeforeStageDirectiveEngine(
    private val eventSink: EventSink,
    private val gateContext: (dev.rubentxu.pipeline.v2.domain.EnvironmentSpec) -> dev.rubentxu.pipeline.v2.domain.directive.GateContext,
    private val gateEvaluator: (WhenPredicate, dev.rubentxu.pipeline.v2.domain.directive.GateContext) -> dev.rubentxu.pipeline.v2.domain.directive.GateVerdict,
    /**
     * S3.1: the ONE seam allowed to answer "does a target exist".
     *
     * Injected rather than constructed, so the engine interprets a decision it
     * did not make. A coordinator that both decided satisfiability and applied
     * the result would be the same defect the S3.1 port exists to prevent.
     *
     * The default lives HERE, on the consumer, rather than as another
     * parameter on the coordinator's constructor, and that placement is
     * deliberate. `CoordinatorGrowthGuardrailTest` ratchets that constructor
     * at 562 lines and instructs that new responsibilities belong in named
     * engines; a resolver default is the engine's own dependency, and the
     * coordinator that merely forwarded one was buying nothing but a line.
     * `WULpr402RuntimeHonestDslFitnessTest` forbids host-environment reads
     * outside the canonical capability bridges, and the previous default read
     * `os.name` inline to build a `PlatformIdentity`. Letting the resolver
     * reach the platform through the `RuntimeConfig` port fixes that at the
     * source rather than by relocating the read: the coordinator no longer
     * knows a host exists, and the platform has exactly one authority —
     * `SystemRuntimeConfig` — which is what `core.isUnix` already uses, so the
     * two cannot disagree about which machine the run is on.
     *
     * The override seam is preserved for when a composition root really does
     * know the granted capability set; until then the default refuses a
     * `CapabilitySet` requirement loudly instead of granting it speculatively.
     */
    private val targetResolver: ExecutionTargetResolver = LocalExecutionTargetResolver(),
) {

    /** What the run must do next. Closed: the coordinator matches it exhaustively. */
    sealed interface Verdict {
        /** The stage starts. Its declared seam directives were admitted and, if gated, satisfied. */
        data object Admitted : Verdict

        /** Fail the run closed with a typed USER failure carrying [reason]. */
        data class Denied(val reason: String) : Verdict

        /**
         * The stage is skipped because a gate was not satisfied. NOT a denial: a skip is a stage
         * outcome, so the coordinator must run the finalizers the pure planner selects for it.
         */
        data class SkipStage(val reason: String) : Verdict
    }

    fun interpret(
        stage: StageNode,
        stageIndex: Int,
        runId: RunId,
        directiveRegistry: DirectiveRegistry?,
    ): Verdict {
        val admitted = when (val admission = admit(stage, stageIndex, runId, directiveRegistry)) {
            // A denial is fail-closed: the stage never starts and the run folds. It must not
            // read as "nothing to do", which is why Admission is a closed result rather than a
            // nullable list.
            is Admission.Denied -> return Verdict.Denied(admission.reason)
            is Admission.Permitted -> admission.seam
        }
        if (admitted.isEmpty()) return Verdict.Admitted

        // S2-C/S2-D: interpret the BEFORE_STAGE seam. Decode EVERY admitted Gate|Evaluate
        // directive through its own definition codec (the registry carries the decoder; the
        // engine never switches on a key).
        val decoded: List<DecodedBeforeStage> = admitted.map { entry ->
            decode(entry, directiveRegistry)
        }

        val deniedDirective = decoded.firstNotNullOfOrNull { it as? DecodedBeforeStage.Denied }
        if (deniedDirective != null) {
            val reason = "directive '" + deniedDirective.key.value + "' " + deniedDirective.reason
            eventSink.append(
                DirectiveDenied(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageIndex = stageIndex,
                    stageName = stage.name,
                    directiveKey = deniedDirective.key.value,
                    reason = reason,
                ),
            )
            return Verdict.Denied(reason)
        }

        // S3.1: resolve declared execution-target requirements BEFORE composing gates.
        //
        // Order matters and is not arbitrary. A gate answers "may this stage
        // proceed"; a resource answers "what is it running on". Resolving first
        // means a stage that both gates and targets never reports a resolution
        // for a body that was going to be skipped, and the resolution event
        // therefore means "this stage is about to run somewhere specific".
        val unresolved = resolveRequirements(decoded, stage, stageIndex, runId)
        if (unresolved != null) return unresolved

        // S2-D: only GATE predicates compose; a decoded Evaluate is observed and dropped by
        // this filter. gateKeys therefore stays the GATE keys — GateEvaluated keeps gate
        // semantics (it is never reused for an Evaluate).
        val predicates = decoded.filterIsInstance<DecodedBeforeStage.GatePredicate>().map { gate ->
            dev.rubentxu.pipeline.v2.domain.directive.GateCompositionPlanner.DeclaredGate(gate.key, gate.predicate)
        }
        val gateKeys = predicates.map { it.key.value }

        return when (val composition = dev.rubentxu.pipeline.v2.domain.directive.GateCompositionPlanner.compose(predicates)) {
            is dev.rubentxu.pipeline.v2.domain.directive.GateCompositionDecision.Conflicting -> {
                eventSink.append(
                    DirectiveDenied(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        directiveKey = composition.key.value,
                        reason = composition.reason,
                    ),
                )
                Verdict.Denied(composition.reason)
            }

            // Empty cannot occur here (the admitted seam list is non-empty), but the ADT is
            // exhaustive and the compiler enforces it.
            dev.rubentxu.pipeline.v2.domain.directive.GateCompositionDecision.Empty -> Verdict.Admitted

            is dev.rubentxu.pipeline.v2.domain.directive.GateCompositionDecision.Composite ->
                evaluateComposition(composition, gateKeys, stage, stageIndex, runId)
        }
    }

    /**
     * The admission result. Closed, because "denied" and "nothing declared" are opposite
     * outcomes and a null could only ever mean one of them by accident.
     */
    private sealed interface Admission {
        /** Fail the stage closed; the denial event has already been observed. */
        data class Denied(val reason: String) : Admission

        /** Admitted. [seam] is the BEFORE_STAGE Gate|Evaluate slice, possibly empty. */
        data class Permitted(val seam: List<AdmittedDirective>) : Admission
    }

    /**
     * The pure admission decision, then its observation. A denial fails the stage closed BEFORE
     * any stage effect; a null registry admits nothing and denies every declared directive.
     */
    private fun admit(
        stage: StageNode,
        stageIndex: Int,
        runId: RunId,
        directiveRegistry: DirectiveRegistry?,
    ): Admission {
        return when (val decision = directiveRegistry?.let { StageDirectivePlanner.decide(it, stage) }) {
            is StageDirectiveDecision.Denied -> {
                eventSink.append(
                    DirectiveDenied(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        directiveKey = decision.key.value,
                        reason = decision.reason,
                    ),
                )
                Admission.Denied(decision.reason)
            }

            is StageDirectiveDecision.Permitted -> {
                // S1-C: one typed admitted event per declared directive, emitted at the same
                // seam that will later interpret them.
                decision.phases.values.flatten().forEach { entry ->
                    eventSink.append(
                        DirectiveAdmitted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            stageIndex = stageIndex,
                            stageName = stage.name,
                            directiveKey = entry.invocation.key.value,
                            phase = entry.phase.name,
                            policy = when (entry.policy) {
                                is DirectiveExecutionPolicy.Evaluate -> "evaluate"
                                is DirectiveExecutionPolicy.Gate -> "gate"
                                is DirectiveExecutionPolicy.ProvideContext -> "provide-context"
                                // S3.1: observable like any other admitted policy. A resource
                                // request that is admitted and then refused is still an admitted
                                // request, and collapsing the two would make "declared and never
                                // resolved" indistinguishable from "never declared".
                                is DirectiveExecutionPolicy.Resource -> "resource"
                            },
                        ),
                    )
                }

                // S2-C/S2-D: the directives the interpreter will decode in the BEFORE_STAGE
                // seam are the ones the DECISION admitted there with policy Gate OR Evaluate —
                // never a re-scan of the stage declaration. Membership is read from the closed
                // policy ADT, never from a key. Admitted directives in other phases or policies
                // (e.g. ProvideContext, D5) are outside this seam.
                Admission.Permitted(
                    decision.phases
                        .getValue(DirectivePhase.BEFORE_STAGE)
                        .filter {
                            it.policy is DirectiveExecutionPolicy.Gate ||
                                it.policy is DirectiveExecutionPolicy.Evaluate ||
                                // S3.1: a resource request is resolved in this seam too. It
                                // is the same phase as a gate - before the body - and
                                // filtering it out here would make it admitted and then
                                // invisible, which is the accepted-metadata defect.
                                it.policy is DirectiveExecutionPolicy.Resource
                        },
                )
            }

            // No composition = no resolution: a stage declaring directives is denied, and a
            // stage declaring none behaves exactly as before.
            null -> Admission.Permitted(emptyList())
        }
    }

    /**
     * Decode one admitted seam directive through its own definition codec, then classify the
     * decode into the seam ADT WITHOUT an unchecked cast. A definition that declares policy Gate
     * but decodes to anything other than a WhenPredicate is a self-contradiction, and the only
     * safe reading of a contradiction is a typed failure — not a ClassCastException escaping
     * into the run loop.
     */
    private fun decode(entry: AdmittedDirective, directiveRegistry: DirectiveRegistry?): DecodedBeforeStage {
        val key = entry.invocation.key
        return when (val result = directiveRegistry?.find(key)?.decodeAny(entry.invocation.encodedArguments)) {
            null -> DecodedBeforeStage.Denied(key, "was admitted but is not resolvable in the registry")

            is DirectiveDecodeResult.Malformed -> DecodedBeforeStage.Denied(key, seamDecodeFailureReason(entry.policy, result.reason))

            is DirectiveDecodeResult.Decoded -> when (entry.policy) {
                is DirectiveExecutionPolicy.Gate -> {
                    val input = result.input
                    if (input is WhenPredicate) {
                        DecodedBeforeStage.GatePredicate(key, input)
                    } else {
                        DecodedBeforeStage.Denied(
                            key,
                            "declared policy Gate but decoded to " + input::class.simpleName + ", not a WhenPredicate",
                        )
                    }
                }

                // Observes and continues; the typed input is discarded (Directive.kt:
                // "evaluate and continue... observes but cannot veto").
                is DirectiveExecutionPolicy.Evaluate -> DecodedBeforeStage.Evaluated(key)

                // Unreachable through the seam filter (Gate | Evaluate only), but the policy
                // ADT is closed and the match must be total: a policy that cannot be
                // interpreted in this seam is a typed denial, never a silent continue.
                is DirectiveExecutionPolicy.ProvideContext -> DecodedBeforeStage.Denied(
                    key,
                    "declared policy provide-context is not interpretable in the BEFORE_STAGE decode seam",
                )

                // S3.1: a resource request decodes to its REQUIREMENT, not to a predicate.
                // The seam is right for it - both are considered before the body - but the
                // shape is different, so it is a distinct case rather than a reused one.
                // [encoded] is carried alongside so the resolution event can report what was
                // DECLARED without re-encoding and risking a divergent byte sequence.
                is DirectiveExecutionPolicy.Resource -> {
                    val input = result.input
                    if (input is ExecutionTargetRequirement) {
                        DecodedBeforeStage.Requirement(
                            key,
                            input,
                            entry.invocation.encodedArguments,
                        )
                    } else {
                        DecodedBeforeStage.Denied(
                            key,
                            "declared policy Resource but decoded to " + input::class.simpleName +
                                ", not an ExecutionTargetRequirement",
                        )
                    }
                }
            }
        }
    }

    /**
     * S3.1: resolve every declared execution target, observing each outcome.
     *
     * Returns a [Verdict.Denied] on the FIRST refusal and null when every
     * requirement was satisfied. A denial is emitted as a [DirectiveDenied] and
     * a grant as an [ExecutionTargetResolved], so an observer can tell the
     * three cases apart: declared and satisfied, declared and refused, and
     * never declared at all.
     */
    private fun resolveRequirements(
        decoded: List<DecodedBeforeStage>,
        stage: StageNode,
        stageIndex: Int,
        runId: RunId,
    ): Verdict? {
        for (entry in decoded.filterIsInstance<DecodedBeforeStage.Requirement>()) {
            when (val result = targetResolver.acquire(entry.requirement)) {
                is TargetLeaseResult.Granted -> eventSink.append(
                    ExecutionTargetResolved(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        directiveKey = entry.key.value,
                        requirement = entry.encoded,
                        targetId = result.targetId,
                    ),
                )

                is TargetLeaseResult.Refused -> {
                    val reason = "stage '" + stage.name + "' execution target: " + result.reason
                    eventSink.append(
                        DirectiveDenied(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            stageIndex = stageIndex,
                            stageName = stage.name,
                            directiveKey = entry.key.value,
                            reason = reason,
                        ),
                    )
                    return Verdict.Denied(reason)
                }
            }
        }
        return null
    }

    /**
     * Evaluate the composed gate ONCE (pure `WhenPredicateEvaluator` via the injected
     * `gateEvaluator`) and observe the verdict before acting on it.
     */
    private fun evaluateComposition(
        composition: dev.rubentxu.pipeline.v2.domain.directive.GateCompositionDecision.Composite,
        gateKeys: List<String>,
        stage: StageNode,
        stageIndex: Int,
        runId: RunId,
    ): Verdict {
        val context = gateContext(stage.environment)
        return when (val verdict = gateEvaluator(composition.predicate, context)) {
            is dev.rubentxu.pipeline.v2.domain.directive.GateVerdict.Satisfied -> {
                // S2-C: the verdict is observable even when it admits the stage — otherwise a
                // satisfied gate and an absent gate are indistinguishable.
                eventSink.append(
                    GateEvaluated(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        directiveKeys = gateKeys,
                        satisfied = true,
                        reason = "",
                    ),
                )
                Verdict.Admitted
            }

            is dev.rubentxu.pipeline.v2.domain.directive.GateVerdict.NotSatisfied -> {
                // S2-C: the negative verdict is observable BEFORE the skip it causes.
                eventSink.append(
                    GateEvaluated(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        directiveKeys = gateKeys,
                        satisfied = false,
                        reason = verdict.reason,
                    ),
                )
                // S2-B: a skip is itself a stage outcome; the coordinator runs the finalizers
                // the pure planner selects for Skipped. The skip event is emitted here because
                // it is the observation of the decision, while the post block is the run's
                // control flow and belongs to the coordinator.
                eventSink.append(
                    StageSkipped(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        reason = verdict.reason,
                    ),
                )
                Verdict.SkipStage(verdict.reason)
            }

            is dev.rubentxu.pipeline.v2.domain.directive.GateVerdict.Unverifiable -> {
                // NOT a skip. Nobody could prove the predicate, so the run fails closed rather
                // than reporting success for work that never ran. The negative verdict is
                // still observable.
                eventSink.append(
                    GateEvaluated(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        directiveKeys = gateKeys,
                        satisfied = false,
                        reason = verdict.reason,
                    ),
                )
                val reason = "stage '${stage.name}' gate could not be verified: " + verdict.reason
                eventSink.append(
                    DirectiveDenied(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stageIndex = stageIndex,
                        stageName = stage.name,
                        directiveKey = gateKeys.first(),
                        reason = reason,
                    ),
                )
                Verdict.Denied(reason)
            }
        }
    }
}
internal sealed interface DecodedBeforeStage {
    val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey

    /** Gate decoded to its predicate; S2-C composition consumes exactly this. */
    data class GatePredicate(
        override val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey,
        val predicate: dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate,
    ) : DecodedBeforeStage

    /**
     * S3.1: a resource request decoded to its requirement.
     *
     * [encoded] is the exact byte sequence the script declared, carried so the
     * resolution event reports the declaration rather than a re-encoding of
     * it. Re-encoding would be a silent way for the event and the program to
     * disagree.
     */
    data class Requirement(
        override val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey,
        val requirement: ExecutionTargetRequirement,
        val encoded: String,
    ) : DecodedBeforeStage

    /** Evaluate decoded: observes and continues; nothing is consumed. */
    data class Evaluated(
        override val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey,
    ) : DecodedBeforeStage

    /**
     * Fail-closed: the directive's decode contract failed (or the policy cannot
     * be interpreted in this seam). The stage never starts.
     */
    data class Denied(
        override val key: dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey,
        val reason: String,
    ) : DecodedBeforeStage
}
internal fun seamDecodeFailureReason(
    policy: dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy,
    decodeReason: String,
): String = when (policy) {
    is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Gate ->
        "declared a gate whose arguments could not be decoded: " + decodeReason

    is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Evaluate ->
        "declared policy evaluate whose arguments could not be decoded: " + decodeReason

    is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.ProvideContext ->
        "declared policy provide-context whose arguments could not be decoded: " + decodeReason

    is dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy.Resource ->
        "declared policy resource whose arguments could not be decoded: " + decodeReason
}
