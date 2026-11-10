package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the snapshot of the PARAMETERS of a run.
 *
 * ## What the snapshot is
 *
 * A document the SERVICE wrote in the supervision's own directory, with the values the
 * client delivered and the schema of the document in it. The child reads it once, at its
 * start, and checks the values against the DECLARATION of the script —which is the truth,
 * because the child compiles the script.
 *
 * ## Why the reader does NOT check the values against a declaration
 *
 * Because the reader does not have one: the declaration of the script is a product of the
 * compilation, and the compilation happens in the child. The reader checks the SHAPE of the
 * document —the schema it claims and the shape of its values— and the rules of the values
 * are applied afterwards, against the declaration that the compilation produced.
 *
 * ## Why the reader FAILS CLOSED
 *
 * Because a run that was given parameters and did not get them is a run that ran with
 * values nobody delivered. The refusal is the product's own: exit code 2, the code of an
 * argument that was not understood, because that is what a snapshot that cannot be read is.
 */
internal object RunInputsReader {

    /** The schema the snapshot carries. */
    const val SCHEMA: String = "pipelinek.runinputs/1"

    /**
     * The values [path] carries, or the refusal that there are none.
     *
     * A `null` [path] is the set of values that carries nothing, and it is NOT a refusal: a
     * run that was given no parameters is the run that always was.
     */
    fun read(path: String?): Map<String, String> {
        if (path == null) return emptyMap()
        val file = Path.of(path)
        val document = readDocument(file)
        val schema = document.text("schema")
        if (schema != SCHEMA) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the parameters speaks schema '$schema' and " +
                        "this build reads '$SCHEMA'",
                ),
            )
        }
        return document.values()
            ?: throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the parameters does not carry a set of values",
                ),
            )
    }

    /**
     * The document [file] holds.
     *
     * A file that cannot be read, or that is not a JSON object, refuses the run: the refusal
     * names the file, because the file is what the operator has to fix.
     */
    private fun readDocument(file: Path): JsonObject {
        val text = try {
            Files.readString(file)
        } catch (failure: Exception) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the parameters could not be read: " +
                        (failure.message ?: failure.javaClass.simpleName),
                ),
            )
        }
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the parameters is not a document this build reads",
                ),
            )
        }
        return parsed as? JsonObject
            ?: throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the parameters is not a document this build reads",
                ),
            )
    }

    /**
     * The values the document carries, or null when it carries none it can read.
     *
     * An absent field is an EMPTY set of values, and a field that is not an object of
     * primitives is none at all: a value that cannot be read is not a value that may be
     * applied.
     */
    private fun JsonObject.values(): Map<String, String>? {
        val element = this["values"] ?: return emptyMap()
        val entries = element as? JsonObject ?: return null
        val values = LinkedHashMap<String, String>()
        for ((name, value) in entries) {
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return null
            values[name] = text
        }
        return values
    }

    /** The text [key] holds, or null when it holds none. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * The document a snapshot of the parameters is written as.
 *
 * It is here so that the WRITER and the READER agree on the shape without sharing a mutable
 * thing: the writer is the service, the reader is the child, and they are two processes that
 * never meet.
 */
internal fun runInputsDocument(values: Map<String, String>): JsonObject = buildJsonObject {
    put("schema", JsonPrimitive(RunInputsReader.SCHEMA))
    put(
        "values",
        buildJsonObject {
            values.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        },
    )
}
