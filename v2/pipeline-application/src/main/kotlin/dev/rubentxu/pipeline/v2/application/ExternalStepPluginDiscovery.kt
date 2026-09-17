package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import java.util.ServiceLoader

/**
 * Runtime discovery adapter for external Step plugins (LB-02 / EP-4).
 *
 * The domain defines the [StepDefinitionContributor] SPI but never calls
 * [ServiceLoader] itself: discovery is a runtime-adapter concern. This adapter
 * is the ONLY place ServiceLoader is used for Steps.
 *
 * Admission order (single composition authority remains
 * [CoreStepRegistryFactory.registry]): core Steps register first; external
 * contributions register afterwards. [StepRegistry.register] fails closed on a
 * duplicate key, so an external plugin can never silently shadow a core Step.
 *
 * Fail-open-vs-closed: a contributor that throws during discovery aborts
 * composition (fail-closed) — a broken plugin JAR must not degrade into a
 * partial registry.
 */
object ExternalStepPluginDiscovery {

    /** Discovers contributors from the RUNTIME classpath and registers them into [registry]. */
    fun registerInto(registry: StepRegistry): List<String> {
        val registered = mutableListOf<String>()
        val loader = ServiceLoader.load(StepDefinitionContributor::class.java)
        val iterator = loader.iterator()
        while (iterator.hasNext()) {
            val contributor = try {
                iterator.next()
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "External Step plugin discovery failed (broken plugin JAR on the runtime classpath?): ${e.message}",
                    e,
                )
            }
            contributor.definitions().forEach(registry::register)
            registered.add(contributor.id)
        }
        return registered
    }

    /**
     * LFC-2E3-T4: collects [StepCapabilityContributor.capabilities] from every capability
     * contributor on the runtime classpath, failing closed on a duplicate key so a second plugin
     * can never silently shadow the first plugin's capability implementation.
     *
     * CLASSLOADER-SENSITIVE. Contributors live on the plugin classloader, which is NOT the
     * application classloader: the caller MUST invoke this inside the same
     * `Thread.currentThread().contextClassLoader = pluginClassLoader` window that
     * [registerInto] requires. Calling it outside that window silently returns an EMPTY map
     * (no failure), which would leave every plugin Step rejected at admission. Discovery
     * (this method) is deliberately separated from composition ([capabilityAccessFactory])
     * so the sensitive step is explicit and cannot be hidden inside a lazily-invoked lambda.
     */
    fun collectContributedCapabilities(): Map<StepCapability, Any> {
        val composed = LinkedHashMap<StepCapability, Any>()
        val owner = mutableMapOf<StepCapability, String>()
        val loader = ServiceLoader.load(StepCapabilityContributor::class.java)
        val iterator = loader.iterator()
        while (iterator.hasNext()) {
            val contributor = try {
                iterator.next()
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "External Step plugin discovery failed (broken plugin JAR on the runtime classpath?): " +
                        e.message,
                    e,
                )
            }
            for ((key, value) in contributor.capabilities()) {
                val previous = owner.putIfAbsent(key, contributor.id)
                if (previous != null) {
                    throw IllegalStateException(
                        "Duplicate capability contribution '" + key.key + "': providers " +
                            "'" + previous + "' and '" + contributor.id + "' both claim it. " +
                            "Capability ownership must be unique (no first-wins/last-wins).",
                    )
                }
                composed[key] = value
            }
        }
        return composed
    }

    /**
     * LFC-2E3-T4: PURE composition of an already-collected capability map into the
     * capability-access factory the coordinator consumes. Performs no discovery, so it is safe
     * to build and invoke outside the plugin-classloader window.
     *
     * Why this is needed: a registry Step is admitted only when every capability it declares is
     * available at prepare-time. Core capabilities come from the canonical bridge, but a capability
     * owned by a plugin has no other legitimate supplier, because production core MUST NOT name a
     * concrete plugin type. Without this factory the CLI composes the canonical bridge alone, so ANY
     * plugin Step that declares a capability is rejected at admission and never executes.
     *
     * Composition rules (fail-closed):
     * - the canonical bridge is ALWAYS the base layer, so core capabilities keep working;
     * - contributed capabilities are layered on top via `available()` + `get()`;
     * - returns `null` when nothing is contributed, so the coordinator falls back to the canonical
     *   bridge bit-equivalently (purely additive behaviour).
     */
    fun capabilityAccessFactory(
        contributed: Map<StepCapability, Any>,
    ): ((CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess)? {
        if (contributed.isEmpty()) return null
        return { ctx ->
            object : CanonicalRuntimeCapabilityAccess(ctx) {
                override fun available(): Set<StepCapability> = super.available() + contributed.keys

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: StepCapability): T {
                    val layered = contributed[key]
                    if (layered != null) return layered as T
                    return super.get(key)
                }
            }
        }
    }
}
