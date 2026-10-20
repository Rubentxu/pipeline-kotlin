package dev.rubentxu.pipeline.v2.sdk.junit.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.boolOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.longOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredObject
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.stringOrNull
import dev.rubentxu.pipeline.v2.sdk.PipelineJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
 *
 * D-012 (C3 follow-on): migrated to `PipelineJson` / `JsonAccessors`. Wire
 * format unchanged.
 */
object JUnitResultsInputCodec : StepCodec<JUnitResultsInput> {

    override fun encode(value: JUnitResultsInput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("reportPath", value.reportPath)
            put("workspaceRoot", value.workspaceRoot)
            put("failOnFailure", value.failOnFailure)
            put("maxReportBytes", value.maxReportBytes)
        }
    )

    override fun decode(encoded: EncodedStepValue): JUnitResultsInput {
        val obj = PipelineJson.decode(encoded)
        return JUnitResultsInput(
            reportPath = obj.requiredString("reportPath"),
            workspaceRoot = obj.requiredString("workspaceRoot"),
            failOnFailure = obj.boolOrNull("failOnFailure") ?: true,
            maxReportBytes = obj.longOrNull("maxReportBytes") ?: JUnitResultsInput.DEFAULT_MAX_REPORT_BYTES,
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
}

/**
 * JSON codec for [JUnitReportSummary]. Symmetric to the input codec so
 * a handler-side failure that returns the typed value lands at the same
 * JSON shape on the wire.
 *
 * D-012 (C3 follow-on): migrated to `PipelineJson` / `JsonAccessors`. Wire
 * format unchanged.
 */
object JUnitReportSummaryCodec : StepCodec<JUnitReportSummary> {

    override fun encode(value: JUnitReportSummary): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
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
    )

    override fun decode(encoded: EncodedStepValue): JUnitReportSummary {
        val obj = PipelineJson.decode(encoded)
        return JUnitReportSummary(
            tests = obj.requiredString("tests").toInt(),
            failures = obj.requiredString("failures").toInt(),
            errors = obj.requiredString("errors").toInt(),
            skipped = obj.requiredString("skipped").toInt(),
            durationSeconds = obj.requiredString("durationSeconds").toDouble(),
            reportPath = obj.requiredString("reportPath"),
        )
    }
}

/**
 * JSON codec for [JUnitResultsOutput] (F5.2 / WU-LPR-FK).
 *
 * Mirrors `CoreShellOutput`'s shape in `CoreShellStep.kt`: the durable
 * payload is the carrier, not just the summary, so that the
 * [dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary]
 * can project `outcome` via `produced as? TypedStepOutput` without
 * ever inspecting a concrete StepKey. The boundary stays Step-agnostic.
 *
 * On the wire:
 *
 *     {
 *       "outcome": { "kind": "Success" }
 *     |  "outcome": { "kind": "Failure", "failureKind": "USER", "message": "..." },
 *       "summary": { "tests": ..., "failures": ..., ... }
 *     }
 *
 * The legacy [JUnitReportSummaryCodec] (summary-only envelope) is still
 * shipped for replay-decode of any historical journal entries; new
 * production writes always use [JUnitResultsOutputCodec].
 *
 * D-012 (C3 follow-on): migrated to `PipelineJson` / `JsonAccessors`. Wire
 * format unchanged.
 */
object JUnitResultsOutputCodec : StepCodec<JUnitResultsOutput> {

    override fun encode(value: JUnitResultsOutput): EncodedStepValue = PipelineJson.encode(
        buildJsonObject {
            put("outcome", encodeOutcome(value.outcome))
            put("summary", encodeSummary(value.summary))
        }
    )

    override fun decode(encoded: EncodedStepValue): JUnitResultsOutput {
        val obj = PipelineJson.decode(encoded)
        val summary = decodeSummary(obj.requiredObject("summary"))
        val outcome = decodeOutcome(obj.requiredObject("outcome"))
        return JUnitResultsOutput(summary, outcome)
    }

    private fun encodeOutcome(outcome: StepOutcome): JsonObject = when (outcome) {
        StepOutcome.Success -> buildJsonObject {
            put("kind", "Success")
        }
        StepOutcome.Unstable -> buildJsonObject {
            put("kind", "Unstable")
        }
        is StepOutcome.Failure -> buildJsonObject {
            put("kind", "Failure")
            put("failureKind", outcome.failure.kind.name)
            put("message", outcome.failure.message)
        }
    }

    private fun decodeOutcome(obj: JsonObject): StepOutcome {
        val kind = obj.requiredString("kind")
        return when (kind) {
            "Success" -> StepOutcome.Success
            "Unstable" -> StepOutcome.Unstable
            "Failure" -> {
                val failureKind = obj.stringOrNull("failureKind")
                    ?.let { name ->
                        runCatching { FailureKind.valueOf(name) }.getOrNull()
                    }
                    ?: FailureKind.ENGINE
                val message = obj.stringOrNull("message")
                    ?: "junit.results: missing failure message in encoded outcome"
                StepOutcome.Failure(PipelineFailure(failureKind, message))
            }
            else -> StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.ENGINE,
                    "junit.results: unknown encoded outcome kind '$kind'",
                ),
            )
        }
    }

    private fun encodeSummary(summary: JUnitReportSummary): JsonObject = buildJsonObject {
        put("tests", summary.tests)
        put("failures", summary.failures)
        put("errors", summary.errors)
        put("skipped", summary.skipped)
        put("durationSeconds", summary.durationSeconds)
        put("reportPath", summary.reportPath)
        put("successful", summary.successful)
        put("failed", summary.failed)
        put("isClean", summary.isClean)
    }

    private fun decodeSummary(obj: JsonObject): JUnitReportSummary = JUnitReportSummary(
        tests = obj.requiredString("tests").toInt(),
        failures = obj.requiredString("failures").toInt(),
        errors = obj.requiredString("errors").toInt(),
        skipped = obj.requiredString("skipped").toInt(),
        durationSeconds = obj.requiredString("durationSeconds").toDouble(),
        reportPath = obj.requiredString("reportPath"),
    )
}
