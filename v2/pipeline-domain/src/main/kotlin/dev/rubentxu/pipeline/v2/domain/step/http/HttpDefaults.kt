package dev.rubentxu.pipeline.v2.domain.step.http

/**
 * Declared defaults of `httpRequest`, shared by the DSL surface and the runtime
 * contract (RP6-C / WU-093).
 *
 * These live in `domain` because BOTH sides must state the same number: the DSL
 * author writing `httpRequest(url)` and the handler that enforces it. Two
 * constants would be two truths, and the one that drifts is the one an author
 * cannot see.
 */
object HttpDefaults {
    /**
     * The default bound on a request, in seconds.
     *
     * Jenkins defaults `timeout` to `0`, meaning "no timeout", because its client
     * would otherwise impose its own 5-minute default. Inheriting that here would
     * mean a forgotten timeout hangs the run forever, and the common case — a
     * responsive API — would look like a broken runner. Thirty seconds is a
     * declared value, not an inherited one, and an author who really wants no
     * bound says `timeoutSeconds = 0`, which survives as `null`.
     */
    const val DEFAULT_TIMEOUT_SECONDS: Int = 30
}
