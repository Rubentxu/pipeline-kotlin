package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PK-1 / ADR-PK1-IDENTITY — `pipeline capabilities` subcommand.
 *
 * Emits the SDK capability registry on stdout as a single line of
 * `pipelinek.capabilities/1` JSON. The output is side-effect-free:
 * no run is started, no state is mutated, no events are written.
 *
 * Exit code 0 always (a consumer can rely on the subcommand returning
 * 0 even when the list is empty).
 */
object MainCapabilitiesCli {
    const val SCHEMA: String = "pipelinek.capabilities/1"

    /**
     * @return 0 on success (always).
     */
    @Suppress("UnusedParameter") // args is required by the JVM main signature.
    fun main(args: Array<String>): Int {
        val obj: JsonObject = buildJsonObject {
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
        val line = Json { prettyPrint = false; encodeDefaults = true }
            .encodeToString(JsonObject.serializer(), obj) + "\n"
        System.out.write(line.toByteArray(Charsets.UTF_8))
        System.out.flush()
        return 0
    }
}

