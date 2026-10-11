package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PK-1 / ADR-PK1-IDENTITY — `pipeline sdk-info` subcommand.
 *
 * Emits the SDK manifest the current build was compiled against on
 * stdout as a single line of `pipelinek.sdk/1` JSON. The output is
 * side-effect-free.
 *
 * The list of [COORDINATES] is the contract declared in
 * `v2/build.gradle.kts` (`publishedContractModules` + the BOM). A
 * consumer that parses this line can verify, by external observation
 * of the resolved artifacts, that the build matches the expected
 * contract.
 */
object MainSdkInfoCli {
    const val SCHEMA: String = "pipelinek.sdk/1"

    /** The 5 SDK coordinates + the BOM, as declared in the publish script. */
    private val COORDINATES: List<Coordinate> = listOf(
        Coordinate(group = "dev.rubentxu.pipeline.v2", artifact = "pipeline-domain", scope = "implementation"),
        Coordinate(group = "dev.rubentxu.pipeline.v2", artifact = "pipeline-events", scope = "implementation"),
        Coordinate(group = "dev.rubentxu.pipeline.v2", artifact = "pipeline-output", scope = "implementation"),
        Coordinate(group = "dev.rubentxu.pipeline.v2", artifact = "pipeline-runtime-api", scope = "implementation"),
        Coordinate(group = "dev.rubentxu.pipeline.v2", artifact = "pipeline-scripting-api", scope = "implementation"),
        Coordinate(group = "dev.rubentxu.pipeline.v2", artifact = "pipeline-sdk-bom", scope = "bom"),
    )

    /**
     * @return 0 on success (always).
     */
    fun main(args: Array<String>): Int {
        val version = RuntimeApiVersion.readImplementationVersion() ?: "unknown"
        val obj: JsonObject = buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA))
            put("version", JsonPrimitive(version))
            put("group", JsonPrimitive("dev.rubentxu.pipeline.v2"))
            put(
                "coordinates",
                JsonArray(
                    COORDINATES.map { c ->
                        buildJsonObject {
                            put("group", JsonPrimitive(c.group))
                            put("artifact", JsonPrimitive(c.artifact))
                            put("version", JsonPrimitive(version))
                            put("scope", JsonPrimitive(c.scope))
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

    /** One SDK coordinate as exposed by the subcommand. */
    data class Coordinate(
        val group: String,
        val artifact: String,
        val scope: String,
    )
}
