package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * THE single authority for the `core.lock` wire format (RP6-A / WU-091).
 *
 * ## Why this is extracted rather than private to [CoreLockStep]
 *
 * The DSL compiler also has to PRODUCE this payload, because `StepSpec.Lock` lives
 * in `pipeline-domain` where `CoreLockInput` cannot be seen. That creates a
 * temptation: let the compiler hand-write the JSON. Doing so would leave two
 * producers of one format, and the compiler would drift.
 *
 * That is not hypothetical — it is exactly what happened to `core.sh`, whose codec
 * today has to accept BOTH spellings of the same payload:
 *
 * ```text
 * kind        = sh      (compiler)  |  shell   (codec's own self-encoding)
 * script      = command (compiler)  |  ...
 * returnStdout/ returnMode            (two vocabularies, one meaning)
 * ```
 *
 * Every one of those aliases is a compatibility shim added after the two producers
 * diverged. WU-091 refuses to manufacture the same debt: the compiler and the Step
 * both call [encode] on this ONE object, so the payload has one spelling by
 * construction.
 *
 * ## The boundary this preserves
 *
 * `pipeline-scripting-api` and `pipeline-domain` never learn about JSON, the
 * coordinator, the capability or the backend. The compiler knows the
 * `StepSpec.Lock -> CoreLockInput` transformation, which is a DOMAIN decision; it
 * does not know the wire format, which is an ENCODING decision. `G3.5` in
 * `SPEC_WU091_LOCK.md` makes that boundary mechanically checkable.
 *
 * `internal` rather than private precisely so the compiler in this same module can
 * reach it; it is not part of any published surface.
 */
internal object CoreLockWireCodec : StepCodec<CoreLockInput> {

    override fun encode(value: CoreLockInput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("resource", JsonPrimitive(value.resource))
            put("skipIfLocked", JsonPrimitive(value.skipIfLocked))
            // Absent optionals are OMITTED, not written as null: a payload that
            // carries "timeoutSeconds": null and one that omits the key are the
            // same input, and emitting both spellings is how a format grows a
            // dialect.
            value.timeoutSeconds?.let { put("timeoutSeconds", JsonPrimitive(it)) }
            value.reason?.let { put("reason", JsonPrimitive(it)) }
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): CoreLockInput {
        val obj = try {
            Json.parseToJsonElement(encoded.value).jsonObject
        } catch (e: Exception) {
            throw CoreLockCodecException(
                "core.lock arguments are not a JSON object: ${e.message ?: "parse failed"}",
            )
        }
        val resource = obj.stringOrNull("resource")
            ?: throw CoreLockCodecException("core.lock arguments missing mandatory 'resource'")
        val skipIfLocked = obj.booleanOrNull("skipIfLocked") ?: false
        val timeoutSeconds = when (val el = obj["timeoutSeconds"]) {
            null, JsonNull -> null
            else -> el.jsonPrimitive.contentOrNull?.toIntOrNull()
                ?: throw CoreLockCodecException("core.lock 'timeoutSeconds' is not an integer")
        }
        return CoreLockInput(
            resource = resource,
            timeoutSeconds = timeoutSeconds,
            reason = obj.stringOrNull("reason"),
            skipIfLocked = skipIfLocked,
        )
    }
}

/**
 * Nullable JSON accessors, `internal` because the `core.lock` OUTPUT codec in
 * [CoreLockStep] reads the same envelope vocabulary. Kept in one place so a
 * change in how an absent field is treated cannot land on the input side only.
 */
internal fun JsonObject.stringOrNull(field: String): String? =
    this[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

internal fun JsonObject.booleanOrNull(field: String): Boolean? =
    this[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull

internal fun JsonObject.longOrNull(field: String): Long? =
    this[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.toLongOrNull()
