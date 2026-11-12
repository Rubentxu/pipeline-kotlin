package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The manifest an operator approves for a script, read back as the declaration it was written
 * with.
 *
 * ## The guarantee under trial
 *
 * A manifest carries the SHA-256 of the script it describes, the declaration of the script's
 * parameters and the digest of that declaration, and nothing else: the document is a closed
 * shape, so a field this build does not know is a manifest it cannot read, and not one whose
 * extra field is ignored. The digest of a declaration is injective, so two declarations that
 * differ in anything are never named by the same digest.
 *
 * ## Why each row is here
 *
 * - The trip proves the reader reads what the writer writes, in the declared order.
 * - The closed shape is tried with a field too many and a field too few, and with a text where
 *   a list belongs: each is a manifest this build cannot read.
 * - The digest is tried with the pairs a separator-joined text would confuse: a choice with a
 *   comma against two choices, an absent default against an empty one, and the same parameters
 *   in another order.
 * - The secret row proves the manifest carries the declaration and not the script: a literal
 *   of the script's body never reaches it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ParameterManifestTest {

    @Test
    fun `a manifest is read back as the declaration it was written with`() {
        val manifest = ParameterManifest.encode(SCRIPT_SHA, CONTRACT)

        val read = assertInstanceOf(ParameterManifestReading.Read::class.java, ParameterManifest.read(manifest))

        assertEquals(SCRIPT_SHA, read.manifest.scriptSha256)
        assertEquals(CONTRACT, read.manifest.contract, "the declaration comes back whole and in its order")
        assertEquals(ServiceCodec.parameterContractDigest(CONTRACT), read.manifest.contractSha256)
    }

    @Test
    fun `a manifest with a field this build does not know is not read`() {
        val document = documentOf(ParameterManifest.encode(SCRIPT_SHA, CONTRACT))
        val extended = JsonObject(document + ("source" to JsonPrimitive("println(1)")))

        assertUnreadable(extended.toString(), "a manifest with an extra field")
    }

    @Test
    fun `a manifest without one of its fields is not read`() {
        val document = documentOf(ParameterManifest.encode(SCRIPT_SHA, CONTRACT))
        for (field in document.keys) {
            assertUnreadable(JsonObject(document - field).toString(), "a manifest without '$field'")
        }
    }

    @Test
    fun `a manifest of another shape is not read`() {
        val document = documentOf(ParameterManifest.encode(SCRIPT_SHA, CONTRACT))

        assertUnreadable("not json", "a text that is not JSON")
        assertUnreadable("[]", "a list")
        assertUnreadable(
            JsonObject(document + ("schema" to JsonPrimitive("pipelinek.parameter-manifest/0"))).toString(),
            "a manifest of another schema",
        )
        assertUnreadable(
            JsonObject(document + ("contract" to JsonPrimitive("target"))).toString(),
            "a contract that is a text",
        )
        assertUnreadable(
            JsonObject(document + ("scriptSha256" to JsonPrimitive("not-a-digest"))).toString(),
            "a script digest that is not one",
        )
        val unquoted = document.toString().replace("\"string\"", "1")
        assertUnreadable(unquoted, "a type that is not a text")
        val blank = JsonObject(
            document + ("contract" to Json.parseToJsonElement("""[{"name":" ","type":"string"}]""")),
        )
        assertUnreadable(blank.toString(), "a parameter with a blank name")
        val extraKey = JsonObject(
            document + ("contract" to Json.parseToJsonElement("""[{"name":"a","type":"string","secret":"x"}]""")),
        )
        assertUnreadable(extraKey.toString(), "a parameter with a field this build does not know")
    }

    @Test
    fun `two declarations that differ in anything are not named by the same digest`() {
        val pairs = listOf(
            listOf(choice("mode", "a,b")) to listOf(choice("mode", "a", "b")),
            listOf(ParameterSpec("target", "string")) to listOf(ParameterSpec("target", "string", "")),
            listOf(ParameterSpec("a", "string"), ParameterSpec("b", "string")) to
                listOf(ParameterSpec("b", "string"), ParameterSpec("a", "string")),
            listOf(ParameterSpec("ab", "string")) to listOf(ParameterSpec("a", "bstring")),
        )
        for ((left, right) in pairs) {
            assertNotEquals(
                ServiceCodec.parameterContractDigest(left),
                ServiceCodec.parameterContractDigest(right),
                "$left and $right are two declarations, and the digest names them as one",
            )
        }
    }

    @Test
    fun `the manifest carries the declaration and not the script`(@TempDir root: Path) {
        val script = root.resolve("deploy.pipeline.kts")
        Files.writeString(script, "val token = \"$SECRET\"\npipeline { }\n")

        val manifest = ParameterManifest.encode(ParameterManifest.digestOf(script), CONTRACT)

        assertFalse(manifest.contains(SECRET), "a literal of the script's body reached the manifest: $manifest")
        assertEquals(
            setOf("schema", "scriptSha256", "contract", "contractSha256"),
            documentOf(manifest).keys,
            "the manifest carries its four fields and nothing else",
        )
    }

    @Test
    fun `the digest of a script is the SHA-256 of its bytes`(@TempDir root: Path) {
        val script = root.resolve("empty.pipeline.kts")
        Files.write(script, ByteArray(0))

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ParameterManifest.digestOf(script),
        )
    }

    private fun assertUnreadable(text: String, what: String) {
        assertInstanceOf(
            ParameterManifestReading.Unreadable::class.java,
            ParameterManifest.read(text),
            "$what was read as a manifest: $text",
        )
    }

    private fun documentOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val SCRIPT_SHA: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SECRET: String = "ghp_literal-of-the-body-0123456789"

        fun choice(name: String, vararg choices: String) = ParameterSpec(name, "choice", choices.first(), choices.toList())

        val CONTRACT: List<ParameterSpec> = listOf(
            ParameterSpec("target", "string"),
            ParameterSpec("dryRun", "boolean", "false"),
            ParameterSpec("region", "choice", "eu", listOf("eu", "us")),
        )
    }
}
