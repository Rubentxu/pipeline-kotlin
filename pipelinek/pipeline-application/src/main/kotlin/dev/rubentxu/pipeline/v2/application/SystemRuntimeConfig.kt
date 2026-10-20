package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RuntimeConfig

/**
 * Production [RuntimeConfig] that reads from `System.getenv` /
 * `System.getProperty`.
 * This class was MEANT to be the only one reading the host directly, and a
 * fitness test used to say so. Neither the gate nor the premise survives in
 * this tree: `FArchM1CanonicalRuntimeConfigTest` was deleted by the RESET, and
 * a census over tracked `src/main/kotlin` on found
 * `System.getenv` / `System.getProperty` calls in 36 files across 9 modules.
 * So this is the [RuntimeConfig] ADAPTER and not an enforcement point, and
 * nothing here stops another production class from bypassing the port. That is
 * spelled out rather than left implicit on purpose: a reader who assumes a
 * boundary the code does not draw will not notice the day one is crossed.
 * Lookups are lazy and stateless: every call re-reads the JVM system
 * property or OS environment variable at call time. That is intentional —
 * the runtime may run for minutes, and configuration can change mid-flight
 * (e.g. tests that mutate `System.setProperty` between steps).
 * @see RuntimeConfig
 * @see MapRuntimeConfig for the test-friendly deterministic alternative.
*/
class SystemRuntimeConfig : RuntimeConfig {
    override fun env(name: String): String? = System.getenv(name)

    override fun property(name: String): String? = System.getProperty(name)

    override fun property(name: String, default: String): String = System.getProperty(name, default)

    override fun osName(): String = System.getProperty("os.name") ?: ""

    override fun userDir(): String = System.getProperty("user.dir") ?: ""
}
