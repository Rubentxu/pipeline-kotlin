package dev.rubentxu.pipeline.v2.harness.retirement

import java.nio.file.Files
import java.nio.file.Path

/**
 * Adapter-boundary reader for the `pipelinek-release-harness`
 * verdicts. The harness writes one JSON file per migrated UAT
 * under a directory; this reader turns the file into a typed
 * [HarnessVerdict] or a typed [ParseError].
 *
 * The reader is fail-closed: a missing field, a blank path, a
 * non-64-char digest or a malformed status string is reported as
 * a [ParseError], not as a default. The applier only sees
 * well-formed values; the operator only sees precise failures.
 *
 * Expected JSON shape:
 *
 * ```json
 * {
 *   "status": "verified",
 *   "path": "v2/pipeline-application/src/test/.../UatLocal008.kt",
 *   "content_sha256": "<64 hex>",
 *   "proof_digest": "<64 hex>",
 *   "authority": "pipelinek-release-harness",
 *   "candidate_sha256": "<64 hex>",
 *   "fingerprint": {
 *     "input_digest": "<64 hex>",
 *     "symptom": "StepFailed: no secret in Output Plane",
 *     "type": "OutputLeak",
 *     "cause": "redaction ring-full spin"
 *   }
 * }
 * ```
 *
 * The `status` field accepts `verified` | `blocked` | `pending`
 * (case-insensitive). Anything else is a
 * [ParseOutcome.UnknownStatus].
 */
object HarnessVerdictReader {

    fun read(path: Path): ParseOutcome {
        if (!Files.exists(path)) {
            return ParseOutcome.Missing(path.toString())
        }
        val text = try {
            Files.readString(path)
        } catch (e: Exception) {
            return ParseOutcome.Malformed(path.toString(), e.message ?: "read error")
        }
        return readText(path.toString(), text)
    }

    /**
     * Pure parser. Exposed for tests: callers may want to validate
     * the contents of a JSON file in memory without touching the
     * filesystem.
     */
    fun readText(source: String, text: String): ParseOutcome {
        val fields = parseFlatObject(text) ?: return ParseOutcome.Malformed(
            source,
            "verdict file is not a flat JSON object",
        )
        val status = (fields["status"]
            ?: return ParseOutcome.Malformed(source, "missing 'status' field"))
            .lowercase()
        return when (status) {
            "verified" -> parseVerified(source, fields)
            "blocked" -> parseBlocked(source, fields)
            "pending" -> parsePending(source, fields)
            else -> ParseOutcome.UnknownStatus(source, status)
        }
    }

    private fun parseVerified(source: String, fields: Map<String, String>): ParseOutcome {
        val path = requireString(source, fields, "path")
        val content = requireSha256(source, fields, "content_sha256")
        val proof = requireSha256(source, fields, "proof_digest")
        val authority = requireString(source, fields, "authority")
        val candidate = requireSha256(source, fields, "candidate_sha256")
        val fp = parseFingerprint(source, fields)
        val firstErr: FieldResult.Invalid? = listOf(path, content, proof, authority, candidate)
            .filterIsInstance<FieldResult.Invalid>()
            .firstOrNull()
        if (firstErr != null) {
            return ParseOutcome.Malformed(source, firstErr.reason)
        }
        if (fp is FingerprintResult.Invalid) {
            return ParseOutcome.Malformed(source, fp.reason)
        }
        return ParseOutcome.Parsed(
            HarnessVerdict.Verified(
                path = (path as FieldResult.Valid).value,
                contentSha256 = (content as FieldResult.Valid).value,
                proofDigest = (proof as FieldResult.Valid).value,
                authority = (authority as FieldResult.Valid).value,
                candidateSha256 = (candidate as FieldResult.Valid).value,
                failureFingerprint = (fp as FingerprintResult.Valid).value,
            ),
        )
    }

    private fun parseBlocked(source: String, fields: Map<String, String>): ParseOutcome {
        val path = requireString(source, fields, "path")
        val reason = requireString(source, fields, "reason")
        val firstErr: FieldResult.Invalid? = listOf(path, reason)
            .filterIsInstance<FieldResult.Invalid>()
            .firstOrNull()
        if (firstErr != null) {
            return ParseOutcome.Malformed(source, firstErr.reason)
        }
        return ParseOutcome.Parsed(
            HarnessVerdict.Blocked(
                path = (path as FieldResult.Valid).value,
                reason = (reason as FieldResult.Valid).value,
            ),
        )
    }

