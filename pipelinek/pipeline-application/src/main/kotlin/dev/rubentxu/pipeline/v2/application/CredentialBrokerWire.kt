package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/**
 * The contract `pipelinek.credentials/2`: what a run and its credential broker
 * say to each other over a private socket.
 *
 * ## What the SECOND version changed
 *
 * The value of an answer is the MATERIAL of the credential --the canonical form
 * that says what it is-- and not the bytes of one secret. A key and its
 * passphrase, a keystore and its password and an archive of several entries
 * are one credential, and the first version could only carry one secret.
 * The question lost its `kind` field for the same reason: the form is the
 * authorization's decision, and a question that carried it would be a second
 * authority for one decision.
 *
 * ## The shape
 *
 * One request per connection, one line each way. The run sends
 * `{schema, token, op:"resolve", credentialId}` and the broker answers
 * `{schema, ok:true, kind, value}` — the kind is the FORM the credential was
 * granted in, and the value is the material of the credential, Base64 — or
 * `{schema, ok:false, refusal}` where the refusal is one of the fixed words of
 * [CredentialBrokerRefusal].
 *
 * ## Why the question carries no kind
 *
 * Because the FORM of a credential is not the run's decision: it is the
 * authorization's. The operator's file names each credential once and says in
 * which form it may be handed over, and the run asks for the credential by
 * NAME. A question that also carried a form would be a second authority for
 * one decision, and the two would disagree the first time a run asked for a
 * form the operator had not written down.
 *
 * ## Why Base64 and one line
 *
 * A secret is bytes, and bytes may hold a newline, a NUL or a value no text
 * encoding keeps. A line that ends at the first newline cannot carry them raw,
 * and Base64 carries every byte back exactly.
 *
 * ## Reading is total and says nothing about what it read
 *
 * Both decoders accept ONLY a line of this contract and answer
 * [RequestReading.Unreadable] / [ResponseReading.Unreadable] for anything else.
 * They never throw and they never keep a fragment of the input: a line that was
 * almost an answer may hold the secret it was almost carrying, and a reason
 * built from it would print that secret.
 *
 * ## Where the secret can be a string
 *
 * Only in the line of an answer, and in the buffer of the process that reads
 * it. Every value that holds one redacts itself.
 */
internal object CredentialBrokerWire {

    /** The contract this wire speaks. */
    const val SCHEMA: String = "pipelinek.credentials/2"

    /** The only operation: ask for a credential. */
    const val OPERATION_RESOLVE: String = "resolve"

    /** The longest request line the broker reads, in bytes. */
    const val MAX_REQUEST_LINE_BYTES: Int = 1024

    /** The biggest secret the broker hands out, in bytes. */
    const val MAX_MATERIAL_BYTES: Int = 64 * 1024

    /** The longest answer line a run reads: the largest secret in Base64, plus the envelope. */
    const val MAX_RESPONSE_LINE_BYTES: Int = 4 * ((MAX_MATERIAL_BYTES + 2) / 3) + 512

    /** How many random bytes a session token holds. */
    private const val TOKEN_BYTES: Int = 32

    private val random: SecureRandom = SecureRandom()

    private val json: Json = Json

    /** A new session token: 32 random bytes, URL-safe, without padding. */
    fun newSessionToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** The request line asking for [credentialId], on behalf of the session [token]. */
    fun encodeRequest(token: String, credentialId: String): String =
        buildJsonObject {
            put("schema", SCHEMA)
            put("token", token)
            put("op", OPERATION_RESOLVE)
            put("credentialId", credentialId)
        }.toString()

    /** The answer line that hands out [material] as [kind]. */
    fun encodeGranted(kind: BrokeredCredentialKind, material: ByteArray): String =
        buildJsonObject {
            put("schema", SCHEMA)
            put("ok", true)
            put("kind", kind.word)
            put("value", Base64.getEncoder().encodeToString(material))
        }.toString()

