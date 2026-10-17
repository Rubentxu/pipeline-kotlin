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
        // The previous shape collected all field results, filtered to
        // the first invalid, and re-extracted the values via unchecked
        // `(x as FieldResult.Valid).value`. The cast was safe by
        // construction (the firstErr check rules out Invalid on every
        // field) but the unchecked form was the dual-emitter defect
        // class: a future FieldResult case (or a refactor that drops
        // the firstErr check) would silently misroute. The new shape
        // uses an exhaustive `when` on each field with smart-cast
        // extraction; the `return ParseOutcome.Malformed(...)` in the
        // Invalid branch is the short-circuit, the Valid branch is
        // the typed value.
        val path = when (val r = requireString(source, fields, "path")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val content = when (val r = requireSha256(source, fields, "content_sha256")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val proof = when (val r = requireSha256(source, fields, "proof_digest")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val authority = when (val r = requireString(source, fields, "authority")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val candidate = when (val r = requireSha256(source, fields, "candidate_sha256")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val fp = when (val r = parseFingerprint(source, fields)) {
            is FingerprintResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FingerprintResult.Valid -> r.value
        }
        return ParseOutcome.Parsed(
            HarnessVerdict.Verified(
                path = path,
                contentSha256 = content,
                proofDigest = proof,
                authority = authority,
                candidateSha256 = candidate,
                failureFingerprint = fp,
            ),
        )
    }

    private fun parseBlocked(source: String, fields: Map<String, String>): ParseOutcome {
        val path = when (val r = requireString(source, fields, "path")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val reason = when (val r = requireString(source, fields, "reason")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        return ParseOutcome.Parsed(
            HarnessVerdict.Blocked(
                path = path,
                reason = reason,
            ),
        )
    }

    private fun parsePending(source: String, fields: Map<String, String>): ParseOutcome {
        val path = when (val r = requireString(source, fields, "path")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        return ParseOutcome.Parsed(HarnessVerdict.Pending(path = path))
    }

    private fun parseFingerprint(source: String, fields: Map<String, String>): FingerprintResult {
        val raw = fields["fingerprint"] ?: return FingerprintResult.Invalid("missing 'fingerprint' object")
        val fpFields = parseFlatObject(raw)
            ?: return FingerprintResult.Invalid("'fingerprint' is not a flat JSON object")
        // Same per-field `when` extraction as parseVerified: the
        // unchecked `(x as FieldResult.Valid).value` was safe by
        // construction (the firstErr check) but the smart-cast
        // `when` is the typed shape and the compiler enforces it.
        val inputDigest = when (val r = requireSha256(source, fpFields, "input_digest")) {
            is FieldResult.Invalid -> return FingerprintResult.Invalid(r.reason)
            is FieldResult.Valid -> r.value
        }
        val symptom = when (val r = requireString(source, fpFields, "symptom")) {
            is FieldResult.Invalid -> return FingerprintResult.Invalid(r.reason)
            is FieldResult.Valid -> r.value
        }
        val type = when (val r = requireString(source, fpFields, "type")) {
            is FieldResult.Invalid -> return FingerprintResult.Invalid(r.reason)
            is FieldResult.Valid -> r.value
        }
        val cause = when (val r = requireString(source, fpFields, "cause")) {
            is FieldResult.Invalid -> return FingerprintResult.Invalid(r.reason)
            is FieldResult.Valid -> r.value
        }
        return FingerprintResult.Valid(
            FailureFingerprint(
                inputDigest = inputDigest,
                symptom = symptom,
                type = type,
                cause = cause,
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
