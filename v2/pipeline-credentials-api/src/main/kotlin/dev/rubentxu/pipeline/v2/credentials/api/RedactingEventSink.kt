package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFailed

/**
 * EventSink decorator that scrubs secrets from free-text event fields
 * before appending to the underlying sink.
 *
 * ## Redaction Layers
 *
 * Two layers of redaction are applied (per design §Data Flow):
 * 1. **Literal + encoding pattern scan**: [SecretPatternRegistry] provides
 *    multi-form patterns (literal, base64, hex, URL-encoded) that scrub
 *    secret values from free-text fields.
 * 2. **Credential ID substitution**: `$${credentialsId.X}` placeholders are
 *    replaced with `[REDACTED:CREDENTIALS:…]` before the event reaches
 *    the delegate sink.
 *
 * Both layers run BEFORE [delegate.append] is called — the delegate
 * receives already-sanitized events.
 *
 * ## Free-text Surfaces Covered
 *
 * - [EchoOutputCaptured.content]
 * - [StepFailed.message]
 * - [CompilationFinished.diagnostics][*].message
 * - [RunFinished.diagnostics][*].message
 * - [GitCheckoutFailed.reason] — INV-L6-CR-013 (scrubbed to prevent credential leakage)
 *
 * ## Performance
 *
 * - Naive [Pattern.quote] alternation for ≤ 20 active secrets
 * - Aho-Corasick single-pass for > 20 secrets
 *
 * @param delegate The underlying [EventSink] to write sanitized events to
 * @param registry The [SecretPatternRegistry] containing active secret patterns
 */
class RedactingEventSink(
    private val delegate: EventSink,
    private val registry: SecretPatternRegistry,
) : EventSink {

    /**
     * Appends an event after sanitizing all free-text surfaces.
     *
     * Two layers are applied before the delegate receives the event:
     * 1. Secret pattern scrub (literal + multi-encoding)
     * 2. Credential ID substitution (`$${credentialsId.X}` → redaction marker)
     */
    override fun append(event: DomainEvent) {
        val sanitized = sanitize(event)
        delegate.append(sanitized)
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> {
        return delegate.eventsFor(runId)
    }

    /**
     * Sanitizes an event by scrubbing secrets from all free-text surfaces.
     * Returns a (possibly new) event with sanitized content.
     */
    private fun sanitize(event: DomainEvent): DomainEvent {
        val patterns = registry.buildActivePatterns()

        return when (event) {
            is EchoOutputCaptured -> event.copy(
                content = scrubString(event.content, patterns)
            )
            is StepFailed -> event.copy(
                message = scrubString(event.message, patterns)
            )
            is CompilationFinished -> event.copy(
                diagnostics = event.diagnostics.map { diag ->
                    diag.copy(message = scrubString(diag.message, patterns))
                }
            )
            is RunFinished -> event.copy(
                diagnostics = event.diagnostics.map { diag ->
                    diag.copy(message = scrubString(diag.message, patterns))
                }
            )
            // INV-L6-CR-013: GitCheckoutFailed.reason may contain embedded credentials in URLs.
            // Scrub the reason field to prevent credential leakage in CI logs.
            is GitCheckoutFailed -> event.copy(
                reason = scrubString(event.reason, patterns)
            )
            // Other event types pass through unchanged
            else -> event
        }
    }

    /**
     * Scrubs all secret patterns from a string.
     * Also handles credential ID substitution placeholders.
     */
    private fun scrubString(input: String, patterns: List<Regex>): String {
        var result = input

        // Layer 1: scrub secret patterns (literal + encodings)
        for (pattern in patterns) {
            result = pattern.replace(result, SCRUB_MARKER)
        }

        // Layer 2: credential ID substitution
        result = applyCredentialSubstitution(result)

        return result
    }

    /**
     * Applies credential ID substitution: `$${credentialsId.X}` → `[REDACTED:CREDENTIALS:…]`
     *
     * The substitution pattern matches `$${` followed by a credentials ID reference
     * and replaces the entire placeholder with a redaction marker.
     */
    private fun applyCredentialSubstitution(input: String): String {
        return CREDENTIAL_SUBSTITUTION_PATTERN.replace(input) {
            "[${REDACTED_PREFIX}${it.groupValues[1]}]"
        }
    }

    companion object {
        /**
         * Marker used to replace scrubbed secret values.
         */
        const val SCRUB_MARKER = "****"

        /**
         * Prefix for credential ID redaction markers.
         */
        const val REDACTED_PREFIX = "REDACTED:CREDENTIALS:"

        /**
         * Pattern matching credential substitution placeholders: `${credentialsId.X}`
         * The double `$$` escapes the `$` in the regex.
         */
        private val CREDENTIAL_SUBSTITUTION_PATTERN = Regex("""\$\$\{([^}]+)\}""")
    }
}
