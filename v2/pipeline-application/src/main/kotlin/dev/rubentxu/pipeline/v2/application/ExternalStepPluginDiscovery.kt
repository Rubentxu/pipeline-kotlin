package dev.rubentxu.pipeline.v2.application

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
}
