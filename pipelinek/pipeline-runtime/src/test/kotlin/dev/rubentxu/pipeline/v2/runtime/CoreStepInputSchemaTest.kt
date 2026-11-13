package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepSchema
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The schema that the catalogue publishes for a core step, and the payload the
 * step really carries.
 *
 * ## The guarantee under trial
 *
 * That the schema of a core step names the arguments the step's payload
 * carries, and that the schema is not a text that went stale: a declaration
 * that names an argument the payload does not carry, or that forgets one it
 * does, is a second answer to a question the codec already answers.
 *
 * ## How it is measured
 *
 * The schema builds the payload, the codec decodes it, and the codec encodes
 * the decoded value again: the keys of that second payload are the keys the
 * step really writes. The schema is compared with them, so a declaration that
 * drifted from the wire is a red row and not a silent fact in a document.
 *
 * The discriminant the payload carries is NOT an argument — the caller does
 * not pass it — so the table below says which value each step writes, and the
 * schema is not asked to name it.
 *
 * ## What this instrument cannot see
 *
 * It does not run a pipeline and does not read a script: it observes the
 * CODECS of the core composition, with the registry as the only source of the
 * steps it walks. The families the composition does not register have no
 * codec, and their declaration is the descriptor's — the row of the catalogue
 * reads that one.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CoreStepInputSchemaTest {

    @Test
    fun `every core step declares the arguments its payload carries`() {
        val registry = CoreStepRegistryFactory.registry()

        registry.keys().forEach { key ->
            val definition = registry.definition(key) ?: fail("the registry names a key it does not define: $key")
            val codec = definition.contract.inputCodec
            val schema = Json.parseToJsonElement(codec.schema()).jsonObject
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            val open = schema["additionalProperties"] != null

            val payload = payloadOf(key.value, properties)
            val decoded = codec.decode(EncodedStepValue(Json.encodeToString(JsonObject.serializer(), payload)))
            val encoded = Json.parseToJsonElement(encode(codec, decoded).value).jsonObject

            encoded.keys.forEach { name ->
                if (name == "kind" && !properties.containsKey("kind")) {
                    return@forEach
                }
                assertTrue(
                    properties.containsKey(name),
                    "the schema of $key declares the argument '$name' that its payload carries",
                )
            }
            required.forEach { name ->
                assertTrue(
                    encoded.containsKey(name),
                    "the schema of $key requires '$name' and its payload carries it",
                )
            }
            if (!open) {
                properties.keys.forEach { name ->
                    if (encoded.containsKey(name)) {
                        return@forEach
                    }
                    assertTrue(
                        name in required,
                        "the schema of $key declares '$name' as an argument its payload does not carry, " +
                            "and an argument that is not there cannot be required",
                    )
                }
            }
        }
    }

    @Test
    fun `the required arguments of a core step are the ones its decoder refuses without`() {
        val registry = CoreStepRegistryFactory.registry()

        registry.keys().forEach { key ->
            val definition = registry.definition(key) ?: fail("the registry names a key it does not define: $key")
            val codec = definition.contract.inputCodec
            val schema = Json.parseToJsonElement(codec.schema()).jsonObject
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            val payload = payloadOf(key.value, properties)

            properties.keys.forEach { name ->
                val without = JsonObject(payload.filterKeys { it != name })
                val refused = try {
                    codec.decode(EncodedStepValue(Json.encodeToString(JsonObject.serializer(), without)))
                    false
                } catch (refusedByTheDecoder: RuntimeException) {
                    true
                }
                if (name in required) {
                    assertTrue(
                        refused,
                        "the schema of $key requires '$name' and its decoder refuses a payload without it",
                    )
                } else {
                    assertFalse(
                        refused,
                        "the schema of $key declares '$name' as optional and its decoder accepts a " +
                            "payload without it",
                    )
                }
            }
        }
    }

    @Test
    fun `every core step declares a schema of its own`() {
        val registry = CoreStepRegistryFactory.registry()

        val undeclared = registry.keys().map { it.value }.filter { key ->
            val codec = registry.definition(PluginStepId(key))?.contract?.inputCodec
            codec == null || StepSchema.declared(codec.schema()) == null
        }

        assertEquals(
            emptyList<String>(),
            undeclared,
            "every step of the core declares the arguments it accepts: a step that says nothing " +
                "publishes no schema, and a reader cannot tell it from a step that takes no arguments",
        )
    }

    /**
     * The encoding of a value the codec decoded.
     *
     * The registry holds the codecs with the type of their payload erased, and
     * the erasure is what the walk needs: the value that comes out of the
     * decoder is the one the encoder takes.
     *
     * @param codec the codec of the step.
     * @param value the value the decoder answered.
     * @return the encoding of that value.
     */
    @Suppress("UNCHECKED_CAST")
    private fun encode(codec: StepCodec<*>, value: Any): EncodedStepValue =
        (codec as StepCodec<Any>).encode(value)

    /**
     * The payload the schema describes, built from the schema itself.
     *
     * @param key the key of the step.
     * @param properties the arguments the schema declares.
     * @return the payload, as the JSON the codec decodes.
     */
    private fun payloadOf(key: String, properties: JsonObject): JsonObject = buildJsonObject {
        DISCRIMINANT[key]?.let { put("kind", JsonPrimitive(it)) }
        properties.forEach { (name, property) -> put(name, sampleOf(key, name, property)) }
    }

    /**
     * The value a declared argument carries in the payload.
     *
     * @param key the key of the step.
     * @param name the name of the argument.
     * @param property the schema of the argument.
     * @return the value, as the JSON the payload carries.
     */
    private fun sampleOf(key: String, name: String, property: JsonElement): JsonElement {
        SAMPLES[key]?.get(name)?.let { return Json.parseToJsonElement(it) }
        return when (property.jsonObject["type"]?.jsonPrimitive?.content) {
            "string" -> JsonPrimitive("value")
            "boolean" -> JsonPrimitive(true)
            "integer" -> JsonPrimitive(1)
            "number" -> JsonPrimitive(1.0)
            "array" -> JsonArray(emptyList())
            "object" -> JsonObject(emptyMap())
            else -> fail("the schema of $key declares the argument '$name' with an unknown type")
        }
    }

    companion object {

        /**
         * The discriminant the payload of each step carries.
         *
         * It is NOT an argument: the caller does not pass it, the step writes
         * it. A step whose payload carries none has no entry here.
         */
        private val DISCRIMINANT: Map<String, String> = mapOf(
            "core.echo" to "echo",
            "core.error" to "error",
            "core.sleep" to "sleep",
            "core.file.writeFile" to "writeFile",
            "core.readFile" to "readFile",
            "core.fileExists" to "fileExists",
            "core.archiveArtifacts" to "archiveArtifacts",
            "core.artifact.query" to "artifactQuery",
            "core.param" to "param",
            "core.pwd" to "pwd",
            "core.deleteDir" to "deleteDir",
            "core.cleanWs" to "cleanWs",
            "core.milestone" to "milestone",
            "core.waitUntil" to "waitUntil",
            "core.stash" to "stash",
            "core.unstash" to "unstash",
            "core.publishHTML" to "publishHTML",
            "core.sh" to "shell",
        )

        /**
         * The values a declared argument has to carry for the codec to accept
         * the payload.
         *
         * They are the arguments whose value is a closed name the codec
         * refuses when it does not know it, and the one the codec refuses
         * when it is true.
         */
        private val SAMPLES: Map<String, Map<String, String>> = mapOf(
            "core.error" to mapOf("failureKind" to "\"USER\""),
            "core.sh" to mapOf("returnMode" to "\"NONE\""),
            "core.pwd" to mapOf("tmp" to "false"),
        )
    }
}
