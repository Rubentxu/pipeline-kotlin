package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.DurableInvocationResolver
import dev.rubentxu.pipeline.v2.application.durable.DurableStepExecutor
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.InvocationReconciliation
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RecoveryInterpretationEngine
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StepLifecycleContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Closed outcome of one scripted registry-step invocation. Never a fabricated value.
 *
 * This ADT is about DELIVERY, not semantics. It answers "did the durable invocation hand the
 * program a usable value?", and it deliberately does not answer "was that value unstable?":
 *
 * - [Success] — the durable invocation produced or restored a usable ENCODED value. It says
 *   nothing about the Step's semantic outcome, which is recovered from the decoded value through
 *   [dev.rubentxu.pipeline.v2.domain.durable.outcomeOf].
 * - [Failed] — the invocation CANNOT deliver a value to the program. This is broader than a
 *   transport error: it also covers a real semantic `StepOutcome.Failure`, a replay/protocol
 *   incompatibility, an admission refusal, and a payload the Step's own codec cannot decode.
 *
 * Widening this ADT with a `Unstable` case would duplicate a semantic fact the typed carrier
 * already owns, and would create a second authority able to disagree with it.
 */
sealed interface ScriptedRegistryResult {
    /**
     * The invocation delivered a usable encoded value: either freshly executed, or restored from
     * the journal. NOT "the Step succeeded" — the semantic outcome lives in the decoded value.
     */
    data class Success(val encodedOutput: EncodedStepValue) : ScriptedRegistryResult

    /** The invocation cannot deliver a value to the program; carries the typed reason. */
    data class Failed(val failure: PipelineFailure) : ScriptedRegistryResult
}

/**
 * S4-D2 — what [ScriptedRegistryInvoker.invokeTyped] hands back: the decoded value the Kotlin
 * program receives, and the canonical outcome recovered from it.
 *
 * Both frontend call paths need both halves. The program needs the value; the run needs the
 * outcome. Returning only the value is what made `Unstable` unrepresentable at this seam.
 *
 * ## The carrier TRANSPORTS; it never CLASSIFIES
 *
 * The constructor is private and the only way in is [from], which derives the outcome from the
 * value it is given. A public `ScriptedTypedResult(value, outcome)` would permit
 *
 * ```kotlin
 * ScriptedTypedResult(value = unstableCarrier, outcome = StepOutcome.Success)   // illegal
 * ```
 *
 * and that is precisely the defect this slice removes: two sources of truth about one fact,
 * free to disagree. Here they cannot be built apart.
 *
 * ## Why there is no separate "outcome the boundary already decided" factory
 *
 * Because the invoker does not use the boundary's `StepOutcome` at all. FRESH and REUSE both
 * arrive here as `Success(encoded)`, are decoded by the Step's own `outputCodec`, and read the
 * outcome from the decoded carrier. That single line is what makes
 * `outcome(FRESH) == outcome(REUSE)` true BY CONSTRUCTION. Threading the boundary's outcome
 * alongside it would reintroduce two answers to the same question — the canonical one and the
 * scripted one — which is the two-authority shape ADR-0103 R1-E removed.
 */
class ScriptedTypedResult<O : Any> private constructor(
    val value: O,
    val outcome: dev.rubentxu.pipeline.v2.domain.StepOutcome,
) {
    companion object {
        /** The only construction: the outcome is DERIVED, never supplied. */
        fun <O : Any> from(value: O): ScriptedTypedResult<O> = ScriptedTypedResult(
            value = value,
            outcome = dev.rubentxu.pipeline.v2.domain.durable.outcomeOf(value),
        )
    }

    override fun toString(): String = "ScriptedTypedResult(value=$value, outcome=$outcome)"

    override fun equals(other: Any?): Boolean = other is ScriptedTypedResult<*> &&
        value == other.value && outcome == other.outcome

    override fun hashCode(): Int = 31 * value.hashCode() + outcome.hashCode()
}

