package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Sandbox configuration record — carries profile + escape-hatch sets.
 *
 * Encapsulates the active [SandboxProfile] plus the allowExtra and pathKeep
 * sets populated from sysprop / controller-JVM env var resolution.
 *
 * Use [SandboxConfig.NONE] for no sandboxing (full ML-R2 back-compat).
 * Use [SandboxConfig.LOCAL] for local sandbox (cwd = workspace, env deny-list, PATH normalise).
 *
 * @property profile The active sandbox profile.
 * @property allowExtra Set of env-key names to retain even if deny-listed
 *                      (e.g., `JAVA_TOOL_OPTIONS`). Populated via
 *                      `PIPELINE_SANDBOX_ALLOW_EXTRA` env var or
 *                      `pipeline.sandbox.allow.extra` sysprop.
 * @property pathKeep Set of PATH prefix paths to retain after normalisation.
 *                     Populated via `PIPELINE_SANDBOX_PATH_KEEP` env var or
 *                     `pipeline.sandbox.path.keep` sysprop.
 *
 * @see SandboxProfile
 * @see SandboxConfigResolver
 * @see <a href="ADR-0048">ADR-0048 — Sandbox Profile Local</a>
 */
data class SandboxConfig(
    val profile: SandboxProfile,
    val allowExtra: Set<String> = emptySet(),
    val pathKeep: Set<String> = emptySet(),
) {
    companion object {
        /** Back-compat sentinel: no sandboxing, no allowExtra, no pathKeep. */
        val NONE: SandboxConfig = SandboxConfig(SandboxProfile.NONE)

        /** Local sandbox defaults: LOCAL profile, empty allowExtra, empty pathKeep. */
        val LOCAL: SandboxConfig = SandboxConfig(SandboxProfile.LOCAL)
    }
}

/**
 * Resolves [SandboxConfig] from sysprop / controller-JVM environment variables.
 *
 * Resolution order:
 * 1. Sysprop first: `pipeline.sandbox.allow.extra`, `pipeline.sandbox.path.keep`
 * 2. Then env var: `PIPELINE_SANDBOX_ALLOW_EXTRA`, `PIPELINE_SANDBOX_PATH_KEEP`
 *    (controller JVM env, NOT child process env — children cannot escape to themselves)
 *
 * Each comma-separated entry is trimmed. Empty entries are ignored.
 *
 * @see SandboxConfig
 */
object SandboxConfigResolver {
    private const val SYS_PROP_ALLOW_EXTRA = "pipeline.sandbox.allow.extra"
    private const val SYS_PROP_PATH_KEEP = "pipeline.sandbox.path.keep"
    private const val ENV_ALLOW_EXTRA = "PIPELINE_SANDBOX_ALLOW_EXTRA"
    private const val ENV_PATH_KEEP = "PIPELINE_SANDBOX_PATH_KEEP"

    /**
     * Resolves the full [SandboxConfig] from sysprops / controller JVM env vars.
     *
     * @param syspropAllowExtra Raw sysprop value for allowExtra, or null.
     * @param syspropPathKeep Raw sysprop value for pathKeep, or null.
     * @param envAllowExtra Raw env var value for allowExtra, or null.
     * @param envPathKeep Raw env var value for pathKeep, or null.
     * @param baseProfile The [SandboxProfile] to use (from CLI / default).
     * @return The resolved [SandboxConfig].
     */
    fun resolve(
        syspropAllowExtra: String?,
        syspropPathKeep: String?,
        envAllowExtra: String?,
        envPathKeep: String?,
        baseProfile: SandboxProfile,
    ): SandboxConfig {
        val allowExtra = resolveSet(syspropAllowExtra ?: envAllowExtra)
        val pathKeep = resolveSet(syspropPathKeep ?: envPathKeep)
        return SandboxConfig(baseProfile, allowExtra, pathKeep)
    }

    /**
     * Convenience: resolves using the controller JVM environment variables only.
     *
     * @param baseProfile The [SandboxProfile] to use.
     * @return The resolved [SandboxConfig].
     */
    fun resolve(baseProfile: SandboxProfile): SandboxConfig {
        return resolve(
            syspropAllowExtra = System.getProperty(SYS_PROP_ALLOW_EXTRA),
            syspropPathKeep = System.getProperty(SYS_PROP_PATH_KEEP),
            envAllowExtra = System.getenv(ENV_ALLOW_EXTRA),
            envPathKeep = System.getenv(ENV_PATH_KEEP),
            baseProfile = baseProfile,
        )
    }

    private fun resolveSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
