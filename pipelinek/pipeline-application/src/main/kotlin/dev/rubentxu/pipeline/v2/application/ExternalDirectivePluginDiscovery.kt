package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import java.util.ServiceLoader

/**
 * Runtime discovery adapter for external DIRECTIVE plugins (S1-D).
 *
 * The domain defines the [DirectiveContributor] SPI but never calls
 * [ServiceLoader] itself: discovery is a runtime-adapter concern. This adapter
 * is the ONLY place ServiceLoader is used for directives, mirroring
 * [ExternalStepPluginDiscovery].
 *
 * Composition order: callers fold external contributions into a builder that
 * already holds any host-registered definitions. [DirectiveRegistry.Builder]
 * fails closed on a duplicate key, so an external plugin can never silently
 * shadow an existing directive.
 *
 * Fail-closed: a contributor that throws during discovery aborts composition —
 * a broken plugin JAR must not degrade into a partial registry.
 */
object ExternalDirectivePluginDiscovery {

    /**
     * Discovers contributors from the RUNTIME classpath (TCCL) and folds their
     * definitions into [builder]. Returns the discovered contributor ids for
     * diagnostics.
     */
    fun registerInto(builder: DirectiveRegistry.Builder): List<String> {
        val registered = mutableListOf<String>()
        val loader = ServiceLoader.load(DirectiveContributor::class.java)
        val iterator = loader.iterator()
        while (iterator.hasNext()) {
            val contributor = try {
                iterator.next()
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "External directive plugin discovery failed (broken plugin JAR on the runtime classpath?): ${e.message}",
                    e,
                )
            }
            builder.addContributors(contributor)
            registered.add(contributor.javaClass.name)
        }
        return registered
    }
}
