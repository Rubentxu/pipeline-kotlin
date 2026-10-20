package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import kotlinx.serialization.json.JsonPrimitive
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal

/**
 * TRAIN H3 / PR-019 — the INTERPRETATION half of durable recovery.
 *
 * [DurableInvocationResolver] decides. This interprets. The two were previously one `when`
 * block inside `CanonicalDurableRunCoordinator.dispatch`, which meant the coordinator both
 * decided what a resumed run may do and performed the journal write, the cursor advance and
 * the lifecycle events that followed from it. Splitting them is what lets the decision stay
 * pure and testable at HF0 (see `InvocationRecoveryCharacterizationTest`) while the effects
 * live behind this narrow port.
 *
 * ## Why the result is an ADT and not a nullable outcome
 *
 * `Execute` is not a recovery case: it is the absence of one. Returning `StepOutcome?` and
 * treating null as "carry on" would encode the caller's control flow in a sentinel and make
 * an unhandled resolution indistinguishable from a legitimate one. [RecoveryInterpretation]
 * states the two shapes explicitly, so adding a fourth resolution is a compile error here
 * rather than a silent fallthrough in the coordinator.
 *
 * ## The behaviour is moved, not rewritten
 *
 * Each arm is the coordinator's original arm verbatim, including which arms emit
 * StepStarted/StepFinished through [StepExecutionBoundary], which ones write a journal row and
 * which ones advance the replay cursor:
 *
 *  - [InvocationReconciliation.Diverged] settles a typed INFRASTRUCTURE failure and does NOT
 *    emit lifecycle events, because the step never starts.
 *  - [InvocationReconciliation.RecoverRunning] runs the lifecycle boundary and writes the
 *    recovered terminal status.
 *  - [InvocationReconciliation.ReuseCompleted] settles a success with NO lifecycle event and
 *    NO journal write: the row is already terminal, and re-emitting would duplicate it.
 *  - [InvocationReconciliation.RejectedAbort] runs the lifecycle boundary around a typed
 *    INFRASTRUCTURE failure, because the step started and must finish observably.
 *
 * ## ADR-0103 D7 — no replay cursor, and no traversal coordinates
 *
 * This engine used to take a `ReplayCursorStore` and advance it on a recovered success, and
 * its [Request] carried `runIdValue` + `stageIndex` purely to do so. Both are gone. The
 * cursor models where the canonical RUN resumes, which is traversal state; interpreting a
 * recovery is not traversal. Leaving the store here would also have split cursor ownership
 * between this engine and the executor, which is the two-owner shape D7 exists to remove.
 *
 * The consequence is the signal that the boundary is now correct: with `runIdValue` and
 * `stageIndex` deleted as unused, every remaining field of [Request] is a durable fact
 * about the operation or the lifecycle coordinates of the execution. Nothing here needs a
 * canonical stage position, so a frontend that has none can reuse this engine as-is.
 */