    private fun parsePending(source: String, fields: Map<String, String>): ParseOutcome {
        val path = requireString(source, fields, "path")
        if (path is FieldResult.Invalid) {
            return ParseOutcome.Malformed(source, path.reason)
        }
        return ParseOutcome.Parsed(HarnessVerdict.Pending(path = (path as FieldResult.Valid).value))
    }

    private fun parseFingerprint(source: String, fields: Map<String, String>): FingerprintResult {
        val raw = fields["fingerprint"] ?: return FingerprintResult.Invalid("missing 'fingerprint' object")
        val fpFields = parseFlatObject(raw)
            ?: return FingerprintResult.Invalid("'fingerprint' is not a flat JSON object")
        val inputDigest = requireSha256(source, fpFields, "input_digest")
        val symptom = requireString(source, fpFields, "symptom")
        val type = requireString(source, fpFields, "type")
        val cause = requireString(source, fpFields, "cause")
        val firstErr: FieldResult.Invalid? = listOf(inputDigest, symptom, type, cause)
            .filterIsInstance<FieldResult.Invalid>()
            .firstOrNull()
        if (firstErr != null) {
            return FingerprintResult.Invalid(firstErr.reason)
        }
        return FingerprintResult.Valid(
            FailureFingerprint(
                inputDigest = (inputDigest as FieldResult.Valid).value,
                symptom = (symptom as FieldResult.Valid).value,
                type = (type as FieldResult.Valid).value,
                cause = (cause as FieldResult.Valid).value,
            ),
        )
    }

    private fun requireString(
        source: String,
        fields: Map<String, String>,
        key: String,
    ): FieldResult {
        val raw = fields[key] ?: return FieldResult.Invalid("missing '$key'")
        if (raw.isBlank()) return FieldResult.Invalid("'$key' is blank")
        return FieldResult.Valid(raw)
    }

    private fun requireSha256(
        source: String,
        fields: Map<String, String>,
        key: String,
    ): FieldResult {
        val raw = fields[key] ?: return FieldResult.Invalid("missing '$key'")
        if (raw.length != 64 || !raw.all { it in '0'..'9' || it in 'a'..'f' }) {
            return FieldResult.Invalid("'$key' is not a 64-char lowercase hex SHA-256")
        }
        return FieldResult.Valid(raw)
    }

    private sealed interface FieldResult {
        data class Valid(val value: String) : FieldResult
        data class Invalid(val reason: String) : FieldResult {
            init { require(reason.isNotBlank()) }
        }
    }

    private sealed interface FingerprintResult {
        data class Valid(val value: FailureFingerprint) : FingerprintResult
        data class Invalid(val reason: String) : FingerprintResult {
            init { require(reason.isNotBlank()) }
        }
    }

    /**
     * Very small JSON-flat-object parser. Accepts
     * `{ "k": "v", "k2": "v2" }` with strings only. Nested objects
     * are allowed for `fingerprint` (the only nested object we
     * know). Numbers, arrays and escapes beyond `\"` are not
     * supported; the harness shape is frozen and any deviation is
     * a [ParseOutcome.Malformed].
     */
    private fun parseFlatObject(text: String): Map<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val body = trimmed.substring(1, trimmed.length - 1)
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= body.length) break
            if (body[i] != '"') return null
            val keyEnd = readString(body, i) ?: return null
            val key = unescape(body.substring(i + 1, keyEnd))
            i = keyEnd + 1
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != ':') return null
            i++
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) return null
            if (body[i] == '{') {
                val nestedEnd = findMatchingBrace(body, i) ?: return null
                out[key] = body.substring(i, nestedEnd + 1)
                i = nestedEnd + 1
            } else if (body[i] == '"') {
                val valEnd = readString(body, i) ?: return null
                out[key] = unescape(body.substring(i + 1, valEnd))
                i = valEnd + 1
            } else {
                return null
            }
        }
        return out
    }

    private fun readString(s: String, start: Int): Int? {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> {
                    if (i + 1 >= s.length) return null
                    i += 2
                }
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\")

    private fun findMatchingBrace(s: String, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '"' -> {
                    val end = readString(s, i) ?: return null
                    i = end + 1
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}

sealed interface ParseOutcome {
    data class Parsed(val verdict: HarnessVerdict) : ParseOutcome
    data class Missing(val path: String) : ParseOutcome
    data class Malformed(val path: String, val reason: String) : ParseOutcome {
        init { require(reason.isNotBlank()) }
    }
    data class UnknownStatus(val path: String, val status: String) : ParseOutcome
}
