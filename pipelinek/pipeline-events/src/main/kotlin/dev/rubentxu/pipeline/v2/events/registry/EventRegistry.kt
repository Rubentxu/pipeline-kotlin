package dev.rubentxu.pipeline.v2.events.registry

/**
 * The open registry where contributors declare their event kinds (P3 slice 1, Semantic
 * Constitution §8.4: payloads are typed, versioned, and registered through an open
 * `EventRegistry`).
 *
 * Laws, each pinned by [EventRegistryTest]:
 *
 *  - **Fail-closed admission.** A duplicate [EventDefinition.kind] is a registration FAILURE,
 *    never a silent overwrite: two plugins claiming one kind would make every recorded payload
 *    of that kind ambiguous forever. A definition that did not pass
 *    [EventDefinition.create] validation cannot be registered at all — it is not accepted "with
 *    warnings".
 *  - **Read-only after registration.** There is no unregister, no replace, no mutation path: a
 *    registry observed by a reader never changes shape underneath it. The registered snapshot is
 *    deterministic — the same registrations in the same order produce the same registry.
 *  - **No silent defaults.** Asking for an unknown kind returns `null`; nothing fabricates a
 *    placeholder definition, because a placeholder would let an unregistered payload decode as
 *    "something" instead of failing as "unknown".
 *  - **The registry types payloads; it does not create events.** Appending, sequencing and
 *    storage remain the EventStore's authority. This registry answers "what does this kind MEAN
 *    and how is it decoded" — nothing else.
 */
class EventRegistry private constructor(
    private val byKind: Map<String, RegisteredEvent>,
) {

    /** A definition and the immutable registration facts the read side can surface. */
    class RegisteredEvent internal constructor(
        val definition: EventDefinition<*>,
        val registeredOrder: Int,
    )

    /** The definition for [kind], or `null` — never a placeholder for an unknown kind. */
    fun definition(kind: String): EventDefinition<*>? = byKind[kind]?.definition

    /** Whether [kind] is registered. Reads do not mutate. */
    fun isRegistered(kind: String): Boolean = byKind.containsKey(kind)

    /** The registered kinds in registration order. Deterministic for identical registrations. */
    fun registeredKinds(): List<String> = byKind.values.map { it.definition.kind }

    /** The number of registered kinds. */
    fun size(): Int = byKind.size

    /**
     * S6/F: composition is a separate TYPE from observation.
     *
     * This class had a public `register` and a KDoc that claimed "read-only after
     * registration". The claim was false for as long as it held a `register`: any holder of
     * the reference could extend the registry a reader is consulting, so the reader's
     * "immutable snapshot" was only immutable by convention. `register` now lives on
     * [Builder], so the law is a property of the type rather than a promise in a comment.
     *
     * Duplicate rejection and invalid-definition rejection are unchanged and are still
     * TOTAL: [Builder.register] returns a [RegistrationOutcome] and never throws, so
     * composition reports refusals as values.
     */
    class Builder {
        private val byKind = LinkedHashMap<String, RegisteredEvent>()

        /**
         * Admits a validated definition. Total over its input: every outcome is one of the
         * [RegistrationOutcome] cases, none of which throws.
         */
        fun register(creation: EventDefinitionCreation<*>): RegistrationOutcome {
            val definition = when (creation) {
                is EventDefinitionCreation.Valid<*> -> creation.definition
                is EventDefinitionCreation.Invalid<*> -> return RegistrationOutcome.RejectedDefinition(
                    creation.problems,
                )
            }
            val existing = byKind[definition.kind]
            if (existing != null) {
                return RegistrationOutcome.DuplicateKind(
                    kind = definition.kind,
                    registeredBy = existing.definition.emittedBy,
                )
            }
            byKind[definition.kind] = RegisteredEvent(definition, byKind.size + 1)
            return RegistrationOutcome.Registered(definition.kind)
        }

        /**
         * Freeze the composition.
         *
         * The copy is what makes a builder safe to reuse: a `Builder` kept alive after
         * `build()` can keep collecting declarations, and none of them reach the registry
         * already handed out.
         */
        fun build(): EventRegistry {
            val snapshot = LinkedHashMap<String, RegisteredEvent>(byKind.size)
            byKind.entries.forEachIndexed { index, entry -> snapshot[entry.key] = entry.value }
            return EventRegistry(snapshot)
        }
    }

    companion object {
        /** A fresh [Builder]. Registration order starts at 1. */
        fun builder(): Builder = Builder()

        /**
         * An empty, ALREADY-FROZEN registry. Registration order starts at 1.
         *
         * Used where "a plugin declared nothing" and "nothing is registered" must be the
         * same typed refusal. There is no registration path off the returned value, so an
         * empty registry cannot later grow under a reader.
         */
        fun create(): EventRegistry = Builder().build()
    }
}

/** Outcome of an admission attempt. Fail-closed cases carry what a diagnostic needs. */
sealed interface RegistrationOutcome {
    /** Admitted. Carries the kind as registered. */
    data class Registered(val kind: String) : RegistrationOutcome

    /**
     * Refused: another definition already owns this kind. Carries who owns it, so the conflict
     * is diagnosable without inspecting registry internals.
     */
    data class DuplicateKind(val kind: String, val registeredBy: String) : RegistrationOutcome

    /** Refused: the declaration itself was malformed. Carries every reason. */
    data class RejectedDefinition(val problems: List<String>) : RegistrationOutcome
}
