package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Sandbox profile for shell execution — controls cwd, environment, and PATH policy.
 * Four values are defined:
 * - [NONE]: Default behaviour. No sandboxing applied. Cwd = control dir (legacy).
 * Env passthrough unchanged. PATH unchanged. Full back-compat
 * - [LOCAL]: Cwd = per-stage workspace. Env deny-list applied to pb.environment.
 *   PATH normalised to keep-set prefixes + JAVA_HOME/bin +_HOME/bin prepends.
 *   Local profile is NOT a filesystem jail — JDK has no portable chroot.
 * OS-level sandbox remains/ (see [])
 * [OS]: Recognised but not instantiable in. The factory constructor throws
 *   [SandboxProfileUnsupportedException] citing/.
 * ## Jenkins-wrapper mapping
 * | Profile | Jenkins equivalent | Notes |
 * |---------|-------------------|-------|
 * | NONE    | Default sh        | cwd = control dir, no env filter |
 * | LOCAL   | `withEnv` block  | cwd = workspace, env deny-list, PATH normalise |
 * | CONFINED | Container profile | the run is put inside a boundary (namespaces, read-only rootfs, no egress) |
 * | OS | Container profile | Not available in |
 * @see SandboxConfig
 * Durable sh Pattern</a>
 * Sandbox Profile Local</a>
 * Scope Firewall</a>
*/
enum class SandboxProfile {
    /** No sandboxing. Cwd = control dir; env passthrough; PATH unchanged. Full back-compat */
    NONE,

    /**
     * Local sandbox. Cwd = per-stage workspace.
     * Env deny-list applied before user putAll (invariant)
     * PATH normalised to keep-set + JAVA_HOME/bin +_HOME/bin prepends.
     * NOTE: local profile is NOT a filesystem jail. JDK has no portable chroot.
     * Write attempts outside workspace are reported but not blocked.
     * OS-level sandbox requires/ (see [])
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
     * OS-level sandbox profile. Recognised but NOT instantiable in
     * Instantiation throws [SandboxProfileUnsupportedException] citing/
     * OS-level sandbox (container, seccomp, unshare) is deferred to/
*/
    OS;

    /**
     * Factory for [OS] profile. Throws because OS profile is not available in
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
 * - the [SandboxProfile.OS] factory is called directly (not available yet)
 * The message names the offending value and the set of accepted profiles, so a
 * caller can tell a refused profile from an unknown one without parsing prose.
 * @param message The error message.
*/
class SandboxProfileUnsupportedException(message: String) : RuntimeException(message)
