package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed output for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsWriteYamlStepDefinition].
 *
 * Mirrors Jenkins' contract: a successful write is either
 *
 *  - a file write (`wroteToFile = true`, `text = null`,
 *    `absolutePath`/`byteSize`/`sha256Hex` populated), or
 *  - a text return (`wroteToFile = false`, `text` populated, file fields
 *    are `null`).
 *
 * Exactly one of [text] and [absolutePath] is populated. The other field is
 * `null`.
 */
@Serializable
data class WriteYamlOutput(
    /**
     * `true` ⇔ the YAML was written to a file. `false` ⇔ it was returned as
     * a string. The invariant is enforced by construction: a `ToText`
     * destination cannot produce a file, and vice-versa.
     */
    val wroteToFile: Boolean,
    /** The YAML text when [destination] was `ToText`. `null` for file writes. */
    val text: String? = null,
    /** Absolute path of the file when [destination] was `ToFile`. `null` for text returns. */
    val absolutePath: String? = null,
    /** Byte size of the written file. `null` for text returns. */
    val byteSize: Long? = null,
    /** Lowercase hex SHA-256 digest of the written file. `null` for text returns. */
    val sha256Hex: String? = null,
)