/**
 * Durable identity of one scripted registry-step call site (LFC-2R / R1).
 *
 * Reuses EXACTLY the scripted identity model proven for `sh` (runId, entry point,
 * call site, dynamic scope path, per-scope invocation ordinal) — plus the generic
 * [stepKey]. No Step-specific identity is ever created.
 */
data class ScriptedRegistryCall(
    val runId: String,
    val entryPointId: String,
    val callSiteId: ScriptedCallSiteId,
    val dynamicScopePath: List<String>,
    val invocationOrdinal: Int,
    val stepKey: PluginStepId,
    val encodedInput: EncodedStepValue,
    /**
     * S4-A1: the compiled artifact identity, carried in the operation input so the
     * fingerprint is bound to it. Without this a scripted registry step replays
     * across two different compiled artifacts of the same source position.
     */
    val definitionDigest: String,
) {
    init {
        require(runId.isNotBlank()) { "Scripted run id must not be blank" }
        require(entryPointId.isNotBlank()) { "Scripted entry point id must not be blank" }
        require(invocationOrdinal >= 0) { "Scripted invocation ordinal must not be negative" }
    }

    /** Stable durable operation identity — same tuple discipline as ScriptedOperation. */
    internal fun operationId(): String = stableScriptedKey(
        listOf(runId, entryPointId, callSiteId.value, dynamicScopePath.size.toString()) +
            dynamicScopePath + invocationOrdinal.toString() + stepKey.value,
    )
}