internal class RecoveryInterpretationEngine(
    private val eventSink: EventSink,
    private val journal: OperationJournal,
    /**
     * S4-F1-C2 — how a recovered terminal becomes a Step's typed value. NOT nullable, and that is
     * a correction rather than a style choice.
     *
     * It started nullable, with a fail-closed default, on the reasoning that an engine without a
     * materialiser should still persist an accurate terminal and refuse to invent a value. That
     * reasoning was sound and the implementation was still wrong: five composition sites construct
     * this engine, a nullable seam is an invitation to omit it at one of them, and the one that was
     * omitted made EVERY recovered `core.sh` report «cannot yield a value» while looking perfectly
     * healthy — a green build, a fail-closed status, and no signal that a capability was missing.
     *
     * So the seam is closed. A missing materialiser is no longer expressible; the remaining way to
     * fail closed is [RecoveryMaterialisation.NoProjection], which NAMES the Step that did not
     * declare a projection and is therefore diagnosable by whoever reads the journal.
     */
    private val materializer: (
        StepDefinition<*, *>,
        OperationInput,
        DurableTaskTerminal,
        String?,
    ) -> RecoveryMaterialisation = RecoveredExecutionMaterializer::materialize,
) {

    /**
     * The durable facts an interpretation needs. Deliberately not the whole runtime context:
     * this engine decides nothing, it only performs the effects its own resolution names.
     *
     * `runIdValue` and `stageIndex` were removed under ADR-0103 D7. They existed only to
     * advance the replay cursor, and an unused field is exactly how a responsibility
     * reappears in the wrong class later.
     *
     * S4-F1-C3: [definition] joins it because materialising a recovered value needs the Step's own
     * codecs, and reaching the registry from here would invert the dependency. The caller that owns
     * the registry hands it over, so this engine still never resolves a Step by name.
     */
    data class Request(
        val operationId: String,
        val fingerprint: Fingerprint,
        val input: OperationInput,
        val lifecycleContext: StepLifecycleContext,
        val definition: dev.rubentxu.pipeline.v2.domain.step.StepDefinition<*, *>? = null,

        /**
         * S4-F1-C2 — the Step's OWN encoded payload, when the durable [input] is not itself that
         * payload.
         *
         * NEITHER surface stores the Step payload as the durable input. F1-C found this by making the
         * difference observable rather than by reading for it, and the first cut of this seam assumed
         * otherwise and failed on BOTH surfaces:
         *
         * ```text
         * canonical   input.params = { payload: <the Step's JSON, as a string>,
         *                              sandboxProfile?, bodyStructure? }
         * scripted    input.params = { callSiteId, dynamicScopePath, invocationOrdinal,
         *                              encodedInput: <the Step's JSON, as a string>,
         *                              definitionDigest }
         * ```
         *
         * Both envelopes exist for the same reason, and it is a good one: the fingerprint is computed
         * over IDENTITY, so the durable input must carry everything that distinguishes this invocation
         * from another — the payload plus the sandbox profile, the body digest, the call site. The
         * consequence for materialisation is that the envelope is not the Step's payload and a Step
         * codec must never be handed it: `kind` is simply not a key of the envelope, so the decode
         * fails and the failure reads as a corrupt record rather than as a shape mismatch.
         *
         * [input] stays exactly as it is, because the journal append MUST keep the envelope:
         * changing it would change the fingerprint and silently fork the identity of every operation
         * already on disk. This field carries only the MATERIALISATION view, and it is supplied by
         * the surface that wrote the payload — `step.payload.encoded` canonically,
         * `call.encodedInput` in the scripted surface. Neither is reconstructed, so a codec can never
         * receive bytes that differ from the ones the compiler produced.
         */
        val stepPayload: String? = null,
    )

    /**
     * The two shapes every resolution can take. Closed: a new resolution will not compile.
     *
     * S4-F1-C3 — [Settled] now carries the WHOLE [CommonExecutionResult], not a bare
     * [StepOutcome]. The narrowing it used to perform was R14: a recovered invocation could produce
     * an encoded value and the carrier dropped it on the floor before leaving this class, so a
     * recovered `core.sh` could never hand user Kotlin the `42` it had just recovered. Consumers
     * that only need the outcome now project it explicitly with `.result.outcome`, which is the
     * projection being made visible and auditable rather than pre-applied.
     */
    sealed interface RecoveryInterpretation {
        /** The invocation is settled; the caller returns this carrier without executing. */
        data class Settled(val result: CommonExecutionResult) : RecoveryInterpretation

        /** Not a recovery case: the caller proceeds to the effective executor. */
        data object ProceedToExecution : RecoveryInterpretation
    }

    /**
     * Suspend because interpretation is the EFFECTFUL half: two of the four arms run the
     * [StepExecutionBoundary], which emits lifecycle events around a body. The decision
     * ([DurableInvocationResolver]) is the pure half and stays non-suspend — the split is
     * exactly where the effects start.
     */
    suspend fun interpret(
        resolution: InvocationReconciliation,
        request: Request,
    ): RecoveryInterpretation = when (resolution) {
        is InvocationReconciliation.Diverged -> RecoveryInterpretation.Settled(
            CommonExecutionResult(
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        FailureKind.INFRASTRUCTURE,
                        "Canonical run diverged at '${resolution.operationId}'",
                    ),
                ),
            ),
        )

        // ADR-0103 R1-E: a required recovery whose substrate could not be inspected.
        //
        // Deliberately effect-free, and that is the whole design. A failure that appended a terminal
        // row — SUCCEEDED, FAILED or LOST alike — would not be fail-closed, it would be
        // fail-destroyed: the RUNNING row is the only evidence that this operation is still in
        // flight, and a later, correctly configured run needs it in order to reconcile. So the
        // invocation fails, and the durable state is left exactly as it was found.
        //
        // LOST in particular is the wrong terminal here. LOST means "the control root was readable and
        // the operation directory held nothing recoverable"; this means "there was no control root to
        // read". The observer keeps those apart in RunningSubprocessObservation, and so does this arm.
        is InvocationReconciliation.RecoveryUnobservable -> RecoveryInterpretation.Settled(
            CommonExecutionResult(outcome = StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.INFRASTRUCTURE,
                    "Recovery of '${resolution.operationId}' is required by the declared recovery " +
                        "policy but the subprocess substrate could not be observed, so no conclusion " +
                        "about its external effect is possible. The operation is left RUNNING: it was " +
                        "neither re-executed nor closed. Configure the runtime control root and re-run " +
                        "to reconcile it.",
                ),
            )),
        )

        is InvocationReconciliation.RecoverRunning -> {
            // ADR-S4-R1 §2.4 + §2.7. The resolution arrives carrying OBSERVED FACTS. Turning
            // them into a Step's value is neither this class's job nor the resolver's: it is the
            // Step's own projection, reached through the generic materialiser. This engine persists
            // what comes back and emits the lifecycle — it re-classifies nothing.
            val terminal = resolution.terminal
            val materialised = materialize(request, terminal, System.currentTimeMillis())
            val result = StepExecutionBoundary(eventSink).execute(request.lifecycleContext) {
                materialised.result
            }
            journal.append(
                RerunOperation(
                    id = request.operationId,
                    fingerprint = request.fingerprint,
                    input = request.input,
                    output = materialised.encoded?.let { encoded ->
                        // The SAME bridge DurableStepExecutor uses, deliberately: a recovered row and
                        // a fresh row are written by one shape, so nothing downstream has to know which
                        // direction produced the value. Timing here is the RECOVERY's own duration,
                        // not the original process's, which is the truth: we are recording how long
                        // reconciling took, because the original duration was never observed.
                        val finishedAt = System.currentTimeMillis()
                        OperationOutput(
                            result = JsonPrimitive(encoded.value),
                            durationMs = (finishedAt - materialised.startedAtMs).coerceAtLeast(0),
                            finishedAt = finishedAt,
                        )
                    },
                    status = materialised.status,
                    attempt = 1,
                ),
            )
            RecoveryInterpretation.Settled(result)
        }

        // Reuse is the one arm that emits nothing: the journal row is already terminal, so
        // re-emitting StepStarted/StepFinished or re-appending it would duplicate the record.
        InvocationReconciliation.ReuseCompleted -> RecoveryInterpretation.Settled(
            CommonExecutionResult(outcome = StepOutcome.Success),
        )

        is InvocationReconciliation.RejectedAbort -> RecoveryInterpretation.Settled(
            StepExecutionBoundary(eventSink).execute(request.lifecycleContext) {
                CommonExecutionResult(
                    outcome = StepOutcome.Failure(
                        PipelineFailure(
                            FailureKind.INFRASTRUCTURE,
                            "Replay aborted for '${resolution.operationId}'",
                        ),
                    ),
                    encodedOutput = null,
                )
            },
        )

        InvocationReconciliation.Execute -> RecoveryInterpretation.ProceedToExecution
    }

    /**
     * S4-F1-C2 — resolve the terminal through the Step-owned projection, and fail closed otherwise.
     *
     * Every non-materialised case still produces a REAL [CommonExecutionResult] whose outcome states
     * why, rather than a sentinel or a `null` the caller has to interpret. The durable status is
     * tracked beside it because «could not deliver a value» and «what the process did» are
     * different questions with different owners: the first is the Step contract's, the second is the
     * substrate fact's.
     */
    private fun materialize(
        request: Request,
        terminal: DurableTaskTerminal,
        startedAtMs: Long,
    ): RecoveryMaterialisationOutcome {
        val project = materializer
        val definition = request.definition
        if (definition == null) {
            return failedClosed(
                key = PluginStepId(request.input.stepId),
                reason = "the Step definition could not be resolved, so its contract is unknown and " +
                    "nothing can be said about the value it was going to produce",
                terminal = terminal,
                startedAtMs = startedAtMs,
            )
        }
        return when (val m = project(definition, request.input, terminal, request.stepPayload)) {
            is RecoveryMaterialisation.Materialised -> RecoveryMaterialisationOutcome(
                result = m.result,
                // A materialised EXITED value carries the Step's OWN outcome, and the durable status
                // follows from it through the same projection the FRESH path uses — so a recovered
                // `Status(42)` journals SUCCEEDED exactly as a fresh `Status(42)` does, which is the
                // whole point of routing the terminal through the contract.
                //
                // It is NOT that way for every terminal. A `Lost` or a `Cancelled` materialises into a
                // `ShellInvocationResult.Failed` whose `toStepOutcome()` is a plain Failure, and
                // letting THAT choose the status collapsed LOST into FAILED — which destroys the
                // storage vocabulary that says "the outcome could not be determined", and with it the
                // distinction D-1 and §2.3 are built on. So the terminal owns the status for the
                // cases where the terminal IS the fact, and the outcome owns it only where the
                // contract supplied the fact.
                status = if (terminal is DurableTaskTerminal.Exited) {
                    m.result.outcome.toOperationStatus()
                } else {
                    terminalStorageStatus(terminal)
                },
                encoded = m.result.encodedOutput,
                startedAtMs = startedAtMs,
            )

            is RecoveryMaterialisation.InsufficientEvidence -> failedClosed(
                key = definition.contract.key,
                reason = m.reason,
                terminal = terminal,
                startedAtMs = startedAtMs,
            )

            is RecoveryMaterialisation.NoProjection -> failedClosed(
                key = m.key,
                reason = "the Step declares no recovered projection, so it has not said how its " +
                    "value would be rebuilt from observed facts",
                terminal = terminal,
                startedAtMs = startedAtMs,
            )

            is RecoveryMaterialisation.MalformedInput -> failedClosed(
                key = m.key,
                reason = "the durable input could not be decoded by the Step's own codec",
                terminal = terminal,
                startedAtMs = startedAtMs,
            )
        }
    }

    private fun failedClosed(
        key: PluginStepId,
        reason: String,
        terminal: DurableTaskTerminal,
        startedAtMs: Long,
    ): RecoveryMaterialisationOutcome = RecoveryMaterialisationOutcome(
        result = CommonExecutionResult(outcome = insufficientEvidenceOutcome(key, reason, terminal)),
        status = terminalStorageStatus(terminal),
        encoded = null,
        startedAtMs = startedAtMs,
    )
}

/**
 * What an interpretation persists and returns, kept as three fields rather than folded into the
 * carrier because they answer three different questions:
 *
 * ```text
 * result   what the invocation produced  → the caller, intact
 * status   what to STORE                 → the journal
 * encoded  the wire form of the value    → the journal, null when there is no value
 * ```
 *
 * `status` is deliberately NOT a projection of `result.outcome` in the failing cases: the process may
 * have finished successfully while the Step still could not deliver its value, and those are two
 * statements. In the materialised case it IS a projection, because there the contract said which.
 */
private data class RecoveryMaterialisationOutcome(
    val result: CommonExecutionResult,
    val status: OperationStatus,
    val encoded: EncodedStepValue?,
    val startedAtMs: Long,
)
