package dev.rubentxu.pipeline.v2.harness.retirement

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
 *
 * BQ4 v23 (typed JSON parse, 2026-10-17): the previous
 * shape hand-rolled a `parseFlatObject` parser
 * (`{ "k": "v", ... }` with strings only) plus three
 * helpers (`readString`, `unescape`, `findMatchingBrace`)
 * — ~80 lines of character-by-character parsing that
 * did not support numbers, arrays, or escapes beyond
 * `\"`. The KDoc on the helper admitted the limitation:
 * 'the harness shape is frozen and any deviation is a
 * [ParseOutcome.Malformed]'. The new shape uses
 * `kotlinx.serialization.json.Json` for the parse and
 * the typed `JsonObject` API for field access; nested
 * objects (the `fingerprint`) are no longer
 * re-parsed through the hand-rolled parser (the
 * `fingerprint` is already a `JsonObject` after the
 * first parse). The three private helpers and
 * `parseFlatObject` are gone.
 */
object HarnessVerdictReader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

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
        val root = try {
            json.parseToJsonElement(text)
        } catch (ex: Exception) {
            return ParseOutcome.Malformed(
                source,
                "verdict file is not valid JSON: ${ex.message ?: ex::class.simpleName ?: "parse error"}",
            )
        }
        val rootObj = root as? JsonObject
            ?: return ParseOutcome.Malformed(
                source,
                "verdict file is not a flat JSON object",
            )
        val status = (rootObj["status"] as? JsonPrimitive)?.content
            ?: return ParseOutcome.Malformed(source, "missing 'status' field")
        return when (status.lowercase()) {
            "verified" -> parseVerified(source, rootObj)
            "blocked" -> parseBlocked(source, rootObj)
            "pending" -> parsePending(source, rootObj)
            else -> ParseOutcome.UnknownStatus(source, status)
        }
    }

    private fun parseVerified(source: String, root: JsonObject): ParseOutcome {
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
        //
        // BQ4 v23 (typed JSON parse, 2026-10-17): the `fields` argument
        // is now a `JsonObject` (the typed `kotlinx.serialization`
        // shape), not a `Map<String, String>` from the hand-rolled
        // parser. The `requireString` / `requireSha256` helpers
        // access fields via `JsonObject["key"]` and cast to
        // `JsonPrimitive` for the string value; the same per-field
        // `when` extraction is the typed shape.
        val path = when (val r = requireString(source, root, "path")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val content = when (val r = requireSha256(source, root, "content_sha256")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val proof = when (val r = requireSha256(source, root, "proof_digest")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val authority = when (val r = requireString(source, root, "authority")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val candidate = when (val r = requireSha256(source, root, "candidate_sha256")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val fp = when (val r = parseFingerprint(source, root)) {
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

    private fun parseBlocked(source: String, root: JsonObject): ParseOutcome {
        val path = when (val r = requireString(source, root, "path")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        val reason = when (val r = requireString(source, root, "reason")) {
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

    private fun parsePending(source: String, root: JsonObject): ParseOutcome {
        val path = when (val r = requireString(source, root, "path")) {
            is FieldResult.Invalid -> return ParseOutcome.Malformed(source, r.reason)
            is FieldResult.Valid -> r.value
        }
        return ParseOutcome.Parsed(HarnessVerdict.Pending(path = path))
    }

    private fun parseFingerprint(source: String, root: JsonObject): FingerprintResult {
        // BQ4 v23 (typed JSON parse, 2026-10-17): the previous
        // shape re-parsed the `fingerprint` value through
        // `parseFlatObject` (it was stored as a JSON string in
        // the top-level `Map<String, String>`). The new shape
        // accesses the `fingerprint` value as a `JsonObject`
        // directly — the first parse already produced it, and
        // re-parsing through a hand-rolled parser is wasted
        // work plus a fragile double-authority (the top-level
        // parser and the nested parser could disagree on what
        // counts as valid JSON).
        val fp = root["fingerprint"] as? JsonObject
            ?: return FingerprintResult.Invalid("missing 'fingerprint' object")
        // Same per-field `when` extraction as parseVerified: the
        // unchecked `(x as FieldResult.Valid).value` was safe by
        // construction (the firstErr check) but the smart-cast
        // `when` is the typed shape and the compiler enforces it.
        val inputDigest = when (val r = requireSha256(source, fp, "input_digest")) {
            is FieldResult.Invalid -> return FingerprintResult.Invalid(r.reason)
            is FieldResult.Valid -> r.value
        }
        val symptom = when (val r = requireString(source, fp, "symptom")) {
            is FieldResult.Invalid -> return FingerprintResult.Invalid(r.reason)
            is FieldResult.Valid -> r.value
        }
        val type = when (val r = requireString(source, fp, "type")) {
            is FieldResult.Invalid -> return FingerprintResult.Invalid(r.reason)
            is FieldResult.Valid -> r.value
        }
        val cause = when (val r = requireString(source, fp, "cause")) {
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
        root: JsonObject,
        key: String,
    ): FieldResult {
        val raw = (root[key] as? JsonPrimitive)?.content
            ?: return FieldResult.Invalid("missing '$key'")
        if (raw.isBlank()) return FieldResult.Invalid("'$key' is blank")
        return FieldResult.Valid(raw)
    }

    private fun requireSha256(
        source: String,
        root: JsonObject,
        key: String,
    ): FieldResult {
        val raw = (root[key] as? JsonPrimitive)?.content
            ?: return FieldResult.Invalid("missing '$key'")
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
}

sealed interface ParseOutcome {
    data class Parsed(val verdict: HarnessVerdict) : ParseOutcome
    data class Missing(val path: String) : ParseOutcome {
        // BQ4 v16 (retirement carrier self-protect,
        // 2026-10-17): a blank `path` would be the
        // parser's record of "verdict file missing"
        // for a path that has no name — a fabrication.
        init { require(path.isNotBlank()) { "Missing.path must not be blank" } }
    }
    data class Malformed(val path: String, val reason: String) : ParseOutcome {
        init {
            require(path.isNotBlank()) { "Malformed.path must not be blank" }
            require(reason.isNotBlank()) { "Malformed.reason must not be blank" }
        }
    }
    data class UnknownStatus(val path: String, val status: String) : ParseOutcome {
        // BQ4 v16 (retirement carrier self-protect,
        // 2026-10-17): a blank `path` would be the
        // parser's record of "verdict status unknown"
        // for a path that has no name; a blank `status`
        // is the parser's record of an unknown status
        // that has no name — a fabrication. The
        // status field is a diagnostic that names
        // the literal value seen; a blank value would
        // be a fabrication, not a diagnostic.
        init {
            require(path.isNotBlank()) { "UnknownStatus.path must not be blank" }
            require(status.isNotBlank()) { "UnknownStatus.status must not be blank" }
        }
    }
}
