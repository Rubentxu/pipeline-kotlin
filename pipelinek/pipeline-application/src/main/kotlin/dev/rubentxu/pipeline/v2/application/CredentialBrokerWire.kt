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
 * The contract `pipelinek.credentials/1`: what a run and its credential broker
 * say to each other over a private socket.
 *
 * ## The shape
 *
 * One request per connection, one line each way. The run sends
 * `{schema, token, op:"resolve", credentialId, kind}` and the broker answers
 * `{schema, ok:true, kind, value}` — the value is the secret, Base64 — or
 * `{schema, ok:false, refusal}` where the refusal is one of the fixed words of
 * [CredentialBrokerRefusal].
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
    const val SCHEMA: String = "pipelinek.credentials/1"

    /** The only operation: ask for a credential. */
    const val OPERATION_RESOLVE: String = "resolve"

    /** The longest request line the broker reads, in bytes. */
    const val MAX_REQUEST_LINE_BYTES: Int = 1024

    /** The biggest secret the broker hands out, in bytes. */
    const val MAX_SECRET_BYTES: Int = 64 * 1024

    /** The longest answer line a run reads: the largest secret in Base64, plus the envelope. */
    const val MAX_RESPONSE_LINE_BYTES: Int = 4 * ((MAX_SECRET_BYTES + 2) / 3) + 512

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

    /** The request line asking for [credentialId] as [kind], on behalf of the session [token]. */
    fun encodeRequest(token: String, credentialId: String, kind: BrokeredCredentialKind): String =
        buildJsonObject {
            put("schema", SCHEMA)
            put("token", token)
            put("op", OPERATION_RESOLVE)
            put("credentialId", credentialId)
            put("kind", kind.word)
        }.toString()

    /** The answer line that hands out [secret] as [kind]. */
    fun encodeGranted(kind: BrokeredCredentialKind, secret: ByteArray): String =
        buildJsonObject {
            put("schema", SCHEMA)
            put("ok", true)
            put("kind", kind.word)
            put("value", Base64.getEncoder().encodeToString(secret))
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
        val kindWord = textOf(fields, "kind") ?: return RequestReading.Unreadable
        return RequestReading.Read(BrokerRequest(token, credentialId, kindWord))
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
        val secret = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return ResponseReading.Unreadable
        }
        if (secret.size > MAX_SECRET_BYTES) return ResponseReading.Unreadable
        return ResponseReading.Granted(kind, secret)
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
     * The kind stays the word it was written with: deciding whether the broker
     * carries it is the broker's job, and a request for a kind it does not
     * carry must be answered with that reason and not with "unreadable".
     */
    class BrokerRequest(
        /** The session token the run presents. */
        val token: String,
        /** The credential asked for. */
        val credentialId: String,
        /** The kind asked for, as written. */
        val kindWord: String,
    ) {
        /** The token is a credential of the run, so it is not printed. */
        override fun toString(): String = "BrokerRequest(credentialId=$credentialId, kind=$kindWord)"
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

        /** The broker handed out a secret. */
        class Granted(val kind: BrokeredCredentialKind, val secret: ByteArray) : ResponseReading {
            /** The secret is not printed, nor is any encoding of it. */
            override fun toString(): String = "Granted(kind=${kind.word}, secret=<${secret.size} bytes>)"
        }

        /** The broker said why it hands out nothing. */
        data class Refused(val refusal: CredentialBrokerRefusal) : ResponseReading

        /** The line was not an answer of this contract. */
        data object Unreadable : ResponseReading
    }
}
