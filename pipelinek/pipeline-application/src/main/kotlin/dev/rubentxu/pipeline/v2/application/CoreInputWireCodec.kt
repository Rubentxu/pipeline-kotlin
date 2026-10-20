package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * THE single wire authority for `core.input` (RP6-B / WU-092 G3.4).
 *
 * Both the DSL compiler and the Step use this object; neither writes the format
 * by hand. That is the whole point: `core.sh` carries a dialect split
 * (`kind=sh|shell`, `command|script`, `returnStdout|returnMode`) that exists only
 * because two places once encoded the same Step, and every alias is a
 * compatibility shim added after the divergence. `Lfc2LockWireAuthorityFitnessTest`
 * froze the same law for `core.lock`; `Lfc2InputWireAuthorityFitnessTest` freezes
 * it for `core.input`.
 *
 * `null` fields are omitted rather than written as JSON null, matching
 * `CoreLockWireCodec` and keeping the payload byte-identical between the compiler
 * and the codec for a default-valued input.
 */
object CoreInputWireCodec : StepCodec<CoreInputInput> {

    override fun encode(value: CoreInputInput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("message", JsonPrimitive(value.message))
            put("ok", JsonPrimitive(value.ok))
            value.submitter?.let { put("submitter", JsonPrimitive(it)) }
            value.id?.let { put("id", JsonPrimitive(it)) }
            value.timeoutSeconds?.let { put("timeoutSeconds", JsonPrimitive(it)) }
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): CoreInputInput {
        val obj = try {
            Json.parseToJsonElement(encoded.value).jsonObject
        } catch (e: Exception) {
            throw CoreInputCodecException(
                "core.input payload is not a JSON object: ${e.message ?: "parse failed"}",
            )
        }
        // `message` is the only mandatory field, and it is mandatory for the same
        // reason it is mandatory in Jenkins: an input step without a message asks
        // an operator to approve nothing in particular.
        val message = obj.stringOrNull("message")
            ?: throw CoreInputCodecException("core.input payload missing mandatory 'message' field")
        return CoreInputInput(
            message = message,
            ok = obj.stringOrNull("ok") ?: "Proceed",
            submitter = obj.stringOrNull("submitter"),
            id = obj.stringOrNull("id"),
            timeoutSeconds = obj["timeoutSeconds"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
}

/** Typed decode failure of a `core.input` payload; never an exception across the kernel boundary. */
class CoreInputCodecException(message: String) : IllegalArgumentException(message)

/**
 * Wire format of an ANSWER, written by whoever responds.
 *
 * Separate from [CoreInputWireCodec] on purpose: the request is engine-owned and
 * the answer is human-owned, they travel in opposite directions, and conflating
 * them would let a human-supplied file dictate the question.
 */
object InputAnswerCodec {

    fun encode(decision: InputDecision): String {
        val obj: JsonObject = buildJsonObject {
            when (decision) {
                is InputDecision.Proceed -> {
                    put("decision", JsonPrimitive("PROCEED"))
                    decision.submitter?.let { put("submitter", JsonPrimitive(it)) }
                    decision.message?.let { put("message", JsonPrimitive(it)) }
                }
                is InputDecision.Abort -> {
                    put("decision", JsonPrimitive("ABORT"))
                    decision.submitter?.let { put("submitter", JsonPrimitive(it)) }
                    decision.message?.let { put("message", JsonPrimitive(it)) }
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Decodes an answer, or returns `null` when the file is not yet a complete,
     * valid answer.
     *
     * `null` is the honest answer for "a human is still typing" and for "this is
     * not an answer at all". Neither may end the wait: a half-written file is not
     * a refusal (SPEC_WU092_INPUT.md §3.4).
     */
    fun decode(raw: String): InputDecision? {
        val obj = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return null
        }
        val kind = obj.stringOrNull("decision") ?: return null
        val submitter = obj.stringOrNull("submitter")
        val message = obj.stringOrNull("message")
        return when (kind) {
            "PROCEED" -> InputDecision.Proceed(submitter, message)
            "ABORT" -> InputDecision.Abort(submitter, message)
            else -> null
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull
}
