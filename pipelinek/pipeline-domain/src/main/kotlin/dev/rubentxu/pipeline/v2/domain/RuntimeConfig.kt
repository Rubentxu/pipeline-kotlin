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
 * or JVM system properties**:
 *
 * > "Runtime recibe `Clock`, `IdGenerator`, `RuntimeConfig`. `Instant.now`,
 * >  `UUID.randomUUID`, `System.getenv` y `System.getProperty` quedan en
 * >  adapters de plataforma."
 *
 * Two adapters ship with the platform:
 * - `MapRuntimeConfig` (in domain) — test-friendly, deterministic, immutable
 *   over a frozen map of values. Useful for the test matrix, characterization
 *   fixtures, and any site that needs reproducible behaviour.
 * - `SystemRuntimeConfig` (in the runtime module) — the production adapter that
 *   reads from `System.getenv` / `System.getProperty`. The only site allowed
 *   to call those methods directly.
 *
 * Migration of the 19 production call sites of
 * `DurableShConfig.fromSystemProperties()` and the 8 direct `System.getenv`
 * / `System.getProperty` sites is work (redirect CLI / migrate sh). The
 * fitness test does NOT pin "no direct System.getenv /
 * System.getProperty in application" yet — that pin lands with the
 * migration so each replacement has a clean regression net.
 *
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

    /**
     * Returns the current working directory of the process.
     *
     * No production site other than `SystemRuntimeConfig` should read the JVM
     * user-dir property directly; nothing enforces that today.
     *
     * The DSL does NOT read it, and that is a decision: the eager DSL used to
     * be wired to answer `pwd()` with this value, and it would have been a
     * path that looks real and is wrong. The directory of the process that
     * compiles the script is not the workspace of the stage — the workspace is
     * allocated when the run happens — so the answer of `pwd()` is the
     * sentinel that names the workspace, and the real path is the typed
     * output of the step that resolves it.
     */
    fun userDir(): String
}
