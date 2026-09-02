package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RuntimeConfig

/**
 * Production [RuntimeConfig] that reads from `System.getenv` /
 * `System.getProperty`.
 *
 * This is the **only** class in the codebase allowed to call
 * `System.getenv` / `System.getProperty` directly. The fitness test
 * `FArchM1CanonicalRuntimeConfigTest` enforces that no other production
 * site invokes those methods.
 *
 * Lookups are lazy and stateless: every call re-reads the JVM system
 * property or OS environment variable at call time. That is intentional —
 * the runtime may run for minutes, and configuration can change mid-flight
 * (e.g. tests that mutate `System.setProperty` between steps).
 *
 * @see RuntimeConfig
 * @see MapRuntimeConfig for the test-friendly deterministic alternative.
 */
class SystemRuntimeConfig : RuntimeConfig {
    override fun env(name: String): String? = System.getenv(name)

    override fun property(name: String): String? = System.getProperty(name)

    override fun property(name: String, default: String): String = System.getProperty(name, default)

    override fun osName(): String = System.getProperty("os.name") ?: ""
}