    /** The answer line that says why nothing is handed out. */
    fun encodeRefused(refusal: CredentialBrokerRefusal): String =
        buildJsonObject {
            put("schema", SCHEMA)
            put("ok", false)
            put("refusal", refusal.word)
        }.toString()

    /** Reads [line] as a request, or as [RequestReading.Unreadable] when it is not one. */
    fun decodeRequest(line: String): RequestReading {
        val fields = objectOf(line) ?: return RequestReading.Unreadable
        if (textOf(fields, "schema") != SCHEMA || textOf(fields, "op") != OPERATION_RESOLVE) {
            return RequestReading.Unreadable
        }
        val token = textOf(fields, "token") ?: return RequestReading.Unreadable
        val credentialId = textOf(fields, "credentialId") ?: return RequestReading.Unreadable
        return RequestReading.Read(BrokerRequest(token, credentialId))
    }

    /** Reads [line] as an answer, or as [ResponseReading.Unreadable] when it is not one. */
    fun decodeResponse(line: String): ResponseReading {
        val fields = objectOf(line) ?: return ResponseReading.Unreadable
        if (textOf(fields, "schema") != SCHEMA) return ResponseReading.Unreadable
        val ok = (fields["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: return ResponseReading.Unreadable
        return if (ok) readGranted(fields) else readRefused(fields)
    }

    private fun readGranted(fields: JsonObject): ResponseReading {
        if ("refusal" in fields) return ResponseReading.Unreadable
        val kind = textOf(fields, "kind")?.let { BrokeredCredentialKind.ofWord(it) }
            ?: return ResponseReading.Unreadable
        val encoded = textOf(fields, "value") ?: return ResponseReading.Unreadable
        val material = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return ResponseReading.Unreadable
        }
        if (material.size > MAX_MATERIAL_BYTES) return ResponseReading.Unreadable
        return ResponseReading.Granted(kind, material)
    }

    private fun readRefused(fields: JsonObject): ResponseReading {
        if ("value" in fields) return ResponseReading.Unreadable
        val refusal = textOf(fields, "refusal")?.let { CredentialBrokerRefusal.ofWord(it) }
            ?: return ResponseReading.Unreadable
        return ResponseReading.Refused(refusal)
    }

    /** The JSON object [line] holds, or null when it holds anything else. */
    private fun objectOf(line: String): JsonObject? =
        try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: Exception) {
            null
        }

    /** The text of the string field [name], or null when it is absent or is not a string. */
    private fun textOf(fields: JsonObject, name: String): String? {
        val element: JsonElement = fields[name] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    /**
     * A request as the broker reads it.
     *
     * The request names the credential and nothing else: the FORM it is handed
     * over in is the authorization's decision, and a request that carried one
     * would be a second authority for the same fact.
     */
    class BrokerRequest(
        /** The session token the run presents. */
        val token: String,
        /** The credential asked for. */
        val credentialId: String,
    ) {
        /** The token is a credential of the run, so it is not printed. */
        override fun toString(): String = "BrokerRequest(credentialId=$credentialId)"
    }

    /** The result of reading a request line. */
    sealed interface RequestReading {

        /** The line was a request. */
        class Read(val request: BrokerRequest) : RequestReading {
            override fun toString(): String = "Read($request)"
        }

        /** The line was not a request of this contract. */
        data object Unreadable : RequestReading
    }

    /** The result of reading an answer line. */
    sealed interface ResponseReading {

        /** The broker handed out the material of a credential, in the form [kind]. */
        class Granted(val kind: BrokeredCredentialKind, val material: ByteArray) : ResponseReading {
            /** The material is not printed, nor is any encoding of it. */
            override fun toString(): String = "Granted(kind=${kind.word}, material=<${material.size} bytes>)"
        }

        /** The broker said why it hands out nothing. */
        data class Refused(val refusal: CredentialBrokerRefusal) : ResponseReading

        /** The line was not an answer of this contract. */
        data object Unreadable : ResponseReading
    }
}
