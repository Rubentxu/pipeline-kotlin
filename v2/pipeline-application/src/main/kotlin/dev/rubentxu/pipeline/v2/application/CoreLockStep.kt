package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `core.lock` — hold a named resource while the enclosed body runs (RP6-A / WU-091).
 *
 * Contract: `docs/v2/07-uat/SPEC_WU091_LOCK.md`.
 * Reasoning and evidence: `docs/v2/07-uat/RP6A_LOCK_CHARACTERIZATION.md`.
 *
 * ## Why HANDLER_CONTINUATION and not a body policy
 *
 * The body of a `lock` is CONDITIONAL: under contention, under `skipIfLocked`, or
 * on an allocation timeout the body may never run. Every case of the closed
 * [BodyExecutionPolicy] family presumes the body runs, so no case can express it,
 * and adding one would break the exhaustiveness that makes that ADT worth having.
 * A [BodyExecutionOwner.HANDLER_CONTINUATION] Step decides WHETHER to invoke its
 * body and reaches it only through the bound [BodyContinuation]; the engine remains
 * the only executor, on the same durable spine, with the same journal and events.
 *
 * The viability of that shape — and the proof that the engine does not substitute
 * its own body semantics — is `LockFeasibilityProofTest`.
 *
 * ## Effects
 *
 * [Effect.READ_ONLY]: `core.lock` does not touch the workspace. It coordinates, and
 * waiting is a suspension, not a mutation. Declaring more than it does would violate
 * "declared capability == used capability"; declaring less would let a replay
 * policy treat a coordination point as a pure computation.
 */
object CoreLockStep {

    val KEY: PluginStepId = PluginStepId("core.lock")

    private val inputCodec = CoreLockWireCodec

    /**
     * Explicit, lossless round-trip of [CoreLockOutput].
     *
     * Written out rather than derived from a serializer because the output carries
     * two ADTs ([LockAdmission] and [StepOutcome]) whose case identity is the
     * contract: a reader of the journal must be able to tell "took it, body
     * succeeded" from "never took it", and both are `Success`.
     */
    private val outputCodec = object : StepCodec<CoreLockOutput> {
        override fun encode(value: CoreLockOutput): EncodedStepValue {
            val obj: JsonObject = buildJsonObject {
                put("resource", JsonPrimitive(value.resource))
                put("bodyRan", JsonPrimitive(value.bodyRan))
                put("admission", JsonPrimitive(admissionDiscriminant(value.admission)))
                put("outcome", JsonPrimitive(outcomeDiscriminant(value.outcome)))
                when (val a = value.admission) {
                    is LockAdmission.Acquired -> {
                        put("acquiredResource", JsonPrimitive(a.resource))
                        put("reentrant", JsonPrimitive(a.reentrant))
                    }
                    is LockAdmission.Denied -> when (val r = a.reason) {
                        is LockDenialReason.Held -> Unit
                        is LockDenialReason.TimedOut ->
                            put("waitedMillis", JsonPrimitive(r.waitedMillis))
                        is LockDenialReason.Cancelled -> Unit
                    }
                }
                (value.outcome as? StepOutcome.Failure)?.let { f ->
                    put("failureKind", JsonPrimitive(f.failure.kind.name))
                    put("failureMessage", JsonPrimitive(f.failure.message))
                }
            }
            return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
        }

        override fun decode(encoded: EncodedStepValue): CoreLockOutput {
            val obj = try {
                Json.parseToJsonElement(encoded.value).jsonObject
            } catch (e: Exception) {
                throw CoreLockCodecException(
                    "core.lock output envelope is not a JSON object: ${e.message ?: "parse failed"}",
                )
            }
            val resource = obj.stringOrNull("resource")
                ?: throw CoreLockCodecException("core.lock output missing mandatory 'resource' field")
            val bodyRan = obj.booleanOrNull("bodyRan")
                ?: throw CoreLockCodecException("core.lock output missing mandatory 'bodyRan' field")
            val admissionStr = obj.stringOrNull("admission")
                ?: throw CoreLockCodecException("core.lock output missing mandatory 'admission' field")
            val outcomeStr = obj.stringOrNull("outcome")
                ?: throw CoreLockCodecException("core.lock output missing mandatory 'outcome' field")

            val admission: LockAdmission = when (admissionStr) {
                "ACQUIRED" -> LockAdmission.Acquired(
                    resource = obj.stringOrNull("acquiredResource") ?: resource,
                    reentrant = obj.booleanOrNull("reentrant") ?: false,
                )
                "HELD" -> LockAdmission.Denied(LockDenialReason.Held)
                "TIMED_OUT" -> LockAdmission.Denied(
                    LockDenialReason.TimedOut(obj.longOrNull("waitedMillis") ?: 0L),
                )
                "CANCELLED" -> LockAdmission.Denied(LockDenialReason.Cancelled)
                else -> throw CoreLockCodecException("unknown core.lock admission variant '$admissionStr'")
            }

            val outcome: StepOutcome = when (outcomeStr) {
                "SUCCESS" -> StepOutcome.Success
                "UNSTABLE" -> StepOutcome.Unstable
                "FAILURE" -> StepOutcome.Failure(
                    PipelineFailure(
                        kind = obj.stringOrNull("failureKind")
                            ?.let { name -> FailureKind.entries.firstOrNull { it.name == name } }
                            ?: FailureKind.UNKNOWN,
                        message = obj.stringOrNull("failureMessage")
                            ?: "core.lock output recorded a failure without a message",
                    ),
                )
                else -> throw CoreLockCodecException("unknown core.lock outcome variant '$outcomeStr'")
            }

            return CoreLockOutput(
                resource = resource,
                bodyRan = bodyRan,
                admission = admission,
                outcome = outcome,
            )
        }
    }

