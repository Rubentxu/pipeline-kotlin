package dev.rubentxu.pipeline.v2.runtime

import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A question of `core.input` as it sits in the control directory.
 *
 * It is what the run PUBLISHES, and it is what anything that lists the pending questions of a
 * run reads. It carries the run and the operation it belongs to, because a directory name is a
 * key and not an identity: whoever lists the questions needs to know which run each one is
 * about without parsing a path.
 *
 * [expiresAtMs] is the moment the run stops waiting, or `null` when the wait has no bound. A
 * question past it can no longer be answered: the run that asked has already denied it.
 */
data class PublishedInputQuestion(
    val runId: String,
    val opId: String,
    val message: String,
    val ok: String,
    /** Attribution declared by the pipeline author. It does not authorize anybody. */
    val submitter: String?,
    val id: String?,
    val publishedAtMs: Long,
    val expiresAtMs: Long?,
)

/**
 * The wire of a published question: one place that writes it and one place that reads it.
 *
 * The document is built with a JSON writer and never by concatenation, because the message is
 * text the pipeline author wrote and it may hold a line break, a quote or a control character.
 * A question whose file is not valid JSON is a question nobody can answer.
 */
object InputQuestionCodec {

    /** The JSON document of [question]. */
    fun encode(question: PublishedInputQuestion): String {
        val obj: JsonObject = buildJsonObject {
            put("runId", JsonPrimitive(question.runId))
            put("opId", JsonPrimitive(question.opId))
            put("message", JsonPrimitive(question.message))
            put("ok", JsonPrimitive(question.ok))
            put("submitter", question.submitter?.let { JsonPrimitive(it) } ?: JsonNull)
            put("id", question.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("publishedAtMs", JsonPrimitive(question.publishedAtMs))
            put("expiresAtMs", question.expiresAtMs?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * The question [raw] holds, or `null` when it is not a complete one.
     *
     * `null` is the answer for a document that is not JSON, for one that lacks a mandatory
     * field and for one written by a build that did not know the run: none of them is a
     * question that can be shown to somebody who is about to answer it.
     */
    fun decode(raw: String): PublishedInputQuestion? {
        val obj = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return null
        }
        return try {
            PublishedInputQuestion(
                runId = obj.text("runId") ?: return null,
                opId = obj.text("opId") ?: return null,
                message = obj.text("message") ?: return null,
                ok = obj.text("ok") ?: return null,
                submitter = obj.text("submitter"),
                id = obj.text("id"),
                publishedAtMs = obj["publishedAtMs"]?.jsonPrimitive?.longOrNull ?: return null,
                expiresAtMs = obj["expiresAtMs"]?.jsonPrimitive?.longOrNull,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.text(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
}

/**
 * Where the files of a question live under the control directory of questions.
 *
 * ```text
 * <root>/<directoryName(opId)>/request.json    written by the run
 * <root>/<directoryName(opId)>/response.json   written by whoever answers
 * ```
 */
object InputQuestionLayout {

    /** The file the run writes the question in. */
    const val REQUEST_FILE: String = "request.json"

    /** The file the answer is written in. */
    const val RESPONSE_FILE: String = "response.json"

    /**
     * The directory name of the question of [opId].
     *
     * The mapping is INJECTIVE: two operations never share a directory, because two questions
     * that shared one would share an answer. Every byte outside `[A-Za-z0-9_-]` is written as
     * `~` and two hexadecimal digits, `~` itself included, and a dot is escaped when it opens
     * the name so that no operation id can name `.` or `..`. A name too long for a file system
     * keeps its beginning and ends in a digest of the whole identity.
     */
    fun directoryName(opId: String): String {
        val escaped = buildString {
            opId.toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
                val c = byte.toInt() and BYTE_MASK
                val plain = c < ASCII_LIMIT && (
                    c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' ||
                        c.toChar() == '_' || c.toChar() == '-' || (c.toChar() == '.' && index > 0)
                    )
                if (plain) append(c.toChar()) else append(ESCAPE).append(UPPER_HEX.toHexDigits(byte))
            }
        }.ifEmpty { ESCAPE.toString() }
        if (escaped.length <= MAX_NAME_LENGTH) return escaped
        val digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(opId.toByteArray(Charsets.UTF_8)),
        )
        return escaped.take(KEPT_PREFIX_LENGTH) + "$ESCAPE$ESCAPE" + digest.take(DIGEST_LENGTH)
    }

    private const val ESCAPE: Char = '~'
    private val UPPER_HEX: HexFormat = HexFormat.of().withUpperCase()
    private const val BYTE_MASK: Int = 0xFF
    private const val ASCII_LIMIT: Int = 0x80
    private const val MAX_NAME_LENGTH: Int = 200
    private const val KEPT_PREFIX_LENGTH: Int = 120
    private const val DIGEST_LENGTH: Int = 40
}
