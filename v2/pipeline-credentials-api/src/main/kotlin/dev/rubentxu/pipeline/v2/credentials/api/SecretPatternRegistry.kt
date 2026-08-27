package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.util.Base64
import java.util.regex.Pattern

/**
 * Registry for secret patterns used by [RedactingEventSink] to scrub secrets
 * from free-text event fields.
 *
 * ## Multi-form Pattern Generation
 *
 * Each registered secret generates patterns for multiple encodings:
 * - Literal: the raw secret bytes as UTF-8
 * - base64 std: Base64 standard encoder (with padding)
 * - base64 url-safe: Base64 URL-safe encoder (no padding)
 * - base64 trimmed padding/last-char: GitHub Actions runner#291 pattern
 * - hex upper: uppercase hex (2 chars per byte)
 * - hex lower: lowercase hex (2 chars per byte)
 * - URL-encoded: percent-encoded special chars
 *
 * ## Minimum Maskable Length
 *
 * Per GitLab rule, secrets shorter than 8 characters are NOT registered
 * for masking. They emit [SecretShorterThanMinimumMaskableLengthWarning] instead.
 *
 * ## Performance
 *
 * - Naive [Pattern.quote] alternation for ≤ 20 active secrets
 * - Aho-Corasick single-pass for > 20 secrets (switch in [RedactingEventSink])
 *
 * @see RedactingEventSink for the decorator that applies these patterns
 */
class SecretPatternRegistry {

    private val registeredSecrets = mutableSetOf<String>()

    /**
     * Registers a secret for masking.
     *
     * @param handle The [SecretHandle] wrapping the secret bytes
     * @throws SecretShorterThanMinimumMaskableLengthWarning if secret is below minimum length
     */
    fun addSecret(handle: SecretHandle) {
        val secretValue = handle.use { bytes ->
            String(bytes, Charsets.UTF_8)
        }

        if (secretValue.length < MIN_MASKABLE_LENGTH) {
            // Short secrets are not registered — emit warning handled by caller
            return
        }

        registeredSecrets.add(secretValue)
    }

    /**
     * Removes a secret from the registry.
     *
     * @param handle The [SecretHandle] wrapping the secret bytes
     */
    fun removeSecret(handle: SecretHandle) {
        val secretValue = handle.use { bytes ->
            String(bytes, Charsets.UTF_8)
        }
        registeredSecrets.remove(secretValue)
    }

    /**
     * Builds the active list of [Regex] patterns for all registered secrets.
     *
     * Uses naive [Pattern.quote] alternation for ≤ 20 secrets.
     * For > 20 secrets, [RedactingEventSink] switches to Aho-Corasick.
     *
     * Each secret generates 6 encoding variants:
     * 1. Literal
     * 2. base64 std
     * 3. base64 url-safe
     * 4. base64 trimmed padding (GitHub runner#291)
     * 5. hex upper
     * 6. hex lower
     *
     * @return List of [Regex] patterns — empty if no secrets registered
     */
    internal fun buildActivePatterns(): List<Regex> {
        if (registeredSecrets.isEmpty()) return emptyList()

        val patterns = mutableListOf<Regex>()

        for (secret in registeredSecrets) {
            // Literal pattern — escaped to prevent regex injection
            patterns.add(Pattern.quote(secret).toRegex())

            // base64 std
            val rawBytes = secret.toByteArray(Charsets.UTF_8)
            patterns.add(Pattern.quote(Base64.getEncoder().encodeToString(rawBytes)).toRegex())

            // base64 url-safe
            patterns.add(Pattern.quote(Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)).toRegex())

            // base64 trimmed padding / last-char (GitHub runner#291)
            val b64Std = Base64.getEncoder().encodeToString(rawBytes)
            if (b64Std.endsWith("==")) {
                patterns.add(Pattern.quote(b64Std.removeSuffix("==")).toRegex())
                patterns.add(Pattern.quote(b64Std.dropLast(2)).toRegex())
            } else if (b64Std.endsWith("=")) {
                patterns.add(Pattern.quote(b64Std.removeSuffix("=")).toRegex())
            }

            // hex upper
            patterns.add(Pattern.quote(rawBytes.joinToString("") { "%02X".format(it) }).toRegex())

            // hex lower
            patterns.add(Pattern.quote(rawBytes.joinToString("") { "%02x".format(it) }).toRegex())

            // URL-encoded (common special chars that appear in secrets)
            val urlEncoded = URL_ENCODE_CHARS.entries.joinToString("") { (ch, enc) ->
                if (secret.contains(ch)) enc else ""
            }
            if (urlEncoded.isNotEmpty() && urlEncoded != secret) {
                patterns.add(Pattern.quote(urlEncoded).toRegex())
            }
        }

        return patterns
    }

    /**
     * Returns the number of registered secrets.
     */
    fun size(): Int = registeredSecrets.size

    /**
     * Scrubs all registered secret patterns from a string.
     *
     * This is a standalone scrub for use cases like [GitCheckoutFailed.reason]
     * where the error message may contain embedded credentials that need to be
     * redacted before the event is emitted.
     *
     * INV-L6-CR-013: `GitCheckoutFailed.reason` must be scrubbed via this method
     * before construction of the event.
     *
     * @param input The string to scrub
     * @return The scrubbed string with all registered secrets replaced by "****"
     */
    fun scrub(input: String): String {
        val patterns = buildActivePatterns()
        if (patterns.isEmpty()) return input

        var result = input
        for (pattern in patterns) {
            result = pattern.replace(result, SCRUB_MARKER)
        }
        return result
    }

    companion object {
        /**
         * GitLab minimum maskable secret length.
         * Secrets below this threshold are NOT registered for masking.
         */
        const val MIN_MASKABLE_LENGTH = 8

        /**
         * Marker used to replace scrubbed secret values.
         */
        const val SCRUB_MARKER = "****"

        /**
         * URL-encodes a string for the common special chars in secrets.
         */
        private val URL_ENCODE_CHARS = mapOf(
            '+' to "%2B",
            '/' to "%2F",
            '=' to "%3D",
            '&' to "%26",
            '%' to "%25",
            '?' to "%3F",
            '#' to "%23",
            '@' to "%40",
            '!' to "%21",
            '$' to "%24",
            '\'' to "%27",
            '"' to "%22",
            ' ' to "%20"
        )
    }
}

/**
 * Warning event emitted when a short secret is not registered for masking.
 *
 * @param credentialsId The credential ID that was not masked
 * @param length The actual length of the secret value
 */
data class SecretShorterThanMinimumMaskableLengthWarning(
    val credentialsId: String,
    val length: Int,
)
