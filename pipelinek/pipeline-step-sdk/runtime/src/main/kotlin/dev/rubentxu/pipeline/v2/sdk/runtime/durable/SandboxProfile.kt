package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Sandbox profile for shell execution — controls cwd, environment, and PATH policy.
 *
 * Three values are defined:
 * - [NONE]: Default behaviour. No sandboxing applied. Cwd = control dir (legacy).
 *   Env passthrough unchanged. PATH unchanged. Full ML-R2 back-compat.
 * - [LOCAL]: Cwd = per-stage workspace. Env deny-list applied to pb.environment().
 *   PATH normalised to keep-set prefixes + JAVA_HOME/bin + M2_HOME/bin prepends.
 *   Local profile is NOT a filesystem jail — JDK has no portable chroot.
 *   OS-level sandbox remains M5/M9 (see [ADR-0016]).
 * - [OS]: Recognised but not instantiable in L3. The factory constructor throws
 *   [SandboxProfileUnsupportedException] citing ADR-0016 M5/M9.
 *
 * ## Jenkins-wrapper mapping
 *
 * | Profile | Jenkins equivalent | Notes |
 * |---------|-------------------|-------|
 * | NONE    | Default sh        | cwd = control dir, no env filter |
 * | LOCAL   | `withEnv` block  | cwd = workspace, env deny-list, PATH normalise |
 * | OS      | Container profile  | Not available in L3 (M5/M9) |
 *
 * @see SandboxConfig
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="ADR-0048">ADR-0048 — Sandbox Profile Local</a>
 * @see <a href="ADR-0016">ADR-0016 — Scope Firewall</a>
 */
enum class SandboxProfile {
    /** No sandboxing. Cwd = control dir; env passthrough; PATH unchanged. Full ML-R2 back-compat. */
    NONE,

    /**
     * Local sandbox. Cwd = per-stage workspace.
     * Env deny-list applied before user putAll (P2 invariant).
     * PATH normalised to keep-set + JAVA_HOME/bin + M2_HOME/bin prepends.
     *
     * NOTE: local profile is NOT a filesystem jail. JDK has no portable chroot.
     * Write attempts outside workspace are reported but not blocked.
     * OS-level sandbox requires M5/M9 (see [ADR-0016]).
     */
    LOCAL,

    /**
     * OS-level sandbox profile. Recognised but NOT instantiable in L3.
     * Instantiation throws [SandboxProfileUnsupportedException] citing ADR-0016 M5/M9.
     * OS-level sandbox (container, seccomp, unshare) is deferred to M5/M9.
     */
    OS;

    /**
     * Factory for [OS] profile. Throws because OS profile is not available in L3.
     *
     * @throws SandboxProfileUnsupportedException always, citing ADR-0016 M5/M9
     */
    companion object {
        /**
         * @throws SandboxProfileUnsupportedException always
         */
        fun OS(): Nothing = throw SandboxProfileUnsupportedException(
            "sandbox-profile 'os' requires ADR-0016 M5/M9; rejected in L3. Accepted: {none, local}. Got: 'os'."
        )
    }
}

/**
 * Exception thrown when an unsupported sandbox profile is requested.
 *
 * Used when:
 * - CLI is passed `--sandbox-profile os` (L3 rejects os)
 * - [SandboxProfile.OS] factory is called directly (not available in L3)
 *
 * The message contains machine-checkable substrings:
 * - `ADR-0016` — scope firewall ADR
 * - `M5` — milestone 5
 * - `M9` — milestone 9
 *
 * @param message The error message.
 */
class SandboxProfileUnsupportedException(message: String) : RuntimeException(message)
