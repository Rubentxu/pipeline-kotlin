package dev.rubentxu.pipeline.v2.dsl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parameters a pipeline declares, and the description of them.
 *
 * ## The guarantee under trial
 *
 * That the declaration the author writes is the ONLY contract there is: the DSL offers one
 * function per type the product knows, the description is a projection of that declaration,
 * and the two cannot disagree.
 *
 * ## Why the description is tested HERE
 *
 * Because the description is a function of the declaration, and a function that is tested
 * apart from its argument can be tested against anything. The rows build the declaration
 * with the DSL and describe it, so a projection that read something else would fail here.
 */
class PipelineParametersTest {

    @Test
    fun `the DSL declares the three types it knows`() {
        val spec = pipeline {
            parameters {
                string("target", default = "staging")
                boolean("dryRun")
                choice("mode", values = listOf("fast", "safe"), default = "safe")
            }
        }

        assertEquals(3, spec.parameters.size)
        assertEquals("target", spec.parameters[0].name)
        assertEquals(RunInputs.TYPE_STRING, spec.parameters[0].type)
        assertEquals("staging", spec.parameters[0].defaultValue)
        assertEquals("dryRun", spec.parameters[1].name)
        assertEquals(RunInputs.TYPE_BOOLEAN, spec.parameters[1].type)
        assertEquals(null, spec.parameters[1].defaultValue)
        assertEquals("mode", spec.parameters[2].name)
        assertEquals(RunInputs.TYPE_CHOICE, spec.parameters[2].type)
        assertEquals(listOf("fast", "safe"), spec.parameters[2].choices)
    }

    @Test
    fun `the boolean of a declaration is written as the text of the boolean it is`() {
        val spec = pipeline {
            parameters {
                boolean("dryRun", default = true)
            }
        }

        assertEquals("true", spec.parameters.single().defaultValue)
    }

    @Test
    fun `a pipeline that declares no parameters declares none`() {
        val spec = pipeline { }

        assertTrue(spec.parameters.isEmpty())
    }

    @Test
    fun `the description says which parameters are required and which carry a default`() {
        val spec = pipeline {
            parameters {
                string("target", default = "staging")
                boolean("dryRun")
                choice("mode", values = listOf("fast", "safe"))
            }
        }

        val description = ParameterDescription.of(spec.parameters)

        assertEquals(ParameterDescription.DIALECT, description.string("\$schema"))
        assertEquals("object", description.string("type"))
        assertEquals(JsonPrimitive(false), description["additionalProperties"])
        val properties = description["properties"] as JsonObject
        assertEquals(3, properties.size)
        assertEquals("staging", (properties["target"] as JsonObject).string("default"))
        // The boolean is a TEXT with two accepted texts, because that is what travels.
        assertEquals("string", (properties["dryRun"] as JsonObject).string("type"))
        val choices = (properties["mode"] as JsonObject)["enum"] as JsonArray
        assertEquals(listOf("fast", "safe"), choices.map { (it as JsonPrimitive).content })
        val required = description["required"] as JsonArray
        assertEquals(listOf("dryRun", "mode"), required.map { (it as JsonPrimitive).content })
    }

    @Test
    fun `the description of a pipeline that declares nothing is an object with no properties`() {
        val description = ParameterDescription.of(emptyList())

        assertEquals(0, (description["properties"] as JsonObject).size)
        assertEquals(0, (description["required"] as JsonArray).size)
    }

    @Test
    fun `the description of a boolean is a text and not a JSON boolean`() {
        // The values of the parameters travel as TEXTS, and the description has to describe
        // what travels: a consumer that built a JSON boolean out of this schema would send
        // something the product refuses.
        val spec = pipeline {
            parameters {
                boolean("dryRun")
            }
        }

        val description = ParameterDescription.of(spec.parameters)
        val property = (description["properties"] as JsonObject)["dryRun"] as JsonObject

        assertEquals("string", property.string("type"))
        assertEquals(listOf("true", "false"), (property["enum"] as JsonArray).map { (it as JsonPrimitive).content })
    }

    /** The text [key] holds, or null when it holds none. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}
