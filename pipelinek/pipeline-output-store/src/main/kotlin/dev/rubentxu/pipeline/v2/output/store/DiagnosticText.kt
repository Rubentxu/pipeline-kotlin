package dev.rubentxu.pipeline.v2.output.store

/**
 * Bounded, single-line rendering of a [Throwable] for a refusal's diagnostic field.
 * Every refusal in this module carries free text that a consumer prints on one line. That text
 * originates in exception messages, which are not ours to bound: an `IOException` from a path
 * carrying a newline embeds it verbatim, and on Linux such a path is legal, because Output Plane
 * stream ids reach the CLI as arguments.
 * This is the single authority for that narrowing on the output side. It previously existed as a
 * private `shortCause` copy in [SegmentOutputFollowHandle], and -- not at all -- at
 * `MainInspectCli`, whose refusal was handed a raw `e.message` and rendered as `<unreadable: ...>`
 * on one console line. Two sites, one rule, and one of them had forgotten it.
 * It lives here, not in `:pipeline-output`, and that placement is deliberate rather than incidental.
 * `:pipeline-output` is the published Output SDK and carries a binary-compatibility gate; a public
 * text helper is not a change that SDK's surface should absorb for an internal caller. This module
 * applies `maven-publish` but declares no publication and the SDK BOM excludes it by name, so it
 * has no such surface. Both live consumers already see this module.
 * `:pipeline-events-store` keeps a third copy, for the same reason this one is here plus one more:
 * `:pipeline-output` deliberately depends on neither `:pipeline-events` nor `:pipeline-domain`,
 * Because the output plane's independence from the event plane is the point of
 * The narrowing is deliberately two operations in a fixed order: truncate to [MAX_LEN], then replace
 * newlines. Truncating last could re-expose a newline that the replace had removed, and a truncation
 * that lands *on* a newline rather than past one would emit a bare carriage return.
*/
object DiagnosticText {

    /** Maximum characters retained from the throwable's message. */
    const val MAX_LEN: Int = 120

    /**
     * Render [throwable] as `"<SimpleName>: <message>"`, or just `"<SimpleName>"` when it has no
     * message or a blank one.
     * Falls back to the binary name when the simple name is null, which happens for anonymous
     * subclasses. A blank message yields the class alone rather than `"Class: "`, so the caller never
     * renders a dangling separator.
*/
    fun from(throwable: Throwable): String {
        val cls = throwable::class.simpleName ?: throwable.javaClass.name
        // Both CR and LF. Replacing only '\n' was the rule as it was inherited: a lone CR returns
        // the terminal cursor to column 0, so the next write OVERWRITES a line this renderer
        // already emitted. On a consumer that does not fold CR into CRLF, that forges a record just
        // as effectively as a newline, and the test for it failed before this replacement existed.
        val msg = throwable.message?.take(MAX_LEN)?.replace('\r', ' ')?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
