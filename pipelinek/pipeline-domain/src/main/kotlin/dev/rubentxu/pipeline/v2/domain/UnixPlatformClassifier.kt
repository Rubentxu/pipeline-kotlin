package dev.rubentxu.pipeline.v2.domain

/**
 * The ONE classification of a platform name: is this a Unix-like platform?
 *
 * ## Why this function lives here
 *
 * Two callers need the answer and they must not disagree: the step `core.isUnix`,
 * which observes the platform when it runs, and the eager DSL, which reads the
 * platform while the plan is built. A second copy of the policy is a second
 * authority, and the two answers would differ on every name the policy does not
 * spell out.
 *
 * So the policy lives in the DOMAIN, next to the port that observes the
 * platform ([RuntimeConfig.osName]), and both callers use this function. The
 * eager DSL reads the platform through the port and classifies it here, so the
 * value it hands to a script is the value the runtime would observe.
 *
 * ## The contract
 *
 * Normalization is `trim().lowercase()` and membership is EXACT on the frozen
 * set below. Exactness is the point: a name that merely CONTAINS a Unix-like
 * word (`linux-gnu`, `darwin-next`) is not a name of this set, and a policy
 * that answered by substring would claim a platform nobody runs on.
 *
 * The function is TOTAL: every input maps to a Boolean and no input is an
 * exception.
 *
 * The empty name is a platform nobody declared, and it is not Unix-like. A
 * port with no platform to report answers the empty name, so a script built
 * without a port behind it takes the branch that is not the Unix one.
 */
object UnixPlatformClassifier {

    /**
     * The frozen set of Unix-like platform names, in normalized form.
     *
     * `mac os x` is an alias the JVM really reports on macOS, not a
     * heuristic: it is in the set because the machine answers that name.
     */
    private val UNIX_LIKE = setOf(
        "linux",
        "macos",
        "mac os x",
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
