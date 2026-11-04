package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import java.util.ServiceLoader

/**
 * Runtime discovery adapter for the SEAMS OFFICIAL_PLUGINs contribute
 * ## Why this exists
 * The HTTP plugin ships `HttpCapabilityContributor`, which supplies
 * `http.transport`, and `HttpRequestStep` declares that capability as required.
 * The contributor reaches the runtime through the plugin JAR's
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor`
 * file: `ServiceLoader` instantiates it when the JAR sits on the runtime
 * classpath, so the core never has to name the plugin to receive what the
 * plugin owns.
 *
 * Two facts make that enough on their own. The capability arrives in the SAME
 * installation as the Step that requires it, because both live in the plugin
 * JAR. And discovery happens INSIDE the classloader window that resolves the
 * Steps, so a plugin whose classes live below the TCCL contributes its Step
 * and its capability, or neither. The composition holds the contributors it
 * discovered, and the coordinator builds one `CompositeCapabilityContributor`
 * out of them.
 * ## Why the same mechanism as Steps
 * `ExternalStepPluginDiscovery` is already the single `ServiceLoader` site for
 * Steps, and it is the reason a plugin ships by dropping a JAR on the classpath.
 * Capabilities go through the identical path, and for the same reason: the core
 * must not learn the name of a plugin in order to receive what that plugin owns.
 * If capability wiring needed a `when` in the composition root, then adding the
 * next OFFICIAL_PLUGIN would be a change to core — which is precisely the
 * dependency direction this architecture forbids.
 * A plugin JAR is already on the runtime classpath in the distribution
 * (`BundledPluginClasspathPlan` proves it by probing for the
 * `StepDefinitionContributor` service file), so no classpath work is needed here.
 * ## Fail-closed
 * A contributor that throws during discovery aborts composition, exactly as for
 * Steps. A broken plugin JAR must not degrade into a partially wired runtime where
 * a Step is admitted for a capability that is only sometimes there.
*/
object ExternalCapabilityContributorDiscovery {

    /**
     * Discovers every [RuntimeCapabilityContributor] on the runtime classpath.
     * Order follows the classpath, and collisions are NOT resolved here:
     * [dev.rubentxu.pipeline.v2.domain.step.CompositeCapabilityContributor] refuses a
     * duplicated capability by name, and that is the correct place for the decision
     * because it is the single place both admission and execution consult.
*/
    fun discover(): List<RuntimeCapabilityContributor> {
        val contributors = mutableListOf<RuntimeCapabilityContributor>()
        val iterator = ServiceLoader.load(RuntimeCapabilityContributor::class.java).iterator()
        while (iterator.hasNext()) {
            contributors.add(
                try {
                    iterator.next()
                } catch (e: Throwable) {
                    throw IllegalStateException(
                        "Runtime capability contributor discovery failed " +
                            "(broken plugin JAR on the runtime classpath?): ${e.message}",
                        e,
                    )
                },
            )
        }
        return contributors
    }
}
