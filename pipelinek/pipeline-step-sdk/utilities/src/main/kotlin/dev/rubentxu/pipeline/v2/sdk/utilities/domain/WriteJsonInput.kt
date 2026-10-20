package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Typed input for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsWriteJsonStepDefinition].
 *
 * - [path]: workspace-relative target file path. Absolute paths are honoured
 *   verbatim. Parent directories are created automatically.
 *
 * - [value]: the typed JSON value to serialise. Either a fully-typed
 *   `JsonElement` tree (the common path) or a raw string in [rawText] when
 *   [useRawText] is true.
 *
 * - [prettyPrint]: when `true` the file is written with 2-space indentation;
 *   otherwise the file is written as compact JSON. Defaults to `true`.
 *
 * - [useRawText]: thin escape hatch for callers that already hold a JSON
 *   string. When true, [value] must be null and [rawText] must be a valid
 *   JSON document. Defaults to `false`.
 *
 * **Replay policy:** `NEVER`. A successful durable write MUST NOT silently
 * re-execute on replay (E-EM-11 NEVER-1). The codec encodes the path + value
 * so the durable fingerprint is stable, but the runtime decision authority
 * (`EffectReplayPolicy.decide`) refuses re-execution when there is an
 * existing journaled status.
 */
@Serializable
data class WriteJsonInput(
    val path: String,
    val value: JsonElement? = null,
    val rawText: String? = null,
    val prettyPrint: Boolean = true,
    val useRawText: Boolean = false,
)
