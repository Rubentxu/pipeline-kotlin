package dev.rubentxu.pipeline.v2.application

/**
 * S2-A5 / G2 — THE single canonical pure classifier for `core.isUnix` (decision D1/D2,
 * receipt `S2_A5_CORE_ISUNIX_G2_CANONICAL_DIFFERENTIAL_FREEZE.md`).
 *
 * Canonical contract "C2" = intent(PATH_A) + JVM-real alias from PATH_B ("mac os x")
 * - PATH_A's fake empty-string placeholder - PATH_B's substring heuristics.
 *
 * Normalization is `trim().lowercase()`; membership is EXACT on the frozen set below.
 * Extension (Solaris, UnixWare, ...) requires a real case, not speculation.
 *
 * This function is the ONLY classification policy authority:
 * - `CoreIsUnixStep` classifies the execution target's [PlatformIdentity.osName] with it;
 * - the future DSL compatibility path (PATH_A reconnection) MUST reuse the same function
 *   so the classification split disappears (the observation-TIME split is a separate,
 *   still-open problem: D3 / DSL_RUNTIME_RETURN_GAP).
 */
object UnixPlatformClassifier {

    /** Frozen canonical Unix-like os.name set (normalized form). */
    private val UNIX_LIKE = setOf(
        "linux",
        "macos",
        "mac os x", // real JVM alias observed by PATH_B; not a substring heuristic
        "darwin",
        "sunos",
        "aix",
        "hp-ux",
        "freebsd",
        "openbsd",
        "netbsd",
    )

    /** Total, pure: every input maps to a Boolean; no exception is a legitimate outcome. */
    fun classifyUnix(osName: String): Boolean =
        osName.trim().lowercase() in UNIX_LIKE
}
