package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed input for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsReadJsonStepDefinition].
 *
 * Fields:
 * - [path]: workspace-relative path to a UTF-8 text file containing a JSON document.
 *   Absolute paths are honoured verbatim (preserves the documented seam for
 *   callers that supply an absolute path). Invalid UTF-8 produces a typed
 *   USER-class failure, never a generic IOException leak.
 *
 * - [prettyPrint]: when `true` the codec expects (and tolerates) pretty-printed
 *   JSON; the canonical value returned to the caller is the parsed
 *   `JsonElement` form, not the raw text. Defaults to `true` because most
 *   hand-written fixtures use pretty-print.
 *
 * - [returnRawText]: when `true` the Step returns the raw file contents as a
 *   string (bypassing parse). Defaults to `false` (typed parse). This is a
 *   thin escape hatch; typed parsing is the canonical path.
 *
 * **No Any? smuggling.** Every field has a typed Kotlin type; the codec
 * roundtrips exactly these three fields.
 */
@Serializable
data class ReadJsonInput(
    val path: String,
    val prettyPrint: Boolean = true,
    val returnRawText: Boolean = false,
)
