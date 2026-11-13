package dev.rubentxu.pipeline.v2.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * The arguments a step accepts, as the catalogue publishes them.
 *
 * ## Why this type exists
 *
 * The catalogue tells a consumer HOW a step is called, and until now it could
 * not: the entry of a step carried a schema, and the schema of a step nobody
 * declared the arguments of was the empty one — a text a reader cannot tell
 * from "this step takes no arguments". A fact that cannot be told apart from
 * an oversight is not a fact.
 *
 * The DECLARATION lives with the code that owns the step: the codec that
 * encodes and decodes the payload declares the arguments it accepts, and the
 * descriptor of a family that has no codec declares them too. A schema
 * written in a document would be a second answer to a question the product
 * already answers, and it would go stale in silence.
 *
 * ## What it declares, and what it does not
 *
 * It declares the ARGUMENTS of the step: the fields a caller passes, with
 * their type, with whether they are required, and with what they mean. It
 * does NOT declare the discriminant that identifies the step on the wire
 * (`kind`), because a discriminant is not an argument: the caller does not
 * pass it, the step does.
 *
 * It does not publish the VALUES a closed enumeration accepts either. That
 * list would be a second copy of an enumeration the product already owns,
 * and a copy goes stale in silence; the description of the argument names the
 * enumeration instead, and the reader looks it up where it is defined.
 *
 * ## The renderer
 *
 * [jsonSchema] is the ONLY place a schema of arguments is written. One
 * renderer means one shape, and a shape a reader can learn once.
 *
 * @property arguments the arguments the step accepts, in the order the schema publishes them.
 * @property openPayloadType the type of the fields the payload may carry BEYOND the declared
 *   ones, or null when the payload carries nothing else. It is not null for the steps whose
 *   payload is an open map by design.
 */
data class StepArguments(
    val arguments: List<StepArgument> = emptyList(),
    val openPayloadType: StepArgumentType? = null,
) {

    /**
     * The JSON Schema of these arguments, as the catalogue publishes it.
     *
     * The dialect is the one the product uses everywhere else
     * (`https://json-schema.org/draft/2020-12/schema`), and the title names
     * the step the schema belongs to, so a schema that reaches a reader
     * alone says whose it is.
     *
     * A step that takes NO arguments renders a schema with no properties and
     * no required names. It is not the empty schema: an empty schema is what
     * nobody declared, and [StepSchema.UNDECLARED] is that one.
     *
     * @param key the key of the step the schema describes.
     * @return the schema, as the text the catalogue publishes.
     */
    fun jsonSchema(key: String): String {
        val properties = buildJsonObject {
            arguments.forEach { argument -> put(argument.name, argument.jsonSchema()) }
        }
        val required = arguments.filter { it.required }.map { it.name }
        val schema: JsonObject = buildJsonObject {
            put("\$schema", JsonPrimitive(DIALECT))
            put("title", JsonPrimitive("$key input"))
            put("type", JsonPrimitive("object"))
            put("properties", properties)
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { name -> add(JsonPrimitive(name)) } }
            }
            openPayloadType?.let { open ->
                put("additionalProperties", buildJsonObject { put("type", JsonPrimitive(open.jsonType)) })
            }
        }
        return Json.encodeToString(JsonObject.serializer(), schema)
    }

    /**
     * The schema of ONE argument, as the catalogue publishes it.
     *
     * @return the schema of the argument, as the text the catalogue publishes.
     */
    private fun StepArgument.jsonSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type.jsonType))
        if (description.isNotBlank()) {
            put("description", JsonPrimitive(description))
        }
    }

    /**
     * The dialect of the schemas and the factory of the declaration.
     */
    companion object {

        /** The dialect of the schemas the catalogue publishes. */
        const val DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

        /**
         * The arguments of a step, declared as a list.
         *
         * @param arguments the arguments the step accepts.
         * @return the declaration of those arguments.
         */
        fun of(vararg arguments: StepArgument): StepArguments = StepArguments(arguments.toList())
    }
}

/**
 * One argument a step accepts.
 *
 * @property name the name the argument has on the wire.
 * @property type the type of the value the argument carries.
 * @property required whether the caller has to pass it.
 * @property description what the argument means, for the reader of the catalogue.
 */
data class StepArgument(
    val name: String,
    val type: StepArgumentType,
    val required: Boolean = true,
    val description: String = "",
)

/**
 * The types an argument of a step can carry.
 *
 * The family is closed because the schema is a published contract: a type
 * that a name spelled could grow with the next refactor would change the
 * meaning of a document without changing its version.
 */
enum class StepArgumentType {
    /** A text. */
    STRING,

    /** A truth value. */
    BOOLEAN,

    /** A whole number. */
    INTEGER,

    /** A number that is not a whole number. */
    NUMBER,

    /** A list of values. */
    ARRAY,

    /** A structured value. */
    OBJECT;

    /** The name the JSON Schema spells this type with. */
    val jsonType: String get() = name.lowercase()
}
