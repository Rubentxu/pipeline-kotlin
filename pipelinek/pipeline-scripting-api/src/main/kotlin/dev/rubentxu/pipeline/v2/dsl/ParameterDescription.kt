package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The description of a declaration of parameters: the same contract, written in the shape a
 * consumer can read.
 *
 * ## What this is
 *
 * A JSON Schema 2020-12 document that says which parameters a pipeline accepts, what type
 * each one has, which of them are required and what a caller may send for a closed set.
 *
 * ## Why it is a REPRESENTATION and not another authority
 *
 * Because the contract has ONE owner: the declaration the author wrote. This document is a
 * FUNCTION of that declaration — there is no second contract to maintain and no file that can
 * drift from the script. The one that CHECKS the values is the validator
 * ([RunInputs]), and it speaks the vocabulary of the declaration, not the vocabulary of this
 * document: a caller that builds its inputs from here and has them checked there is using
 * the same declaration from both sides.
 *
 * ## Why the shape is the one of JSON Schema and not one of the product's
 *
 * Because the consumer of a contract is usually not this product: a form, a linter, another
 * tool. A shape the product invented would need a reader invented too, and the reader would
 * know nothing about anything else. JSON Schema is a shape other tools already read.
 *
 * ## Why the required ones are the ones with no default
 *
 * Because that is the same rule the validator applies: a parameter that declares a default
 * may be left out, and a parameter that declares none has to be delivered. The two answers
 * cannot disagree, because they read the same declaration.
 */
object ParameterDescription {

    /**
     * The dialect of the document.
     *
     * It is written in the document itself, so a consumer that reads the document knows which
     * rules it is written against without having to ask.
     */
    const val DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

    /**
     * The description of [declaration].
     *
     * The declaration is read and not interpreted: a type this product does not know is
     * described with its own name, so the document says what was declared even when the
     * product would not be able to check a value against it.
     */
    fun of(declaration: List<ParameterSpec>): JsonObject = buildJsonObject {
        put("\$schema", JsonPrimitive(DIALECT))
        put("type", JsonPrimitive("object"))
        put("properties", propertiesOf(declaration))
        put("required", requiredOf(declaration))
        put("propertyNames", propertyNames())
        put("additionalProperties", JsonPrimitive(false))
    }

    /**
     * The rules the NAME of a parameter has to follow.
     *
     * ## Why they are here
     *
     * Because the description has to describe the same contract the validator checks, and
     * the validator refuses a name that is not the shape of a name or that belongs to the
     * product. A description that said nothing about the names would let a consumer build a
     * request the product refuses, and the two would be describing different contracts.
     */
    private fun propertyNames(): JsonObject = buildJsonObject {
        put("pattern", JsonPrimitive(NAME_PATTERN))
        put(
            "not",
            buildJsonObject {
                put("pattern", JsonPrimitive(RESERVED_PATTERN))
            },
        )
    }

    /**
     * The properties of the description: one per declared parameter.
     *
     * The properties are in the order of the declaration, so two descriptions of the same
     * declaration are the same text.
     */
    private fun propertiesOf(declaration: List<ParameterSpec>): JsonObject = buildJsonObject {
        for (spec in declaration) {
            put(spec.name, propertyOf(spec))
        }
    }

    /**
     * The property of one parameter.
     *
     * ## Why every value is a TEXT
     *
     * Because the values of the parameters travel as texts, and the description has to
     * describe what travels: a consumer that read this schema and built a JSON boolean out
     * of it would send something the product refuses. A boolean is therefore described as a
     * text with the two texts it accepts.
     *
     * A closed set is described with its texts, so a consumer can offer the choices instead
     * of guessing them. A default is described when the parameter declares one: the value a
     * caller gets when it delivers nothing.
     */
    private fun propertyOf(spec: ParameterSpec): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        val accepted = acceptedTextsOf(spec)
        if (accepted.isNotEmpty()) {
            put("enum", JsonArray(accepted.map { JsonPrimitive(it) }))
        }
        spec.defaultValue?.let { put("default", JsonPrimitive(it)) }
    }

    /**
     * The texts a parameter accepts, or nothing when it accepts any text.
     */
    private fun acceptedTextsOf(spec: ParameterSpec): List<String> =
        when (spec.type) {
            RunInputs.TYPE_BOOLEAN -> listOf("true", "false")
            RunInputs.TYPE_CHOICE -> spec.choices
            else -> emptyList()
        }

    /**
     * The names of the parameters a caller has to deliver.
     *
     * A parameter that declares a default may be left out, and one that declares none has
     * to be delivered: the absence of the default IS the obligation.
     */
    private fun requiredOf(declaration: List<ParameterSpec>): JsonArray = buildJsonArray {
        for (spec in declaration) {
            if (spec.defaultValue == null) {
                add(JsonPrimitive(spec.name))
            }
        }
    }

    /**
     * The shape the NAME of a parameter has to have, as a regular expression.
     *
     * It is the validator's own pattern, read and not copied: a description that spelled the
     * shape a second time would be a second rule, and two rules about the same thing are two
     * rules that can disagree.
     *
     * It is ANCHORED, because the pattern of JSON Schema is not: it matches when the
     * expression matches anywhere in the name.
     */
    private val NAME_PATTERN: String = "^${RunInputs.NAME_PATTERN.pattern}$"

    /**
     * The prefixes the product owns, as a regular expression.
     *
     * It is built from the validator's own prefixes, read and not copied, for the same reason
     * the shape of a name is.
     */
    private val RESERVED_PATTERN: String =
        RunInputs.RESERVED_PREFIXES.joinToString("|", prefix = "^(", postfix = ")") {
            Regex.escape(it)
        }
}
