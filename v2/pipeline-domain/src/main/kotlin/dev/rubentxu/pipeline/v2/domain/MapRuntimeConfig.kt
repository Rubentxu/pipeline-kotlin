package dev.rubentxu.pipeline.v2.domain

/**
 * Deterministic, immutable [RuntimeConfig] backed by two frozen maps: one
 * for OS environment variables and one for JVM system properties.
 *
 * This is the test-friendly adapter: UAT, characterization fixtures and any
 * site that needs reproducible behaviour construct one of these and pass it
 * through the runtime. No I/O happens at lookup time; the maps are read at
 * construction and then frozen.
 *
 * Both maps are defensively copied on construction so that subsequent
 * mutation of the source maps by the caller does not affect this config.
 *
 * @property env frozen map of OS environment variables (case-sensitive keys).
 * @property properties frozen map of JVM system properties.
 */
class MapRuntimeConfig(
    env: Map<String, String>,
    properties: Map<String, String>,
) : RuntimeConfig {

    private val envView: Map<String, String> = env.toMap()
    private val propertiesView: Map<String, String> = properties.toMap()

    override fun env(name: String): String? = envView[name]

    override fun property(name: String): String? = propertiesView[name]

    override fun property(name: String, default: String): String = propertiesView[name] ?: default

    override fun osName(): String = propertiesView["os.name"] ?: ""

    /** Returns an empty [MapRuntimeConfig] for tests that need a no-op config. */
    companion object {
        fun empty(): MapRuntimeConfig = MapRuntimeConfig(emptyMap(), emptyMap())
    }
}
