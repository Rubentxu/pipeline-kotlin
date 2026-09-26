package dev.rubentxu.pipeline.v2.sdk

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Helpers for [StepCodec][dev.rubentxu.pipeline.v2.domain.step.StepCodec]
 * implementations that build a JSON payload via the kotlinx.serialization runtime
 * API (no plugin, manual `buildJsonObject`).
 *
 * C3 / D-011 (audit 2026-09-26, H14): 44 production codec sites in
 * `pipeline-step-sdk` repeat the same three operations:
 *
 * ```kotlin
 * val obj = buildJsonObject { put("k", v) }
 * EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
 *
 * val obj = Json.parseToJsonElement(encoded.value).jsonObject
 * obj.getValue("k").jsonPrimitive.content
 * ```
 *
 * This object wraps that pattern. The wire format is unchanged: identical
 * `Json` configuration (`encodeDefaults = true`), identical JSON encoding
 * (`JsonObject.serializer()`), identical decoder access (`jsonPrimitive.content`).
 * Every migrated codec roundtrips byte-identical to its pre-refactor output.
 *
 * Usage:
 *
 * ```kotlin
 * object MyInputCodec : StepCodec<MyInput> {
 *     override fun encode(value: MyInput) = PipelineJson.encode {
 *         put("path", value.path)
 *         put("retries", value.retries)
 *     }
 *     override fun decode(encoded: EncodedStepValue): MyInput {
 *         val obj = PipelineJson.decode(encoded)
 *         return MyInput(
 *             path = obj.string("path"),
 *             retries = obj.longOrNull("retries") ?: 0L,
 *         )
 *     }
 * }
 * ```
 */
object PipelineJson {

    /**
     * The single `Json` instance used by every migrated codec. Keeping it
     * centralised ensures all `encode`/`decode` operations share identical
     * behaviour. `encodeDefaults = true` matches the convention used by every
     * Pattern A codec surveyed (see D-011 / H14 in `.agent/TECH_DEBT_BACKLOG.md`).
     */
    val json: Json = Json { encodeDefaults = true }

    /**
     * Wraps `Json.encodeToString(JsonObject.serializer(), obj)` so a codec can
     * return `EncodedStepValue` in one line. The caller is expected to use
     * `buildJsonObject { ... }` to compose the payload; this helper takes the
     * already-built `JsonObject` and serialises it. Migration is mechanical:
     * replace `EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))`
     * with `PipelineJson.encode(obj)`.
     */
    fun encode(obj: JsonObject): EncodedStepValue =
        EncodedStepValue(json.encodeToString(JsonObject.serializer(), obj))

    /**
     * Inverse of [encode]: parses an `EncodedStepValue` and returns its top-level
     * `JsonObject`. Replaces the `Json.parseToJsonElement(encoded.value).jsonObject`
     * dance.
     */
    fun decode(encoded: EncodedStepValue): JsonObject =
        json.parseToJsonElement(encoded.value).jsonObject
}

/**
 * Convenience accessors for [JsonObject] that replace the repeated
 * `obj.getValue("k").jsonPrimitive.content` and
 * `obj["k"]?.jsonPrimitive?.contentOrNull` chains.
 *
 * Each function returns the typed value or throws when the key is missing AND
 * the field is required (suffix-free variant). For optional fields, use the
 * `*OrNull` variants which return `null` when the key is absent or the value
 * has the wrong primitive kind.
 *
 * These extensions live alongside `PipelineJson` because every Pattern A
 * codec surveyed needed at least the `string` and `longOrNull` accessors.
 * The other variants are provided for completeness and to absorb future
 * migrations without further API churn.
 */
object JsonAccessors {

    fun JsonObject.requiredString(key: String): String =
        getValue(key).jsonPrimitive.content

    fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    fun JsonObject.requiredLong(key: String): Long =
        getValue(key).jsonPrimitive.content.toLong()

    fun JsonObject.longOrNull(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    fun JsonObject.requiredInt(key: String): Int =
        getValue(key).jsonPrimitive.int

    fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    fun JsonObject.requiredDouble(key: String): Double =
        getValue(key).jsonPrimitive.content.toDouble()

    fun JsonObject.doubleOrNull(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    fun JsonObject.requiredBoolean(key: String): Boolean =
        getValue(key).jsonPrimitive.boolean

    fun JsonObject.boolOrNull(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}
