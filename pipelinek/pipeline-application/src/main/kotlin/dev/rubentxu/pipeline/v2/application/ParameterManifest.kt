package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The manifest an operator approves for a script: the declaration of its parameters, bound to
 * the bytes of the script it was taken from.
 *
 * ## What a manifest is
 *
 * A JSON document that sits next to the script, as `<script>.parameters.json`, with exactly four
 * fields:
 *
 * ```json
 * {
 *   "schema": "pipelinek.parameter-manifest/1",
 *   "scriptSha256": "<SHA-256 of the script's bytes>",
 *   "contract": [ { "name": "target", "type": "string" }, ... ],
 *   "contractSha256": "<the digest ServiceCodec.parameterContractDigest names the contract with>"
 * }
 * ```
 *
 * `pipelinek validate --parameter-manifest <script>` writes it, after compiling the script on
 * the operator's side; the service reads it and never compiles anything to describe a script.
 *
 * ## Why it is a closed shape
 *
 * Because a field this build does not know is a field whose meaning nobody approved: a manifest
 * that carried one is refused as unreadable, and not read with the field ignored. The same holds
 * for every parameter of the contract.
 *
 * ## What it does not carry
 *
 * The script. The manifest is the declaration and two digests, so nothing of the script's body
 * —a literal, a credential written in it— reaches the document a client is described from.
 */
internal object ParameterManifest {

    /** The schema of the document this build writes and reads. */
    const val SCHEMA: String = "pipelinek.parameter-manifest/1"

    /** What the name of a script is followed by to name its manifest. */
    const val SUFFIX: String = ".parameters.json"

    /** The largest manifest this build reads: a declaration is a form, not a file of data. */
    const val MAX_BYTES: Long = 1L shl 20

    /** The manifest of [contract], approved for the script whose bytes have [scriptSha256]. */
    fun encode(scriptSha256: String, contract: List<ParameterSpec>): String {
        val document = buildJsonObject {
            put(FIELD_SCHEMA, JsonPrimitive(SCHEMA))
            put(FIELD_SCRIPT, JsonPrimitive(scriptSha256))
            put(FIELD_CONTRACT, ServiceCodec.encodeParameterContract(contract))
            put(FIELD_CONTRACT_DIGEST, JsonPrimitive(ServiceCodec.parameterContractDigest(contract)))
        }
        return PRETTY.encodeToString(JsonObject.serializer(), document)
    }

    /**
     * The manifest [text] is, or why it is none.
     *
     * The reading checks the SHAPE and nothing else: whether the contract is the one its digest
     * names, and whether the script is the one the manifest was approved for, is the business of
     * whoever reads the manifest for a script.
     */
    fun read(text: String): ParameterManifestReading {
        val document = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return ParameterManifestReading.Unreadable("the manifest is not a JSON document")
        } catch (_: IllegalArgumentException) {
            return ParameterManifestReading.Unreadable("the manifest is not a JSON document")
        }
        val fields = document as? JsonObject
            ?: return ParameterManifestReading.Unreadable("the manifest is not a JSON object")
        if (fields.keys != FIELDS) {
            return ParameterManifestReading.Unreadable(
                "the manifest carries the fields ${fields.keys.sorted()}, and a manifest carries " +
                    "exactly ${FIELDS.sorted()}",
            )
        }
        val schema = fields.text(FIELD_SCHEMA)
        if (schema != SCHEMA) {
            return ParameterManifestReading.Unreadable(
                "the manifest is of the schema '$schema', and this build reads '$SCHEMA'",
            )
        }
        val scriptSha256 = fields.digest(FIELD_SCRIPT)
            ?: return ParameterManifestReading.Unreadable("'$FIELD_SCRIPT' is not a SHA-256 in lowercase hexadecimal")
        val contractSha256 = fields.digest(FIELD_CONTRACT_DIGEST)
            ?: return ParameterManifestReading.Unreadable(
                "'$FIELD_CONTRACT_DIGEST' is not a SHA-256 in lowercase hexadecimal",
            )
        val entries = fields[FIELD_CONTRACT] as? JsonArray
            ?: return ParameterManifestReading.Unreadable("'$FIELD_CONTRACT' is not a list of parameters")
        val contract = ArrayList<ParameterSpec>(entries.size)
        for ((position, entry) in entries.withIndex()) {
            val spec = try {
                Json.decodeFromJsonElement(ParameterSpec.serializer(), entry)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            contract += spec ?: return ParameterManifestReading.Unreadable(
                "the parameter at position $position of '$FIELD_CONTRACT' is not one this build reads: " +
                    "a parameter carries a 'name' and a 'type' that are not blank, and maybe a " +
                    "'defaultValue' and 'choices' that are texts",
            )
        }
        return ParameterManifestReading.Read(ApprovedParameterManifest(scriptSha256, contract, contractSha256))
    }

    /**
     * The SHA-256 of the bytes of [script], in lowercase hexadecimal.
     *
     * Read in blocks, so a script is never held whole to be digested.
     *
     * @throws java.io.IOException when the script cannot be read.
     */
    fun digestOf(script: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(script).use { input ->
            val block = ByteArray(BLOCK_BYTES)
            while (true) {
                val read = input.read(block)
                if (read < 0) break
                digest.update(block, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.text(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.digest(field: String): String? =
        text(field)?.takeIf { DIGEST.matches(it) }

    private const val FIELD_SCHEMA: String = "schema"
    private const val FIELD_SCRIPT: String = "scriptSha256"
    private const val FIELD_CONTRACT: String = "contract"
    private const val FIELD_CONTRACT_DIGEST: String = "contractSha256"
    private const val BLOCK_BYTES: Int = 8192

    private val FIELDS: Set<String> = setOf(FIELD_SCHEMA, FIELD_SCRIPT, FIELD_CONTRACT, FIELD_CONTRACT_DIGEST)
    private val DIGEST: Regex = Regex("[0-9a-f]{64}")
    private val PRETTY: Json = Json { prettyPrint = true }
}

/** A manifest as it was read: the digest of its script, its declaration and the digest it names that declaration with. */
internal data class ApprovedParameterManifest(
    val scriptSha256: String,
    val contract: List<ParameterSpec>,
    val contractSha256: String,
)

/** What the reading of a manifest found. */
internal sealed interface ParameterManifestReading {

    /** The text is a manifest of this build's shape. */
    data class Read(val manifest: ApprovedParameterManifest) : ParameterManifestReading

    /** The text is not, and [detail] says what it is instead. */
    data class Unreadable(val detail: String) : ParameterManifestReading
}
