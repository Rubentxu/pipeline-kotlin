package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry
import dev.rubentxu.pipeline.v2.events.registry.RegistrationOutcome
import java.util.ServiceLoader

/**
 * Runtime discovery adapter for the event kinds SEAMS OFFICIAL_PLUGINs contribute (P3-B / S6.4).
 *
 * ## Why this exists
 *
 * Before it, the open [EventRegistry], the closed carrier and the whole durable round trip were
 * reachable only from a test. There were `ServiceLoader` paths for Steps, for Directives and for
 * Capabilities, and none for events, so a plugin JAR could ship an `EventDefinition` and the
 * runtime would never hear of it. `RegistryEventEmitter` was constructed in no production site.
 *
 * ## Why the same mechanism as Steps, Directives and Capabilities
 *
 * The core must not learn the name of a plugin in order to receive what that plugin owns. If
 * event contributions needed a `when` in the composition root, then adding the next plugin would
 * be a change to core — the dependency direction this architecture forbids. A plugin ships by
 * dropping a JAR on the classpath, and the registry is what decides whether that is allowed.
 *
 * This is deliberately a small ADAPTER and not a fourth composition identity. S6 has to converge
 * identity, versioning, provenance and discovery across Steps, Directives, Capabilities and Events;
 * what belongs to that future is the manifest, not this loader, which is exactly the shape the
 * other three loaders already have.
 *
 * ## Fail-closed, in two distinct places
 *
 *  1. A contributor that THROWS while being loaded aborts composition, exactly as for Steps.
 *  2. A contributor whose declaration is REFUSED — a duplicate kind, or a malformed definition —
 *     aborts composition too, naming the contributor and every reason.
 *
 * The second one is the reason [compose] does not skip a refusal and carry on. A partially
 * composed registry is the worst outcome available here: a plugin whose first three events
 * registered and whose fourth collided would look like a working feature that silently drops one
 * of its own observations.
 */
object ExternalEventDefinitionDiscovery {

    /**
     * Discovers every [EventDefinitionContributor] on the runtime classpath.
     *
     * Order follows the classpath, and collisions are NOT resolved here: the [EventRegistry]
     * refuses a duplicated kind by name, which is the correct place for that decision because it
     * is the single place both emission and read-back consult.
     */
    fun discover(): List<EventDefinitionContributor> {
        val contributors = mutableListOf<EventDefinitionContributor>()
        val iterator = ServiceLoader.load(EventDefinitionContributor::class.java).iterator()
        while (iterator.hasNext()) {
            contributors.add(
                try {
                    iterator.next()
                } catch (e: Throwable) {
                    throw IllegalStateException(
                        "Event definition contributor discovery failed " +
                            "(broken plugin JAR on the runtime classpath?): ${e.message}",
                        e,
                    )
                },
            )
        }
        return contributors
    }

    /**
     * The ONE composition authority: discovers contributors and admits their declarations into a
     * fresh [EventRegistry], or refuses the whole composition.
     *
     * Deterministic and fresh per call; no global registry, matching
     * [CoreStepRegistryFactory.registry].
     *
     * @throws IllegalStateException naming the contributor and every reason, if any declaration is
     *   refused. There is no partial-success mode.
     */
    fun compose(
        contributors: List<EventDefinitionContributor> = discover(),
    ): EventRegistry {
        val registry = EventRegistry.create()
        for (contributor in contributors) {
            val declarations = try {
                contributor.definitions()
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "Event definition contributor '${contributor.id}' failed to declare its kinds: " +
                        "${e.message}. Aborting composition: a partially composed event registry " +
                        "would silently drop some of this plugin's own observations.",
                    e,
                )
            }
            for (creation in declarations) {
                when (val outcome = registry.register(creation)) {
                    is RegistrationOutcome.Registered -> Unit
                    is RegistrationOutcome.DuplicateKind -> throw IllegalStateException(
                        "Event definition contributor '${contributor.id}' declared kind " +
                            "'${outcome.kind}', already owned by '${outcome.registeredBy}'. " +
                            "Aborting composition: a shadowed event kind makes every recorded " +
                            "payload of that kind ambiguous forever.",
                    )
                    is RegistrationOutcome.RejectedDefinition -> throw IllegalStateException(
                        "Event definition contributor '${contributor.id}' declared an invalid " +
                            "event definition: ${outcome.problems.joinToString("; ")}. " +
                            "Aborting composition.",
                    )
                }
            }
        }
        return registry
    }
}