/**
 * LFC-2R / R1 — THE generic scripted→registry seam.
 *
 * Responsibility (and NOTHING else): call-site identity + step identity + encoded
 * input → durable execute-or-reuse → encoded output. The invoker is STEP-AGNOSTIC:
 * it must never name or special-case any concrete Step. Architecture fitness
 * enforces that by scanning this source with comments stripped.
 *
 * ## ADR-0103 RPL-4 — this class no longer interprets durable state
 *
 * It used to answer `when (existing.status)` with its own table — `SUCCEEDED` → restore,
 * everything else → `REPLAY_COMPATIBILITY` — and to hash its fingerprint with a hardcoded
 * `ReplayPolicy.MEMOIZED` regardless of what the Step declared. That made it a SECOND
 * replay authority with a SECOND, stricter policy than the canonical one: a journalled
 * FAILED operation failed closed here, while the same operation under the descriptor's own
 * `RERUN` policy would have been re-executed by the canonical spine.
 *
 * It now does the only three things a frontend may do:
 *
 * ```text
 * 1. ADDRESS     operationId + OperationInput + fingerprint
 * 2. RESOLVE     declared metadata (effects / replayPolicy / recoveryPolicy)
 * 3. FINGERPRINT over the DECLARED policy          ← ADR-0103 RPL-3
 * 4. OBSERVE     journal.get(operationId)          ← obtaining a fact, not interpreting one
 * 5. DELEGATE    DurableInvocationResolver.reconcileInvocation(...)
 * ```
 *
 * and then interprets the CLOSED [InvocationReconciliation] ADT — never an
 * [OperationStatus].
 *
 * A note on what was claimed here before ADR-0103 R1-E, because the claim was false and the code
 * is the authority: the `when (existing.status)` table and the `memoized` import did go, but the
 * `ReplayPolicy` literal in the fingerprint call did **not**. It was still there, hashing every
 * scripted operation under `MEMOIZED` while the canonical dispatcher hashed
 * `metadata.replayPolicy` — so a scripted `core.sh` (declared `RERUN`) and a declarative one were
 * two durable records of the same declared Step under two different identities. A KDoc asserting a
 * change that the code did not make is worse than no KDoc: it stops the next reader from looking.
 * The literal is gone now, and step 3 exists so that its absence is structural — the policy that
 * enters the hash is read from the descriptor, so there is no longer a value there to hardcode.
 *
 * The two responsibilities that stay are the ones RPL-1 and D4 explicitly reserve for a
 * frontend: ADDRESSING, and turning a durable row into a typed value through the Step's
 * DECLARED output codec. The latter is [restoredOutput], which is materialisation, not
 * decision — it is why a runtime-returning call can hand user Kotlin a real `O`.
 *
 * ## What each resolution means here
 *
 * ```text
 * Diverged        → REPLAY_COMPATIBILITY   the durable row is not this invocation
 * RejectedAbort   → REPLAY_COMPATIBILITY   the Step's policy forbids reusing this history
 * ReuseCompleted  → materialise the persisted value via the declared outputCodec
 * RecoverRunning  → see below
 * RecoveryUnobservable → REPLAY_COMPATIBILITY  the substrate could not be observed; see below
 * Execute         → admit, then DurableStepExecutor, then the declared outputCodec
 * ```
 *
 * `RecoveryUnobservable` is the ADR-0103 R1-E case and the reason this invoker has a fifth arm at
 * all. Before it, a required recovery whose substrate could not be inspected was not a resolution
 * — it was the absence of one, and the invoker fell through to `Execute`. It is now its own
 * fail-closed case, and the journal row is left `RUNNING` by the interpretation engine: an
 * operation whose external effect is unknown is neither re-run nor closed.
 *
 * `RecoverRunning` is the one resolution this frontend cannot satisfy, and it fails closed rather
 * than by omission. **But the reason depends on WHICH terminal was recovered, and collapsing the
 * two cases is a conservation-of-information defect, not a simplification.**
 *
 * ```text
 * terminal carried no value facts          → failing closed is CORRECT
 *   LOST / no exit code ever observed
 *   TIMEOUT with no captured output
 *   → the substrate never told us what the program computed.
 *     There is nothing to hand user Kotlin, and `Status(0)` / `""` / `Unit` would be fabrication.
 *
 * terminal DID carry value facts           → failing closed is a CURRENT CANONICAL GAP
 *   Exited(exitCode = 42)                   → the exit code is on the control dir. Observed.
 *   Exited(0, capturedStdout = "abc")      → the output file is on the control dir. Observed.
 *   → `sh(returnStatus = true)` with exit 42 is `Status(42) · Success`, and the authority that
 *     knows that already exists: `classifyShellTerminal(terminal, returnMode)`.
 *     Failing closed here loses `returnMode` BEFORE the authority that can interpret it.
 * ```
 *
 * So the honest statement of this arm's limit is: **this frontend does not yet project a recovered
 * terminal through the Step's own declared contract.** It is not that a recovered value can never be
 * reconstructed — an observed `exitCode` is not a fabricated value. The capability is missing
 * because the projection does not exist yet, and that is tracked as manifestation 2 of
 * `implementation conformance: PARTIAL` in ADR-S4-R1, closed by S4-F1-C under §2.7. The failing-closed
 * behaviour is correct for the no-facts terminals and is a stand-in for the not-yet-built projection
 * on the rest.
 *
 * Meanwhile: the terminal row IS written — the interpretation engine owns that — and the call fails
 * with `REPLAY_COMPATIBILITY`, which is the kind that means "this runtime cannot replay this
 * operation safely". This is not a regression: the previous status table failed the same way on a
 * RUNNING row, and it fails the same way now.
 *
 * ## Fresh/reuse law
 *
 * - FRESH: fail-closed capability admission BEFORE any effect → execute the typed handler
 *   exactly once through [dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary]
 *   → journal the terminal row with the encodedOutput → return it.
 * - REUSE: the durable decision happens BEFORE any capability admission, so a resume never
 *   reconstructs or consults runtime capabilities just to discover that the operation
 *   already succeeded. The persisted output is decoded through the Step's own codec.
 *   `reuse_value == persisted_value` and handler count is unchanged.
 *
 * Replay does NOT require capabilities, and this invoker does not advance the run replay
 * cursor: a scripted call has no canonical stage position (ADR-0103 D7).
 */
