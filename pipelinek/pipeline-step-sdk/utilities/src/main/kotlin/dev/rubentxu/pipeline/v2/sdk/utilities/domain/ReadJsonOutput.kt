package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Typed output for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsReadJsonStepDefinition].
 *
 * The output is a typed value, not a raw `Map<String, Any?>`. Callers that
 * want raw text can read [rawText]; callers that want the parsed structure
 * read [parsed].
 *
 * Exactly one of [rawText] or [parsed] is populated:
 *  - `rawText != null && parsed == null` when [ReadJsonInput.returnRawText] is true;
 *  - `parsed != null && rawText == null` otherwise.
 *
 * **Determinism:** [parsed] preserves the order of JSON object keys as written
 * in the source file. Callers that need a stable canonical form should
 * normalise via the same kotlinx.serialization JSON parser with
 * `prettyPrint = true` (this is what the codec does).
 */
@Serializable
data class ReadJsonOutput(
    /** Raw text read from disk (UTF-8). Always populated, even on parse failures inside a caller that wants both views. */
    val rawText: String,
    /** Parsed JSON tree. `null` only when [ReadJsonInput.returnRawText] is true. */
    val parsed: JsonElement? = null,
    /** Number of bytes read. Useful for diagnostics / event emission. */
    val byteSize: Long,
    /** Absolute path that was actually read (after workspace-root resolution). */
    val absolutePath: String,
)
