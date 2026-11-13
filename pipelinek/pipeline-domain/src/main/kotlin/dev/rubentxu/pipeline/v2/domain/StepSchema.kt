package dev.rubentxu.pipeline.v2.domain

/**
 * The schema of the payload of a step, and the ONE place that says what the
 * absence of a schema means.
 *
 * ## Why this exists
 *
 * A schema of a step can be in three states, and a reader has to tell them
 * apart:
 *
 * 1. **nobody declared the arguments** — the codec of the step does not say
 *    what it accepts. The catalogue publishes NO schema for it, and the
 *    reader knows that the product itself does not say.
 * 2. **the step takes no arguments** — the codec declares an empty set of
 *    arguments. The catalogue publishes a schema with no properties, and the
 *    reader knows the step takes nothing.
 * 3. **the step takes arguments** — the catalogue publishes the schema that
 *    names them.
 *
 * The three states used to collapse into one: the schema of a step nobody
 * declared was the empty schema, and the empty schema is also what a step
 * with no arguments could publish. A reader could not tell "this step takes
 * nothing" from "nobody said", and a document that cannot tell them apart
 * publishes an empty fact — a fact that says nothing and looks like one.
 *
 * ## The convention
 *
 * [UNDECLARED] is the text that means "nobody declared the arguments". It
 * is the default of the seam
 * ([dev.rubentxu.pipeline.v2.domain.step.StepCodec.schema]) and of the
 * descriptor of a family ([StepDescriptor.inputSchema]), so that a step that
 * says nothing does not have to say that it says nothing.
 */
object StepSchema {

    /**
     * The schema of a step whose arguments nobody declared.
     *
     * It is the empty JSON Schema: a schema that describes nothing, and says
     * nothing about the payload either. The catalogue does NOT publish it:
     * the ABSENCE of the key is what tells the reader that nobody declared
     * the arguments, and a step that declares no arguments publishes a
     * schema of its own that says so.
     */
    const val UNDECLARED: String = "{}"

    /**
     * The schema [schema] declares, or null when nobody declared one.
     *
     * @param schema the schema the codec or the descriptor carries.
     * @return the declared schema, or null when there is none.
     */
    fun declared(schema: String): String? =
        schema.takeUnless { it.isBlank() || it == UNDECLARED }
}
