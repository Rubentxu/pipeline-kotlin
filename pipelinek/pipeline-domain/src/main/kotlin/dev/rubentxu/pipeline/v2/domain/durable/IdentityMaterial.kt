package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The fields whose value is a fact of instant.
 *
 * This is a DECLARED list, not a heuristic: a field is a fact of instant
 * because the domain says so. Adding one to this list is a domain decision,
 * not something inferred from the shape of a value.
 */
private val INSTANT_FIELDS: Set<String> = setOf("enteredAt")

/**
 * The declared text parsed as a JSON object, or null when it is not one.
 *
 * A payload that does not parse, or that parses into something other than an
 * object, declares no fields at all — and a text without fields cannot carry
 * a fact of instant.
 */
private fun parseObjectOrNull(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject

/**
 * The material of a durable operation's identity: the text that reaches the
 * digest, with the facts of instant projected out.
 *
 * ## Why the material is not the declared payload
 *
 * A Step's payload is the Step's own encoding, and some encodings carry a fact
 * of instant — `enteredAt`, the wall-clock moment the compiler entered a
 * control scope. That fact describes WHEN the program was compiled, not WHAT
 * the operation is, so it must not reach the digest: an identity that moves
 * with the clock turns a replayed program into a new operation, and makes the
 * identity check useless as a determinism guarantee.
 *
 * The payload itself is NOT touched. It is journalled, and the instant stays
 * observable there for whoever reads the journal. Only the material that
 * reaches the digest is projected.
 *
 * ## The two cases
 *
 * - [Whole]: the payload declares no fact of instant, so its identity material
 *   IS its declared text, byte for byte. The no-op is exact on purpose — the
 *   operations that carry no clock keep the identity they already had.
 * - [Projected]: the payload declares facts of instant, and they are projected
 *   out of the identity material.
 *
 * [of] is the only constructor. The cases are what the projection decides, not
 * what a caller assembles.
 */
internal sealed interface IdentityMaterial {

    /** The text that reaches the digest. */
    val identityText: String

    /** The facts of instant this material keeps out of the identity. */
    val excludedFields: Set<String>

    /**
     * A payload that declares no fact of instant: its identity material is its
     * declared text, byte for byte.
     */
    class Whole(override val identityText: String) : IdentityMaterial {
        override val excludedFields: Set<String> = emptySet()
    }

    /**
     * A payload that declares facts of instant: they are projected out of the
     * identity material.
     *
     * The invariant is checked at construction — the excluded fields are facts
     * of instant, and the projected text declares none of them.
     */
    class Projected(
        override val identityText: String,
        override val excludedFields: Set<String>,
    ) : IdentityMaterial {
        init {
            require(excludedFields.isNotEmpty()) {
                "a projected material must exclude at least one field, got ${excludedFields.size}"
            }
            require(excludedFields.all { it in INSTANT_FIELDS }) {
                "a projected material may only exclude facts of instant, got $excludedFields"
            }
            val projected = parseObjectOrNull(identityText)
            require(projected != null && excludedFields.none { it in projected }) {
                "the projected text still declares one of the excluded fields $excludedFields"
            }
        }
    }

    companion object {
        /**
         * The parameter that carries the Step's own encoding into the identity.
         */
        const val PAYLOAD_PARAMETER: String = "payload"

        /** The fields whose value is a fact of instant. */
        val instantFields: Set<String> = INSTANT_FIELDS

        /**
         * The identity material of a declared payload text.
         *
         * A text that is not a JSON object, or that does not parse, declares no
         * fields and therefore no fact of instant: it is kept whole.
         */
        fun of(declaredText: String): IdentityMaterial {
            val declared = parseObjectOrNull(declaredText) ?: return Whole(declaredText)
            val excluded = declared.keys.intersect(INSTANT_FIELDS)
            if (excluded.isEmpty()) return Whole(declaredText)
            return Projected(projectedText(declared, excluded), excluded)
        }

        /**
         * The parameters of an operation, with the payload's identity material
         * in place of the payload itself.
         *
         * Only the payload parameter is projected: the other parameters
         * (`sandboxProfile`, `bodyStructure`) are facts the program declared
         * about itself, and a fact of instant among them would be declared by
         * another rule, not by this one.
         */
        fun project(params: Map<String, JsonElement>): Map<String, JsonElement> =
            params.mapValues { (key, value) ->
                if (key == PAYLOAD_PARAMETER) value.identityMaterial() else value
            }

        private fun JsonElement.identityMaterial(): JsonElement =
            if (this is JsonPrimitive && isString) {
                JsonPrimitive(of(content).identityText)
            } else {
                this
            }

        /**
         * The declared object without the facts of instant, with its keys in
         * canonical order so that two compilations project to the same text.
         */
        private fun projectedText(declared: JsonObject, excluded: Set<String>): String =
            JsonObject(declared.filterKeys { it !in excluded }.toSortedMap()).toString()
    }
}