    private val descriptor = StepDescriptor(
        stepId = KEY.value,
        name = "lock",
        configRef = "",
        pluginId = "core",
        pluginVersion = "0.1.0",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.READ_ONLY),
        // The output is memoized, but the ACQUISITION is not: the handler re-runs on
        // resume and re-acquires before any fresh effect. Memoizing the acquisition
        // token would replay "I hold this" for a hold this process does not have.
        // See SPEC_WU091_LOCK.md section 4.
        replayPolicy = ReplayPolicy.MEMOIZED,
        body = StepBody.Declared(
            invocation = BodyInvocationPolicy.ONCE,
            execution = BodyExecution(
                owner = BodyExecutionOwner.HANDLER_CONTINUATION,
                policy = BodyExecutionPolicy.Sequential,
            ),
            introduces = null,
        ),
    )

    private val handler: StepHandler<CoreLockInput, CoreLockOutput> = StepHandler { input, ctx ->
        val coordinator: LockCoordinator = ctx.capabilities.get(LOCK_COORDINATION_CAPABILITY)
        val continuation: BodyContinuation = ctx.capabilities.get(BODY_CONTINUATION_CAPABILITY)
        val lane: ExecutionLaneId = ctx.capabilities.get(EXECUTION_LANE_CAPABILITY)

        // The owner is the durable EXECUTION LANE, derived by the bridge from the
        // runtime's own operation identity. Same lane re-enters (Jenkins is
        // re-entrant per build, so a nested `lock` must not deadlock against its own
        // hold); a sibling `parallel` branch is a DIFFERENT lane and must contend,
        // which is precisely the exclusion the lock exists to provide.
        val owner = LockOwner(lane)

        when (val resolution = lockIntentOf(input.skipIfLocked, input.timeoutSeconds)) {
            is LockIntentResolution.Rejected -> CoreLockOutput(
                resource = input.resource,
                bodyRan = false,
                admission = LockAdmission.Denied(LockDenialReason.Cancelled),
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        FailureKind.USER,
                        "core.lock: ${resolution.error.diagnostic}",
                    ),
                ),
            )
            is LockIntentResolution.Resolved -> {
                val intent = resolution.intent
                when (val admission = coordinator.acquire(owner, input.resource, intent)) {
                    is LockAdmission.Denied -> denied(input.resource, admission, intent)
                    is LockAdmission.Acquired -> runBody(
                        owner = owner,
                        resource = input.resource,
                        admission = admission,
                        continuation = continuation,
                        coordinator = coordinator,
                    )
                }
            }
        }
    }

    /**
     * The body runs ONLY on a granted hold, and the hold is released after the
     * continuation RETURNS, whatever it returned.
     *
     * [BodyOutcome] is exactly `Completed | Cancelled` and never throws, so the
     * release contract is return-based. The `finally` is defence in depth against
     * an ENGINE defect (a throw across the continuation), and is explicitly NOT a
     * property this file claims to be tested — see
     * `LockFeasibilityProofTest`'s header.
     */
    private suspend fun runBody(
        owner: LockOwner,
        resource: String,
        admission: LockAdmission.Acquired,
        continuation: BodyContinuation,
        coordinator: LockCoordinator,
    ): CoreLockOutput = try {
        when (val body = continuation.invoke(BodyInvocationContext())) {
            is BodyOutcome.Completed -> CoreLockOutput(
                resource = resource,
                bodyRan = true,
                admission = admission,
                outcome = body.outcome,
            )
            is BodyOutcome.Cancelled -> CoreLockOutput(
                resource = resource,
                bodyRan = true,
                admission = admission,
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.INFRASTRUCTURE,
                        message = "core.lock: body of '$resource' was cancelled while the hold was taken",
                    ),
                ),
            )
        }
    } finally {
        coordinator.release(LockHold(owner, admission.resource))
    }

    /**
     * A denied acquisition never runs the body. The three denials are NOT the same
     * outcome:
     *  - `Held` under [LockIntent.Now] is the `skipIfLocked` contract: SUCCESS with
     *    the body not run. Jenkins treats it as success too.
     *  - `Held` under a waiting intent means the coordinator did not honour the wait,
     *    which is a coordinator defect surfaced as a failure rather than swallowed.
     *  - `TimedOut` and `Cancelled` are failures.
     */
    private fun denied(
        resource: String,
        denial: LockAdmission.Denied,
        intent: LockIntent,
    ): CoreLockOutput = when (val reason = denial.reason) {
        is LockDenialReason.Held -> if (intent == LockIntent.Now) {
            CoreLockOutput(
                resource = resource,
                bodyRan = false,
                admission = denial,
                outcome = StepOutcome.Success,
            )
        } else {
            CoreLockOutput(
                resource = resource,
                bodyRan = false,
                admission = denial,
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.INFRASTRUCTURE,
                        message = "core.lock: '$resource' was reported held under a waiting intent",
                    ),
                ),
            )
        }
        is LockDenialReason.TimedOut -> CoreLockOutput(
            resource = resource,
            bodyRan = false,
            admission = denial,
            outcome = StepOutcome.Failure(
                PipelineFailure(
                    kind = FailureKind.TIMEOUT,
                    message = "core.lock: '$resource' was not acquired within ${reason.waitedMillis}ms",
                ),
            ),
        )
        is LockDenialReason.Cancelled -> CoreLockOutput(
            resource = resource,
            bodyRan = false,
            admission = denial,
            outcome = StepOutcome.Failure(
                PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "core.lock: cancelled while waiting for '$resource'",
                ),
            ),
        )
    }

    val definition: StepDefinition<CoreLockInput, CoreLockOutput> =
        object : StepDefinition<CoreLockInput, CoreLockOutput> {
            override val contract: StepContract<CoreLockInput, CoreLockOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                // Two halves of one declaration plus the lane: the port that decides
                // WHETHER to run the body, the bound continuation that runs it, and
                // the durable lane that decides WHO owns the resulting hold. Admission
                // is fail-closed before the handler runs when any is absent, and
                // resolveBodyExecutionPolicy rejects the owner/capability mismatch.
                requiredCapabilities = setOf(
                    LOCK_COORDINATION_CAPABILITY,
                    BODY_CONTINUATION_CAPABILITY,
                    EXECUTION_LANE_CAPABILITY,
                ),
            )

            override val handler: StepHandler<CoreLockInput, CoreLockOutput> = this@CoreLockStep.handler
        }

    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}

/** Typed decode failure of a `core.lock` payload; never an exception across the kernel boundary. */
class CoreLockCodecException(message: String) : IllegalArgumentException(message)

private fun admissionDiscriminant(admission: LockAdmission): String = when (admission) {
    is LockAdmission.Acquired -> "ACQUIRED"
    is LockAdmission.Denied -> when (admission.reason) {
        is LockDenialReason.Held -> "HELD"
        is LockDenialReason.TimedOut -> "TIMED_OUT"
        is LockDenialReason.Cancelled -> "CANCELLED"
    }
}

private fun outcomeDiscriminant(outcome: StepOutcome): String = when (outcome) {
    is StepOutcome.Success -> "SUCCESS"
    is StepOutcome.Unstable -> "UNSTABLE"
    is StepOutcome.Failure -> "FAILURE"
}
