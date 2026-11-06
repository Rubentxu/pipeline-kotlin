package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * The sandbox profile of a shell step: what the step's process may see and
 * where it may write.
 *
 * ## A value is not a capability
 *
 * This enum is the CLOSED vocabulary of the profiles a caller may name. Naming
 * a value says what the caller ASKS for; it does not say what the host can DO.
 * The two are different facts with different answers — one is answered by a
 * different build, the other by a different host — and reading a value as the
 * promise of the behaviour is how a product ends up claiming a boundary it
 * does not apply.
 *
 * Each constant below says what the value asks for and what this build can do
 * with it.
 *
 * ## Where the truth about the host lives
 *
 * This file does not restate what a host can confine, because that is a fact
 * about the host and it is measured rather than declared:
 *
 * - the profiles this runtime may run under are the ones
 *   [RunnerTrustProfile.TrustedSingleTenant] admits, and the configuration
 *   refuses anything else ([SandboxConfig]);
 * - whether a boundary can be applied is decided by the admission core, which
 *   refuses with the capabilities the host could not prove
 *   ([dev.rubentxu.pipeline.v2.domain.isolation.DenialReason.UnsupportedCapability]).
 *
 * @see SandboxConfig
 * @see RunnerTrustProfile
 */
enum class SandboxProfile {
    /**
     * The legacy behaviour, and the default.
     *
     * It asks for nothing: the control directory as the working directory, the
     * environment unchanged and the PATH unchanged. This build does it, and
     * every run that says nothing about the profile runs under it.
     *
     * ## This is the TRUSTED posture, and it says so about the network
     *
     * Nothing here confines the run, so the process a shell step launches keeps
     * the HOST's network and can open a socket on it. The gate that denies
     * egress to the in-process plugins is a different mechanism and does not
     * reach that process: a `sh` step that runs `curl` reaches the network on a
     * default run, and that is the local-first behaviour this profile names.
     * An operator who wants the child process on a network of its own asks for
     * [CONFINED], and a host that cannot confine is told so before the run
     * starts.
     */
    NONE,

    /**
     * The stage's workspace as the working directory, the environment
     * deny-list and the normalised PATH.
     *
     * This build does it, and it confines NOTHING: the JDK has no portable
     * chroot, so a write outside the workspace is reported and not blocked.
     * The deny-list applies to the inherited environment, before the caller's
     * own variables are merged into it, and the PATH is normalised to the
     * keep-set plus JAVA_HOME/bin and M2_HOME/bin.
     *
     * It is the TRUSTED posture about the network for the same reason it is
     * about the filesystem: the child process a shell step launches keeps the
     * HOST's network, and the egress gate that denies the in-process plugins
     * does not reach it.
     */
    LOCAL,

    /**
     * Confined sandbox. The run is put INSIDE a boundary: its own namespaces, no
     * network, and a filesystem in which the workspace is the only place of the
     * operator's that can be written.
     *
     * Unlike [LOCAL], which confines nothing (it changes the working directory and
     * applies a deny-list to the environment), this profile applies a real
     * boundary, and it is applied by [SandboxConfinement].
     *
     * It is available where a provider can be found and can PROVE what it claims:
     * the admission core decides that, and its refusal names the capability that
     * is missing when this host cannot honour the request. The runtime admits the
     * profile because the posture declares it, and the admission is what decides
     * whether this host can run under it.
     */
    CONFINED,

    /**
     * An OS-level boundary for code the product does not trust with the
     * machine.
     *
     * The word is recognised and this build has NO runtime profile for it: the
     * request it states is refused by the admission core, which names the
     * capabilities a host would have to prove. Asking for it as a runtime
     * profile is refused as well: the companion factory throws
     * [SandboxProfileUnsupportedException], and [SandboxConfig] refuses the
     * value.
     */
    OS;

    /**
     * The factory for the OS profile, which cannot be built.
     *
     * @throws SandboxProfileUnsupportedException always
     */
    companion object {
        /**
         * @throws SandboxProfileUnsupportedException always
         */
        fun OS(): Nothing = throw SandboxProfileUnsupportedException(
            "sandbox-profile 'os' requires OS-level isolation, which is out of scope here. " +
                "Accepted: {none, local, confined}. Got: 'os'."
        )
    }
}

/**
 * Exception thrown when an unsupported sandbox profile is requested.
 * Used when:
 * - the CLI is passed `--sandbox-profile os` (which it rejects)
 * - the OS profile is asked for directly as a runtime profile (which it refuses)
 * The message names the offending value and the set of accepted profiles, so a
 * caller can tell a refused profile from an unknown one without parsing prose.
 * @param message The error message.
*/
class SandboxProfileUnsupportedException(message: String) : RuntimeException(message)
