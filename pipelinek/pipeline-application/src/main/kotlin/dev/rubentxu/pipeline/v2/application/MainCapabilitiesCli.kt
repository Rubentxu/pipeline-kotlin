package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
     * @return 0 on success (always).
*/
    @Suppress("UnusedParameter") // args is required by the JVM main signature.
    fun main(args: Array<String>): Int {
        val line = Json { prettyPrint = false; encodeDefaults = true }
            .encodeToString(JsonObject.serializer(), document()) + "\n"
        System.out.write(line.toByteArray(Charsets.UTF_8))
        System.out.flush()
        return 0
    }
}
