package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RunConfiguration
import dev.rubentxu.pipeline.v2.runtime.RunConfigurationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the snapshot of the configuration of a run.
 *
 * ## What the snapshot is
 *
 * A document the SERVICE wrote in the supervision's own directory, with the
 * values the client delivered and the schema of the document in it. The
 * child reads it once, at its start, and the values become the environment
 * of the steps of the run.
 *
 * ## Why the reader VALIDATES what it reads
 *
 * Because the disk is not an authority. The file was written by the service
 * and it sits on the disk, where anything can be: a file that does not
 * carry the schema it claims, or that breaks a rule of the configuration,
 * refuses the run instead of being believed. The rules are the SAME rules
 * the service applied, and they live in ONE place — the type of the
 * configuration — so the reader and the service cannot disagree.
 *
 * ## Why the reader FAILS CLOSED
 *
 * Because a run that was given a configuration and did not get it is a run
 * that ran with an environment nobody asked for. The refusal is the
 * product's own: exit code 2, the code of an argument that was not
 * understood, because that is what a snapshot that cannot be read is.
 */
internal object RunConfigurationReader {

    /** The schema the snapshot carries. */
    const val SCHEMA: String = "pipelinek.runconfig/1"

    /**
     * The configuration of [path], or the refusal that there is none.
     *
     * A `null` [path] is the configuration that carries nothing, and it is
     * NOT a refusal: a run that was given no configuration is the run that
     * always was.
     */
    fun read(path: String?): RunConfiguration {
        if (path == null) return RunConfiguration.EMPTY
        val file = Path.of(path)
        val document = readDocument(file)
        val schema = document.text("schema")
        if (schema != SCHEMA) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration speaks schema '$schema' and " +
                        "this build reads '$SCHEMA'",
                ),
            )
        }
        val values = document.values()
            ?: throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration does not carry a set of values",
                ),
            )
        return when (val outcome = RunConfiguration.of(values)) {
            is RunConfigurationOutcome.Accepted -> outcome.configuration
            is RunConfigurationOutcome.Refused -> throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration is refused: ${outcome.refusal.reason} " +
                        "(${outcome.refusal.detail})",
                ),
            )
        }
    }

    /**
     * The document [file] holds.
     *
     * A file that cannot be read, or that is not a JSON object, refuses the
     * run: the refusal names the file, because the file is what the
     * operator has to fix.
     */
    private fun readDocument(file: Path): JsonObject {
        val text = try {
            Files.readString(file)
        } catch (failure: Exception) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration could not be read: " +
                        (failure.message ?: failure.javaClass.simpleName),
                ),
            )
        }
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration is not a document this build reads",
                ),
            )
        }
        return parsed as? JsonObject
            ?: throw CommandRefused(
                CliExit.InvalidArgument(
                    message = "the snapshot of the configuration is not a document this build reads",
                ),
            )
    }

    /**
     * The values the document carries, or null when it carries none it can read.
     *
     * An absent field is an EMPTY set of values, and a field that is not an
     * object of primitives is none at all: a value that cannot be read is
     * not a value that may be applied.
     */
    private fun JsonObject.values(): Map<String, String>? {
        val element = this["values"] ?: return emptyMap()
        val object_ = element as? JsonObject ?: return null
        val values = LinkedHashMap<String, String>()
        for ((name, value) in object_) {
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
 * The document a snapshot of a configuration is written as.
 *
 * It is here so that the WRITER and the READER agree on the shape without
 * sharing a mutable thing: the writer is the service, the reader is the
 * child, and they are two processes that never meet.
 */
internal fun runConfigurationDocument(values: Map<String, String>): JsonObject = buildJsonObject {
    put("schema", JsonPrimitive(RunConfigurationReader.SCHEMA))
    put(
        "values",
        buildJsonObject {
            values.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        },
    )
}