internal class ScriptedRegistryInvoker(
    private val registry: StepRegistry,
    private val journal: OperationJournal,
    private val runtimeContextFactory: (call: ScriptedRegistryCall) -> CanonicalRuntimeContext,
    /**
     * Capability bridge construction. The default is the canonical bridge over the
     * runtime context; harnesses may substitute a bridge whose OBSERVATION sources
     * are synthetic (e.g. a non-host platform) without changing any production logic.
     * Admission stays fail-closed and replay never consults this factory.
     */
    private val capabilityAccessFactory: (CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess =
        { context -> CanonicalRuntimeCapabilityAccess(context) },
    /**
     * Pre-decode durable metadata authority. Derived from the registry this invoker already
     * holds, so scripted and canonical resolve a Step's effects, replay policy and recovery
     * policy through the SAME composite rather than through a second lookup. Overridable for
     * harnesses that need a different authority, never for production.
     */
    private val metadataResolver: StepMetadataResolver =
        dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver.composite(registry),
    private val eventSink: EventSink,
    private val invocationResolver: DurableInvocationResolver,
    private val stepExecutor: DurableStepExecutor,
    private val recoveryInterpretation: RecoveryInterpretationEngine,
) {

    /** Read-only definition accessor so callers can project typed outputs through the
     * Step's DECLARED codec — the single output contract, never a parallel decoder. */
    fun definitionFor(key: PluginStepId): dev.rubentxu.pipeline.v2.domain.step.StepDefinition<*, *>? =
        registry.definition(key)

    /**
     * S4-A1 — THE single typed authority for scripted → registry invocation.
     *
     * Everything a runtime-returning scripted call needs is here, exactly once:
     * the durable identity tuple, the Step's DECLARED input codec, the
     * execute-or-reuse decision with fail-closed capability admission, the
     * Step's DECLARED output codec, and the typed-failure translation. A caller
     * supplies only the call site, the Step it wants, and its typed input; it
     * receives the typed output or a typed step exception.
     *
     * The StepKey is read from [StepDefinition.contract] rather than passed
     * separately, because a caller that could name a key different from the
     * definition it handed over would have two sources of truth for one Step.
     * That is a real drift hazard, not a style preference: the key selects the
     * definition at admission time, and the definition supplies the codecs that
     * encode and decode the payload.
     *
     * This method stays STEP-AGNOSTIC. It must never name or special-case any
     * concrete Step; architecture fitness enforces that by scanning this source
     * with comments stripped.
     *
     * It is `internal` on purpose. An external library contributes a
     * [StepDefinition] and the core invokes it; the library never invokes this
     * seam itself, so publishing it would widen the surface for no consumer.
     *
     * Decoding happens HERE rather than in each caller so that "a persisted
     * payload the Step's own codec cannot read" is one typed
     * [FailureKind.REPLAY_COMPATIBILITY] failure instead of six hand-written
     * copies that could drift.
     */
    internal suspend fun <I : Any, O : Any> invokeTyped(
        identity: ScriptedScopeIdentity,
        callSiteId: ScriptedCallSiteId,
        invocationOrdinal: Int,
        definition: StepDefinition<I, O>,
        input: I,
    ): ScriptedTypedResult<O> {
        val result = invoke(
            ScriptedRegistryCall(
                runId = identity.runId,
                entryPointId = identity.entryPointId,
                callSiteId = callSiteId,
                dynamicScopePath = identity.dynamicScopePath,
                invocationOrdinal = invocationOrdinal,
                stepKey = definition.contract.key,
                encodedInput = definition.contract.inputCodec.encode(input),
                definitionDigest = identity.definitionDigest,
            ),
        )
        // S4-D2: FRESH and REUSE converge on ONE line. Both arrive here as
        // `Success(encoded)`, both are decoded through the Step's own declared codec, and both
        // recover their outcome from the decoded carrier. That is why outcome(FRESH) ==
        // outcome(REUSE) holds by construction rather than by two implementations happening to
        // agree. The durable status cannot be the source: an Unstable Step is journalled
        // SUCCEEDED, exactly like a successful one.
        return when (result) {
            is ScriptedRegistryResult.Success -> {
                val decoded: O = try {
                    definition.contract.outputCodec.decode(result.encodedOutput)
                } catch (e: IllegalArgumentException) {
                    throw PipelineStepException(
                        PipelineFailure(
                            FailureKind.REPLAY_COMPATIBILITY,
                            "persisted runtime output is not decodable by " +
                                "${definition.contract.key.value}'s declared codec: ${e.message}",
                        ),
                    )
                }
                ScriptedTypedResult.from(decoded)
            }
            is ScriptedRegistryResult.Failed -> throw PipelineStepException(result.failure)
        }
    }

    suspend fun invoke(call: ScriptedRegistryCall): ScriptedRegistryResult {
        // 1. ADDRESS — the frontend's own durable coordinate system (ADR-0103 RPL-1).
        val stepId = scriptedStepId(call.stepKey)
        val input = OperationInput(
            stepId = stepId,
            params = buildJsonObject {
                put("runId", JsonPrimitive(call.runId))
                put("entryPointId", JsonPrimitive(call.entryPointId))
                put("callSiteId", JsonPrimitive(call.callSiteId.value))
                put("dynamicScopePath", JsonPrimitive(call.dynamicScopePath.joinToString("/")))
                put("invocationOrdinal", JsonPrimitive(call.invocationOrdinal))
                put("encodedInput", JsonPrimitive(call.encodedInput.value))
                put("definitionDigest", JsonPrimitive(call.definitionDigest))
            }.let(::flattenParams),
            runId = call.runId,
            attempt = ATTEMPT,
        )
        // 2. RESOLVE THE DECLARED METADATA — ADR-0103 RPL-2/RPL-3, before anything hashes against it.
        //
        // Pre-decode metadata (effects, replay policy, recovery policy) is the Step's own
        // descriptor, resolved by the SAME composite the canonical coordinator uses. This is
        // the concrete form of ADR-0103's "StepDescriptor is the single authority": scripted
        // does not get to have an opinion about what its Steps declare.
        //
        // The authority signals an unknown key two ways — `null` on the port, or an
        // `EngineInvariantViolation` from the composite's hard-defect default — so both are
        // translated here. Translating at THIS seam is the point: an unknown key is an
        // ordinary admission failure that user Kotlin can observe, and it must arrive as a
        // typed `PipelineStepException`, not as an engine defect escaping `invoke`. The
        // previous hand-written table returned a typed SCHEMA failure for exactly this case,
        // so the public contract is unchanged; only the authority behind it changed.
        val metadata = resolveMetadataOrNull(call.stepKey)
            ?: return ScriptedRegistryResult.Failed(
                PipelineFailure(
                    FailureKind.SCHEMA,
                    "no durable metadata for step '${call.stepKey.value}': it is neither a " +
                        "legacy executable key nor present in the registry",
                ),
            )

        // 3. FINGERPRINT — ADR-0103 RPL-3. The policy that goes INTO the hash is the policy that
        //    actually governs reconciliation for this operation, which is the declared one.
        //
        //    This used to hardcode `ReplayPolicy.MEMOIZED` here while the canonical dispatcher
        //    hashed `metadata.replayPolicy`, so the two surfaces disagreed about the identity of
        //    the same declared policy: a scripted `core.sh` (RERUN) and a declarative one were
        //    durable records of the same Step under different identities. A literal here is not a
        //    neutral default, it is a second opinion about the Step, and the canonical path does
        //    not get to have one.
        val fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
            input, stepId, metadata.replayPolicy, ATTEMPT,
        )
        val operationId = call.operationId()

        // 4. OBSERVE — obtaining the durable fact. ADR-0103 D4: reading a fact is not
        //    interpreting a protocol. What the fact MEANS is decided below, by the resolver.
        val journaled: DurableOperation? = journal.get(operationId)
        val currentOperation = RerunOperation(
            id = operationId,
            fingerprint = fingerprint,
            input = input,
            output = null,
            status = OperationStatus.PENDING,
            attempt = ATTEMPT,
        )

        // 5. DELEGATE — the canonical decision. This invoker never reads a status to choose.
        return when (val resolution = invocationResolver.reconcileInvocation(
            metadata = metadata,
            journaled = journaled,
            currentOperation = currentOperation,
            operationId = operationId,
        )) {
            is InvocationReconciliation.Diverged -> notReplayable(
                "scripted registry step input diverged from its durable history",
            )

            is InvocationReconciliation.RejectedAbort -> notReplayable(
                "scripted registry step history cannot be reused under the policy its descriptor declares",
            )

            // Reuse materialises, it does not decide. The ADT carries no output by design:
            // mixing the decision with the materialisation is exactly what RPL-5 forbids.
            InvocationReconciliation.ReuseCompleted -> restoredOutput(journaled?.output)

            // The interpretation engine journals the recovered terminal row and emits the
            // lifecycle events, because it is the owner of interpreting a resolution. The
            // typed value still cannot be produced HERE — see the class KDoc, and note that
            // "here" is load-bearing: a terminal that carries observed facts is a different
            // case from one that does not, and only the former is this arm's real limit.
            is InvocationReconciliation.RecoverRunning -> {
                recoveryInterpretation.interpret(
                    resolution,
                    RecoveryInterpretationEngine.Request(
                        operationId = operationId,
                        fingerprint = fingerprint,
                        input = input,
                        lifecycleContext = lifecycleFor(call),
                    ),
                )
                notReplayable(
                    "scripted registry step was recovered from a running subprocess; the recovered " +
                        "terminal is journaled, but this frontend does not yet materialise a typed " +
                        "runtime value from it (ADR-S4-R1 §2.7, closed by F1-C)",
                )
            }

            // ADR-0103 R1-E. The observer could not inspect the substrate, so there is nothing to
            // interpret and nothing to materialise: no recovery terminal was produced, and
            // inventing one would be the fabricate-a-runtime-value defect in a new place. The
            // failure surfaces through the same typed seam every other fail-closed case uses, and
            // the journal row is left RUNNING by the interpreter.
            is InvocationReconciliation.RecoveryUnobservable -> notReplayable(
                "scripted registry step requires recovery of a running subprocess but the substrate " +
                    "could not be observed; the operation is left RUNNING rather than re-executed",
            )

            InvocationReconciliation.Execute -> execute(call, operationId, fingerprint, input, journaled)
        }
    }

    /**
     * The Execute arm. Capability admission stays fail-closed and happens BEFORE any effect,
     * and the effective execution plus the durable fold are the canonical ones — this class
     * contributes no `beginOperation`, no terminal append and no cursor write of its own.
     */
    private suspend fun execute(
        call: ScriptedRegistryCall,
        operationId: String,
        fingerprint: dev.rubentxu.pipeline.v2.domain.durable.Fingerprint,
        input: OperationInput,
        journaled: DurableOperation?,
    ): ScriptedRegistryResult {
        val runtime = runtimeContextFactory(call)
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = call.stepKey,
            encodedInput = call.encodedInput,
            availableCapabilities = capabilityAccessFactory(runtime).available(),
        )
        val ready = when (preparation) {
            is ExecutionPreparation.Ready -> preparation.prepared as PreparedRegistryExecution
            is ExecutionPreparation.Rejected -> return ScriptedRegistryResult.Failed(
                PipelineFailure(FailureKind.SCHEMA, preparation.reason),
            )
        }

        val execution = stepExecutor.executeAndJournal(
            operationId = operationId,
            fingerprint = fingerprint,
            input = input,
            journaled = journaled,
            prepared = ready,
            runtime = runtime,
            lifecycleContext = lifecycleFor(call),
        )

        return when (val outcome = execution.outcome) {
            is dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure ->
                ScriptedRegistryResult.Failed(outcome.failure)
            // A runtime-returning Step MUST produce a value: SUCCEEDED with no encoded
            // output is a protocol violation, never a fabricated Unit/false.
            else -> when (val encoded = execution.encodedOutput) {
                null -> ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.ENGINE,
                        "scripted registry step succeeded without a typed runtime output",
                    ),
                )
                else -> ScriptedRegistryResult.Success(encoded)
            }
        }
    }

    /**
     * S4-C4 — TOTAL. A durable payload this runtime cannot read is a typed
     * [ScriptedRegistryResult.Failed], never a thrown [ClassCastException].
     *
     * The previous `else -> Success(EncodedStepValue((raw as JsonPrimitive).content))`
     * was the one malformed-durable-state case in this method that was NOT typed,
     * while every other one already carried a [FailureKind]. `OperationOutput.result` is
     * a `JsonElement`, so a `JsonObject` or `JsonArray` written by a different codec
     * version reached that cast and escaped `invoke` as an exception.
     *
     * `REPLAY_COMPATIBILITY` is the exact kind — "A persisted operation cannot be
     * safely replayed by this runtime" — because a foreign payload IS that.
     *
     * The match is exhaustive over the closed `JsonElement` ADT, so a shape added
     * later cannot fall through to a fabricated value. `JsonNull` is a
     * `JsonPrimitive` whose `content` is the four-character string `"null"`, so
     * accepting it as a primitive would hand a caller a fabricated value for a
     * step that persisted nothing: it is rejected with the same typed failure.
     */
    private fun restoredOutput(output: OperationOutput?): ScriptedRegistryResult =
        when (val raw = output?.result) {
            null -> notReplayable("SUCCEEDED scripted registry step has no persisted output")
            is JsonPrimitive -> if (raw is JsonNull) {
                unreadablePersistedOutput("a JSON null")
            } else {
                ScriptedRegistryResult.Success(EncodedStepValue(raw.content))
            }
            is JsonObject -> unreadablePersistedOutput("a JSON object")
            is JsonArray -> unreadablePersistedOutput("a JSON array")
        }

    private fun unreadablePersistedOutput(shape: String): ScriptedRegistryResult =
        ScriptedRegistryResult.Failed(
            PipelineFailure(
                FailureKind.REPLAY_COMPATIBILITY,
                "persisted scripted registry output is $shape, which is not a typed Step output; " +
                    "the durable state was written by a codec version this runtime cannot read, " +
                    "and reusing it would fabricate a value",
            ),
        )

    /**
     * Lifecycle coordinates for an execution this frontend performs.
     *
     * These are the coordinates the scripted runner ALREADY assigns its
     * [CanonicalRuntimeContext] (`stageName = "scripted"`, `stageIndex = 0`,
     * `stepIndex = invocationOrdinal`), reused verbatim rather than invented here. They
     * name WHERE the execution happened for observability; they are not a cursor position,
     * and this frontend advances no cursor (ADR-0103 D7).
     */
    private fun lifecycleFor(call: ScriptedRegistryCall): StepLifecycleContext = StepLifecycleContext(
        runId = call.runId,
        stageIndex = 0,
        stepIndex = call.invocationOrdinal,
        stepName = call.callSiteId.value,
        stepType = call.stepKey.value,
    )

    /**
     * Total metadata lookup. `EngineInvariantViolation` is the composite resolver's documented
     * signal for a key that is neither legacy-executable nor registered; it is an ordinary
     * admission outcome at this boundary, so it becomes a value rather than an exception.
     */
    private fun resolveMetadataOrNull(key: PluginStepId) =
        try {
            metadataResolver.resolve(key)
        } catch (_: dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation) {
            null
        }

    /** One wording for every "this durable state cannot be replayed" refusal. */
    private fun notReplayable(reason: String): ScriptedRegistryResult = ScriptedRegistryResult.Failed(
        PipelineFailure(FailureKind.REPLAY_COMPATIBILITY, reason),
    )


    /** `OperationInput.params` is Map<String, JsonElement>; keep the flat object form. */
    private fun flattenParams(obj: JsonObject): Map<String, kotlinx.serialization.json.JsonElement> = obj

    private companion object {
        const val ATTEMPT = 1

        /** Namespace rule: `scripted.` + stepKey; collision-checked by fitness tests. */
        fun scriptedStepId(key: PluginStepId): String = "scripted." + key.value
    }
}
