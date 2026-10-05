package dev.rubentxu.pipeline.v2.events.registry

/**
 * The typed contract a plugin declares for its OWN event payload (P3 of the Runtime Observation
 * Contract Closure, slice 1).
 *
 * The core `DomainEvent` hierarchy stays sealed: it is the vocabulary of the ENGINE, and opening
 * it would let any classpath stranger masquerade as a core event. A plugin event instead travels
 * as identity — the same `PipelineEventEnvelope` every event gets — plus a payload that only
 * becomes typed again through THIS definition's codec, from the registry the plugin registered
 * it in. Nothing here names a concrete event, a transport, or a storage format: a definition is
 * a declaration, and a declaration performs no effects (a builder that executes is a Step
 * wearing a builder's name).
 *
 * Every field is required by the Semantic Constitution §8/§9: an external contribution without
 * schema, version and emission authority is forbidden, so the definition is where those three
 * become MANDATORY instead of conventional. `kind` must be namespaced
 * (`<plugin-id>.<EventName>`) so two plugins cannot collide by accident and a collision that
 * still happens is an explicit registration failure, never a silent overwrite.
 *
 * @param kind namespaced event kind, e.g. `fabric.contractvalidated`. Unique per registry.
 * @param schemaVersion 1-based version of THIS payload shape. Decode is versioned: a payload
 *   written under a version the codec does not know fails closed — it is never "best-effort
 *   parsed".
 * @param emittedBy the identity of the contributor that owns the emission, as provenance the
 *   read side can surface. Not trusted for decisions; declared for inspection.
 */
class EventDefinition<P : Any> private constructor(
    val kind: String,
    val schemaVersion: Int,
    val payloadClass: Class<P>,
    val codec: EventPayloadCodec<P>,
    val emittedBy: String,
) {
    companion object {
        const val MIN_SCHEMA_VERSION: Int = 1

        /**
         * The only construction path. Total validation of the declaration BEFORE any registry
         * sees it, so an invalid definition is a construction failure with named reasons, not a
         * registration that half-succeeded.
         */
        fun <P : Any> create(
            kind: String,
            schemaVersion: Int,
            payloadClass: Class<P>,
            codec: EventPayloadCodec<P>,
            emittedBy: String,
        ): EventDefinitionCreation<P> {
            val problems = buildList {
                if (kind.isBlank()) add("kind must not be blank")
                else {
                    if (!kind.contains('.')) {
                        add("kind '$kind' must be namespaced as <plugin-id>.<EventName>")
                    }
                    if (kind.any { it.isWhitespace() }) add("kind '$kind' must not contain whitespace")
                }
                if (schemaVersion < MIN_SCHEMA_VERSION) {
                    add("schemaVersion must be >= $MIN_SCHEMA_VERSION, got $schemaVersion")
                }
                if (emittedBy.isBlank()) add("emittedBy must not be blank")
            }
            return if (problems.isEmpty()) {
                EventDefinitionCreation.Valid(
                    EventDefinition(kind, schemaVersion, payloadClass, codec, emittedBy),
                )
            } else {
                EventDefinitionCreation.Invalid(problems)
            }
        }
    }
}

/**
 * Outcome of declaring an event. A sealed ADT rather than a boolean-plus-nullable pair or an
 * exception: an invalid declaration is an EXPECTED operational outcome, not a defect.
 */
sealed interface EventDefinitionCreation<P : Any> {
    /** The declaration is well-formed and ready to register. Carries the definition itself. */
    data class Valid<P : Any>(val definition: EventDefinition<P>) : EventDefinitionCreation<P>

    /** The declaration is malformed. Carries every reason found, not just the first. */
    data class Invalid<P : Any>(val problems: List<String>) : EventDefinitionCreation<P>
}

/**
 * Versioned payload codec of one event kind. Implemented by the CONTRIBUTOR, in its own module,
 * with its own serialization stack: the core moves strings and typed payloads and never parses
 * a plugin's format itself.
 *
 * Implementations MUST be pure and deterministic: the same payload encodes to the same string,
 * and the same string decodes to the same payload, every time — a codec that embedded a clock
 * or ambient state would make recorded events unreplayable.
 */
interface EventPayloadCodec<P : Any> {
    /** Serializes the payload. The result must be stable for identical inputs. */
    fun encode(payload: P): String

    /**
     * Deserializes a payload written under [schemaVersion]. A version the implementation does
     * not recognise MUST return [PayloadDecode.UnknownSchemaVersion] — reading an unknown shape
     * as "probably fine" is the semantic drop the Constitution forbids.
     */
    fun decode(raw: String, schemaVersion: Int): PayloadDecode<P>
}

/**
 * Outcome of a versioned payload decode. Three cases with different meanings; none is
 * interchangeable with another.
 */
sealed interface PayloadDecode<P : Any> {
    /** The payload is a well-formed instance of the declared version's schema. */
    data class Decoded<P : Any>(val payload: P) : PayloadDecode<P>

    /** The payload was written under a schema version this codec does not support. */
    data class UnknownSchemaVersion<P : Any>(val schemaVersion: Int) : PayloadDecode<P>

    /** The payload claims a known version but does not conform to that version's schema. */
    data class Malformed<P : Any>(val reason: String) : PayloadDecode<P>
}
