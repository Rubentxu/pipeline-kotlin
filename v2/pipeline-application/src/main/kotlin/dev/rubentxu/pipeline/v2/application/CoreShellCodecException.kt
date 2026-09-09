package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * G7 — typed decode failure for `core.sh` output codec.
 *
 * Subclassing [RuntimeException] (not [Exception]) keeps the failure visible to
 * the boundary's `try / catch (e: Exception)` adapter so the result shape can
 * become a typed `StepOutcome.Failure(ENGINE)` rather than silently coercing
 * a malformed replay payload to `Success`.
 *
 * The message is part of the diagnostic contract; downstream tooling may surface
 * it in run logs. It MUST NOT include raw payload bytes that could leak secrets.
 */
class CoreShellCodecException(message: String) : RuntimeException(message)

/**
 * G7 — JsonElement helpers used by `core.sh` output codec decode.
 *
 * `asStringOrNull` / `asIntOrNull` / `asLongOrNull` are tolerant readers that
 * return null on type mismatch; the codec explicitly distinguishes "missing"
 * from "wrong-typed" by calling the typed helpers on the validated field.
 *
 * `stringOrThrow` / `intOrThrow` / `boolOrThrow` are strict readers used after
 * the field's presence was already verified.
 */
internal fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun kotlinx.serialization.json.JsonElement?.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.takeIf { it.content.length < 12 }?.content?.toIntOrNull()

internal fun kotlinx.serialization.json.JsonElement?.asLongOrNull(): Long? =
    (this as? JsonPrimitive)?.content?.toLongOrNull()

internal fun kotlinx.serialization.json.JsonElement?.asBoolOrNull(): Boolean? =
    (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun kotlinx.serialization.json.JsonObject.asStringMapOrNull(): Map<String, String>? {
    val obj = this
    return obj.mapValues { (_, v) ->
        (v as? JsonPrimitive)?.content
            ?: throw CoreShellCodecException(
                "details map entry '${obj.keys.firstOrNull()}' must be a string",
            )
    }
}

internal fun kotlinx.serialization.json.JsonElement?.asStringMapOrNull(): Map<String, String>? {
    val obj = this as? JsonObject ?: return null
    return obj.mapValues { (_, v) ->
        (v as? JsonPrimitive)?.content
            ?: throw CoreShellCodecException("details map entry is not a string")
    }
}

internal fun JsonObject.stringOrThrow(name: String): String =
    this[name]?.asStringOrNull()
        ?: throw CoreShellCodecException("missing or non-string required field '$name'")

internal fun JsonObject.intOrThrow(name: String): Int =
    this[name]?.asIntOrNull()
        ?: throw CoreShellCodecException("missing or non-integer required field '$name'")

internal fun JsonObject.boolOrThrow(name: String): Boolean =
    this[name]?.asBoolOrNull()
        ?: throw CoreShellCodecException("missing or non-boolean required field '$name'")
