package dev.rubentxu.pipeline.v2.sdk.junit.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON codec for [JUnitResultsInput] (F5.2).
 *
 * Same shape as the SCM/Git codec: kotlinx.serialization runtime JSON
 * API (no plugin), symmetric encode/decode, `encodeDefaults = true`
 * preserved via explicit `put` for every field so the roundtrip is
 * lossless.
 */
object JUnitResultsInputCodec : StepCodec<JUnitResultsInput> {

    override fun encode(value: JUnitResultsInput): EncodedStepValue {
        val obj = buildJsonObject {
            put("reportPath", value.reportPath)
            put("workspaceRoot", value.workspaceRoot)
            put("failOnFailure", value.failOnFailure)
            put("maxReportBytes", value.maxReportBytes)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): JUnitResultsInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        return JUnitResultsInput(
            reportPath = obj.getValue("reportPath").jsonPrimitive.content,
            workspaceRoot = obj.getValue("workspaceRoot").jsonPrimitive.content,
            failOnFailure = obj.boolOr("failOnFailure") ?: true,
            maxReportBytes = obj.longOr("maxReportBytes") ?: JUnitResultsInput.DEFAULT_MAX_REPORT_BYTES,
        )
    }

    override fun schema(): String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "JUnitResultsInput",
          "type": "object",
          "required": ["reportPath", "workspaceRoot"],
          "properties": {
            "reportPath":      {"type": "string"},
            "workspaceRoot":   {"type": "string"},
            "failOnFailure":   {"type": "boolean", "default": true},
            "maxReportBytes":  {"type": "integer", "default": 10485760}
          }
        }
    """.trimIndent()

    private fun JsonObject.boolOr(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.longOr(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
}

/**
 * JSON codec for [JUnitReportSummary]. Symmetric to the input codec so
 * a handler-side failure that returns the typed value lands at the same
 * JSON shape on the wire.
 */
object JUnitReportSummaryCodec : StepCodec<JUnitReportSummary> {

    override fun encode(value: JUnitReportSummary): EncodedStepValue {
        val obj = buildJsonObject {
            put("tests", value.tests)
            put("failures", value.failures)
            put("errors", value.errors)
            put("skipped", value.skipped)
            put("durationSeconds", value.durationSeconds)
            put("reportPath", value.reportPath)
            put("successful", value.successful)
            put("failed", value.failed)
            put("isClean", value.isClean)
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun decode(encoded: EncodedStepValue): JUnitReportSummary {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        return JUnitReportSummary(
            tests = obj.getValue("tests").jsonPrimitive.int,
            failures = obj.getValue("failures").jsonPrimitive.int,
            errors = obj.getValue("errors").jsonPrimitive.int,
            skipped = obj.getValue("skipped").jsonPrimitive.int,
            durationSeconds = obj.getValue("durationSeconds").jsonPrimitive.double,
            reportPath = obj.getValue("reportPath").jsonPrimitive.content,
        )
    }
}
