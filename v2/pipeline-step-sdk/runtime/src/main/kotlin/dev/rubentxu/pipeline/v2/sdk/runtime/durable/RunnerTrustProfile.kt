package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * WU-RP-041: declared trust profile of a local runner (roadmap RP-4 criterion:
 * "diferenciar ejecución confiable de multi-tenant").
 *
 * Closed ADT. Each case carries ONLY the sandbox guarantees that profile can
 * actually make; a profile whose guarantees depend on a BEST_EFFORT capability
 * is UNREPRESENTABLE: [MultiTenantConstrained] cannot be constructed until the
 * OS-level sandbox (ADR-0016 M5/M9) exists, and its constructor fails closed
 * citing the ADR, exactly like [SandboxProfile.OS].
 *
 * ## What each profile guarantees today (L3)
 *
 * [TrustedSingleTenant] — the runner host and the pipeline author are the same
 * party. Guaranteed: process containment (process-tree kill, cookie-scan
 * watchdog, FAILED_TIMEOUT determinism), time containment (deadline budgets
 * projected onto childShOptions.timeoutMs), secret redaction (typed
 * SecretHandle channel + chunk-boundary-safe transcript redaction, console and
 * at-rest). Sandbox profiles allowed: [SandboxProfile.NONE], [SandboxProfile.LOCAL].
 *
 * [MultiTenantConstrained] — third-party scripts on a shared host. Requires
 * filesystem jail, CPU/memory limits and egress blocking. NONE of these exist
 * in L3; therefore this profile is not constructible and no run may claim it.
 *
 * @see SandboxProfile
 * @see <a href="ADR-0016">ADR-0016 — Scope Firewall (M5/M9)</a>
 */
sealed interface RunnerTrustProfile {

    /** The sandbox profiles this trust profile may run under. */
    val allowedSandboxProfiles: Set<SandboxProfile>

    /**
     * Single-tenant trusted runner. The default; every existing invocation is
     * classified here unless a caller explicitly declares otherwise.
     */
    data object TrustedSingleTenant : RunnerTrustProfile {
        override val allowedSandboxProfiles: Set<SandboxProfile> =
            setOf(SandboxProfile.NONE, SandboxProfile.LOCAL)
    }

    /**
     * Multi-tenant constrained execution. NOT constructible here: a filesystem
     * jail, CPU/memory limits and egress blocking are not implemented.
     * Any attempt fails closed, naming what is missing and what exists instead.
     */
    companion object {
        /**
         * @throws SandboxProfileUnsupportedException always.
         */
        fun multiTenantConstrained(): Nothing = throw SandboxProfileUnsupportedException(
            "RunnerTrustProfile.multiTenantConstrained is not available: it requires " +
                "a filesystem jail, CPU/memory limits and egress blocking, none of which " +
                "exist here. No untrusted-execution profile exists today; capabilities " +
                "that are best-effort remain OUTSIDE the untrusted profile.",
        )
    }
}
