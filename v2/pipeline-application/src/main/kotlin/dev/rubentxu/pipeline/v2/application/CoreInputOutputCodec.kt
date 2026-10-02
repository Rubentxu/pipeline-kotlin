package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
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
 * THE single wire authority for the `core.input` OUTPUT (RP6-B / WU-092).
 *
 * A named authority rather than a private object inside the Step, for the same
 * reason [CoreInputWireCodec] is one: the journal reader, the tests and the Step
 * must all agree on the spelling, and a contract test cannot pin what it cannot
 * reach.
 *
 * Written out rather than derived from a serializer because the output carries two
 * closed ADTs ([InputDecision] and [InputDenialReason]) whose case identity IS the
 * contract: a reader of the journal must be able to tell "a human refused" from
 * "nobody answered", and both end in a failed run.
 */
object CoreInputOutputCodec : StepCodec<CoreInputOutput> {

    override fun encode(value: CoreInputOutput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("requested", JsonPrimitive(value.requested))
            put("bodyRan", JsonPrimitive(value.bodyRan))
            value.decision?.let { put("decision", JsonPrimitive(decisionDiscriminant(it))) }
            value.denial?.let { put("denial", JsonPrimitive(denialDiscriminant(it))) }
            value.denial?.let { reason ->
                when (reason) {
                    is InputDenialReason.TimedOut -> {
                        put("waitedMillis", JsonPrimitive(reason.waitedMillis))
                    }
                    is InputDenialReason.Unanswerable -> {
                        put("diagnostic", JsonPrimitive(reason.diagnostic))
                    }
                    is InputDenialReason.Cancelled -> Unit
                }
            }
            (value.decision as? InputDecision.Proceed)?.message?.let {
                put("decisionMessage", JsonPrimitive(it))
            }
            (value.decision as? InputDecision.Abort)?.message?.let {
                put("decisionMessage", JsonPrimitive(it))
            }
            (value.decision as? InputDecision.Proceed)?.submitter?.let {
                put("decidedBy", JsonPrimitive(it))
            }
            (value.decision as? InputDecision.Abort)?.submitter?.let {
                put("decidedBy", JsonPrimitive(it))
            }
            // `bodyOutcome` — the FIELD — is what the envelope carries, never the
            // derived `outcome`. The two are not the same value: for an abort or a
            // denial the derived outcome is a Failure while the field is null, and
            // journalling the derivation would decode back into a different object
            // than the one that produced it. The projection is recomputed on read,
            // which is the whole point of keeping it derived.
            (value.bodyOutcome as? StepOutcome.Failure)?.let { failure ->
                put("outcomeKind", JsonPrimitive("FAILURE"))
                put("failureKind", JsonPrimitive(failure.failure.kind.name))
                put("failureMessage", JsonPrimitive(failure.failure.message))
            }
            if (value.bodyOutcome is StepOutcome.Success) {
                put("outcomeKind", JsonPrimitive("SUCCESS"))
            }
            if (value.bodyOutcome is StepOutcome.Unstable) {
                put("outcomeKind", JsonPrimitive("UNSTABLE"))
            }
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): CoreInputOutput {
        val obj = try {
            Json.parseToJsonElement(encoded.value).jsonObject
        } catch (e: Exception) {
            throw CoreInputCodecException(
                "core.input output envelope is not a JSON object: ${e.message ?: "parse failed"}",
            )
        }
        val requested = obj.stringOrNull("requested")
            ?: throw CoreInputCodecException("core.input output missing mandatory 'requested' field")
        val bodyRan = obj.booleanOrNull("bodyRan")
            ?: throw CoreInputCodecException("core.input output missing mandatory 'bodyRan' field")
        val decidedBy = obj.stringOrNull("decidedBy")
        val decisionMessage = obj.stringOrNull("decisionMessage")
        val decision = when (obj.stringOrNull("decision")) {
            "PROCEED" -> InputDecision.Proceed(decidedBy, decisionMessage)
            "ABORT" -> InputDecision.Abort(decidedBy, decisionMessage)
            null -> null
            else -> throw CoreInputCodecException(
                "unknown core.input decision variant '${obj.stringOrNull("decision")}'",
            )
        }
        val denial = when (obj.stringOrNull("denial")) {
            "TIMED_OUT" -> InputDenialReason.TimedOut(
                obj.stringOrNull("waitedMillis")?.toLongOrNull() ?: 0L,
            )
            "CANCELLED" -> InputDenialReason.Cancelled
            "UNANSWERABLE" -> InputDenialReason.Unanswerable(
                obj.stringOrNull("diagnostic") ?: "core.input could not ask",
            )
            null -> null
            else -> throw CoreInputCodecException(
                "unknown core.input denial variant '${obj.stringOrNull("denial")}'",
            )
        }
        val outcome = obj.stringOrNull("outcomeKind")?.let { kind ->
            when (kind) {
                "SUCCESS" -> StepOutcome.Success
                "UNSTABLE" -> StepOutcome.Unstable
                "FAILURE" -> StepOutcome.Failure(
                    PipelineFailure(
                        kind = obj.stringOrNull("failureKind")
                            ?.let { name -> FailureKind.entries.firstOrNull { it.name == name } }
                            ?: FailureKind.UNKNOWN,
                        message = obj.stringOrNull("failureMessage")
                            ?: "core.input output recorded a failure without a message",
                    ),
                )
                else -> throw CoreInputCodecException(
                    "unknown core.input outcome variant '$kind'",
                )
            }
        }
        return CoreInputOutput(
            requested = requested,
            decision = decision,
            denial = denial,
            bodyRan = bodyRan,
            bodyOutcome = outcome,
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull
}
