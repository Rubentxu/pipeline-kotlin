package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * The declared trust profile of a local runner.
 *
 * ## What it decides
 * A runner either hosts the pipeline of the same party that authored it, or
 * it hosts third-party scripts on a shared host. The two have different
 * answers, and this profile is the declaration of which one a runner is: each
 * case carries ONLY the sandbox guarantees that the case can actually make.
 *
 * ## Why a profile that cannot be made is UNREPRESENTABLE
 * A profile whose guarantees depend on a capability this build does not have
 * is not a case that is constructed and refused later: it is not a case at
 * all. [MultiTenantConstrained] cannot be constructed until the filesystem
 * jail, the CPU/memory limits and the egress blocking exist, and asking for it
 * fails closed, naming what is missing and what exists instead.
 *
 * ## What each profile guarantees today
 * [TrustedSingleTenant] — the runner host and the pipeline author are the same
 * party. Guaranteed: process containment (process-tree kill, cookie-scan
 * watchdog, FAILED_TIMEOUT determinism), time containment (deadline budgets
 * projected onto childShOptions.timeoutMs), secret redaction (typed
 * SecretHandle channel + chunk-boundary-safe transcript redaction, console and
 * at-rest). Sandbox profiles allowed: [SandboxProfile.NONE], [SandboxProfile.LOCAL],
 * [SandboxProfile.CONFINED].
 * [MultiTenantConstrained] — third-party scripts on a shared host. Requires
 * filesystem jail, CPU/memory limits and egress blocking. NONE of these exist
 * here; therefore this profile is not constructible and no run may claim it.
 *
 * @see SandboxProfile
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
            setOf(SandboxProfile.NONE, SandboxProfile.LOCAL, SandboxProfile.CONFINED)
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
