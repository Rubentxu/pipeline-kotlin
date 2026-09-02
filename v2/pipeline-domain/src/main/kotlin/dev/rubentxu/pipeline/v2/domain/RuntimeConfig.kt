package dev.rubentxu.pipeline.v2.domain

/**
 * Platform-agnostic port for runtime configuration. Replaces the historical
 * direct reads of `System.getenv` / `System.getProperty` scattered across the
 * runtime (8 sites in `:pipeline-application`, plus the 19-call composition-root
 * `DurableShConfig.fromSystemProperties()`).
 *
 * The contract is intentionally **read-only** and **stateless** from the
 * caller's perspective: a `RuntimeConfig` is a snapshot of the environment
 * at the moment it was constructed, plus the ability to look up values by
 * name. Implementations are free to read lazily or eagerly, as long as the
 * observed behaviour matches the documented semantics.
 *
 * The contract is **mandatory for every site that needs the OS environment
 * or JVM system properties**. Per
 * `docs/v2/03-specifications/CANONICAL_CONTRACTS_SPEC.md` §Platform services:
 *
 * > "Runtime recibe `Clock`, `IdGenerator`, `RuntimeConfig`. `Instant.now`,
 * >  `UUID.randomUUID`, `System.getenv` y `System.getProperty` quedan en
 * >  adapters de plataforma."
 *
 * Two adapters ship with the platform:
 * - `MapRuntimeConfig` (in domain) — test-friendly, deterministic, immutable
 *   over a frozen map of values. Useful for UAT, characterization fixtures,
 *   and any site that needs reproducible behaviour.
 * - `SystemRuntimeConfig` (in application) — the production adapter that
 *   reads from `System.getenv` / `System.getProperty`. The only site allowed
 *   to call those methods directly.
 *
 * Migration of the 19 production call sites of
 * `DurableShConfig.fromSystemProperties()` and the 8 direct `System.getenv`
 * / `System.getProperty` sites is M2 work (LF-0205 redirect CLI / LF-0307
 * migrate sh). The fitness test does NOT pin "no direct System.getenv /
 * System.getProperty in application" yet — that pin lands with the
 * migration so each replacement has a clean regression net.
 *
 * @see SystemRuntimeConfig
 * @see MapRuntimeConfig
 */
interface RuntimeConfig {

    /**
     * Returns the value of the OS environment variable named [name], or
     * `null` if it is not defined.
     *
     * Semantics match `System.getenv(String)`: a missing variable returns
     * `null`, not an empty string.
     *
     * @param name environment variable name (case-sensitive on Unix-like
     *             systems; case-insensitive on Windows — but the contract is
     *             the OS's contract, not ours).
     */
    fun env(name: String): String?

    /**
     * Returns the value of the JVM system property named [name], or `null`
     * if it is not defined.
     *
     * Semantics match `System.getProperty(String)`.
     */
    fun property(name: String): String?

    /**
     * Returns the value of the JVM system property named [name], or
     * [default] if it is not defined.
     *
     * Semantics match `System.getProperty(String, String)`.
     */
    fun property(name: String, default: String): String

    /**
     * Returns the operating system name as reported by the JVM
     * (`System.getProperty("os.name")`).
     *
     * Exposed as a separate method because most consumers only need the OS
     * family and would otherwise re-implement the `lowercase().contains(...)`
     * dance that already lives in `PipelineRun.kt`.
     */
    fun osName(): String
}
