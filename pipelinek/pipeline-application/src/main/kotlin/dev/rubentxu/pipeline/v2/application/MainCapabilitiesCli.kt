package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RuntimeApiVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.PrintStream

/**
 * `pipeline capabilities` subcommand.
 * Emits the SDK capability registry on stdout as a single line of
 * `pipelinek.capabilities/2` JSON. The output is side-effect-free:
 * no run is started, no state is mutated, no events are written.
 * Exit code 0 always (a consumer can rely on the subcommand returning
 * 0 even when the list is empty).
 *
 * ## Why the schema version moved
 *
 * The first version of this document published the delivered capabilities under
 * a status spelled in one language while the two other statuses were spelled
 * in another: a consumer that reads the status by value had to know that one
 * value of a three-value vocabulary came from elsewhere, and the ones spelled
 * that way were exactly the ones the build delivers. The vocabulary is now
 * spelled in one language, and the document says so by moving to version 2.
 * A consumer that pinned version 1 learns from the schema field that the
 * vocabulary moved, instead of silently failing to recognise the capabilities
 * it was told are delivered.
*/
object MainCapabilitiesCli {
    const val SCHEMA: String = "pipelinek.capabilities/2"

    /**
     * The published document, as a value.
     *
     * It is a function and not a block inside [main] so that the contract can
     * be READ: the status a consumer receives is a string in this document,
     * and a vocabulary that only the code knows about is not a vocabulary.
     */
    fun document(): JsonObject = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA))
        put(
            "version",
            JsonPrimitive(RuntimeApiVersion.readImplementationVersion() ?: "unknown"),
        )
        put(
            "capabilities",
            JsonArray(
                SdkCapabilities.ALL.map { entry ->
                    buildJsonObject {
                        put("id", JsonPrimitive(entry.id))
                        put("status", JsonPrimitive(entry.status.name))
                        put("firstPublished", JsonPrimitive(entry.firstPublished))
                    }
                },
            ),
        )
    }

    /**
     * Runs the verb and writes what it answers to the streams it is given.
     *
     * The destination is a PARAMETER and not a global: a caller that wants to read what this
     * verb answers hands it its own streams, and what it reads is the same bytes the CLI shows.
     *
     * @return 0 on success (always).
     */
    fun main(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val admission = CliOptionAdmission.admit(args, NO_ARGUMENTS_REQUEST)
        when (admission) {
            is CliAdmission.Rejected -> {
                err.println(admission.error.refusalLines("capabilities").joinToString("\n"))
                return CliExit.INVALID_ARGUMENT.code
            }
            is CliAdmission.Admitted -> Unit
        }
        val line = Json { prettyPrint = false; encodeDefaults = true }
            .encodeToString(JsonObject.serializer(), document()) + "\n"
        out.write(line.toByteArray(Charsets.UTF_8))
        out.flush()
        return CliExit.SUCCESS.code
    }
}
