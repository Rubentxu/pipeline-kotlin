package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.events.HtmlReportFailed
import dev.rubentxu.pipeline.v2.events.HtmlReportSkipped
import dev.rubentxu.pipeline.v2.events.HttpRequestFailed
import dev.rubentxu.pipeline.v2.events.HttpRequestStarted
import dev.rubentxu.pipeline.v2.events.HttpResponseReceived
import dev.rubentxu.pipeline.v2.events.HttpStatusRejected
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputDenied
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import dev.rubentxu.pipeline.v2.events.LockAcquireFailed
import dev.rubentxu.pipeline.v2.events.LockRequested
import dev.rubentxu.pipeline.v2.events.LockSkipped
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StashFailed
import dev.rubentxu.pipeline.v2.events.StepFailed

/**
 * An [EventSink] that scrubs registered secrets from the free text of every
 * event, before the event reaches the sink that keeps it.
 *
 * ## What "free text" means here
 *
 * A field carries free text when it can hold a sentence that a human or a
 * process wrote: the content of an echo, the message of a failure, the reason
 * a stage was skipped, the URL of a remote. The record of a run has exactly
 * four such field names — `content`, `message`, `reason` and `url` — plus the
 * diagnostics of a compilation and of a run, whose entries carry a `message`.
 * Every event kind that has one of them is listed below, and the list IS the
 * rule applied: a kind missing from it is a kind whose secret walks into the
 * console and into the record of the run.
 *
 * ## Why the list is over the taxonomy and not over today's emitters
 *
 * Some of the kinds listed here have no emitter yet. They are listed anyway,
 * because "nobody emits it yet" is a fact about this week, and the moment
 * somebody writes the emitter the secret walks. The list belongs to the
 * taxonomy, not to the roadmap.
 *
 * ## What this sink does NOT touch
 *
 * It scrubs the OBSERVABLE channel: the console and the event record of the
 * run. It does not touch the typed value a step returns to the pipeline — a
 * step asked to capture a stdout gets it exact, and the pipeline is handed
 * that one. The two channels are separate on purpose, and this class is on the
 * observable side of that line.
 *
 * ## Redaction Layers
 * Two layers of redaction are applied:
 * 1. **Literal + encoding pattern scan**: [SecretPatternRegistry] provides
 *    multi-form patterns (literal, base64, hex, URL-encoded) that scrub
 *    secret values from free-text fields.
 * 2. **Credential ID substitution**: `$${credentialsId.X}` placeholders are
 *    replaced with `[REDACTED:CREDENTIALS:…]` before the event reaches
 *    the delegate sink.
 * Both layers run BEFORE [delegate.append] is called — the delegate
 * receives already-sanitized events.
 *
 * ## Performance
 * - Naive [Pattern.quote] alternation for ≤ 20 active secrets
 * - Aho-Corasick single-pass for > 20 secrets
 * - One scrub per free-text field the event has, so a wider list does not
 *   make an event more expensive to write
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
        val withContent = scrubContent(event, patterns)
        val withMessages = scrubMessages(withContent, patterns)
        val withReasons = scrubReasons(withMessages, patterns)
        val withUrls = scrubUrls(withReasons, patterns)
        return scrubDiagnostics(withUrls, patterns)
    }

    /** The `content` surface: what a step printed. */
    private fun scrubContent(event: DomainEvent, patterns: List<Regex>): DomainEvent =
        when (event) {
            is EchoOutputCaptured -> event.copy(content = scrub(event.content, patterns))
            else -> event
        }

    /** The `message` surface: why a step failed, and what a person was asked. */
    private fun scrubMessages(event: DomainEvent, patterns: List<Regex>): DomainEvent =
        when (event) {
            is StepFailed -> event.copy(message = scrub(event.message, patterns))
            is CatchErrorTriggered -> event.copy(message = event.message?.let { scrub(it, patterns) })
            is StageMarkedUnstable -> event.copy(message = scrub(event.message, patterns))
            is InputRequested -> event.copy(message = scrub(event.message, patterns))
            is InputProceed -> event.copy(message = event.message?.let { scrub(it, patterns) })
            is InputAborted -> event.copy(message = event.message?.let { scrub(it, patterns) })
            else -> event
        }

    /** The `reason` surface: why something was skipped, denied or could not be done. */
    private fun scrubReasons(event: DomainEvent, patterns: List<Regex>): DomainEvent =
        when (event) {
            is StageSkipped -> event.copy(reason = scrub(event.reason, patterns))
            is ArtifactArchiveFailed -> event.copy(reason = scrub(event.reason, patterns))
            is MilestoneAborted -> event.copy(reason = scrub(event.reason, patterns))
            is StashFailed -> event.copy(reason = scrub(event.reason, patterns))
            is HtmlReportSkipped -> event.copy(reason = scrub(event.reason, patterns))
            is HtmlReportFailed -> event.copy(reason = scrub(event.reason, patterns))
            is DirectiveDenied -> event.copy(reason = scrub(event.reason, patterns))
            is GateEvaluated -> event.copy(reason = scrub(event.reason, patterns))
            is LockRequested -> event.copy(reason = event.reason?.let { scrub(it, patterns) })
            is LockSkipped -> event.copy(reason = scrub(event.reason, patterns))
            is LockAcquireFailed -> event.copy(reason = scrub(event.reason, patterns))
            is InputDenied -> event.copy(reason = scrub(event.reason, patterns))
            is SagaTerminal -> event.copy(reason = event.reason?.let { scrub(it, patterns) })
            is GitCheckoutFailed -> event.copy(reason = scrub(event.reason, patterns))
            is HttpRequestFailed -> event.copy(reason = scrub(event.reason, patterns))
            else -> event
        }

    /** The `url` surface: where a remote was, which can carry a credential of its own. */
    private fun scrubUrls(event: DomainEvent, patterns: List<Regex>): DomainEvent =
        when (event) {
            is GitCheckoutStarted -> event.copy(url = scrub(event.url, patterns))
            is GitCheckoutCompleted -> event.copy(url = scrub(event.url, patterns))
            is GitCheckoutFailed -> event.copy(url = scrub(event.url, patterns))
            is GitPollChanged -> event.copy(url = scrub(event.url, patterns))
            is HttpRequestStarted -> event.copy(url = scrub(event.url, patterns))
            is HttpResponseReceived -> event.copy(url = scrub(event.url, patterns))
            is HttpStatusRejected -> event.copy(url = scrub(event.url, patterns))
            is HttpRequestFailed -> event.copy(url = scrub(event.url, patterns))
            else -> event
        }

    /** The diagnostics of a compilation and of a run, whose entries carry a `message`. */
    private fun scrubDiagnostics(event: DomainEvent, patterns: List<Regex>): DomainEvent =
        when (event) {
            is CompilationFinished -> event.copy(
                diagnostics = event.diagnostics.map { diag ->
                    diag.copy(message = scrub(diag.message, patterns))
                }
            )
            is RunFinished -> event.copy(
                diagnostics = event.diagnostics.map { diag ->
                    diag.copy(message = scrub(diag.message, patterns))
                }
            )
            else -> event
        }

    /**
     * Scrubs all secret patterns from a string.
     * Also handles credential ID substitution placeholders.
*/
    private fun scrub(input: String, patterns: List<Regex>): String {
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
