package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RuntimeApiVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.PrintStream

/**
 * `pipeline sdk-info` subcommand.
 * Emits the SDK manifest the current build was compiled against on
 * stdout as a single line of `pipelinek.sdk/1` JSON. The output is
 * side-effect-free.
 * The list of [COORDINATES] is the contract declared in
 * `pipelinek/build.gradle.kts` (`publishedContractModules` + the BOM). A
 * consumer that parses this line can verify, by external observation
 * of the resolved artifacts, that the build matches the expected
 * contract.
*/
object MainSdkInfoCli {
    const val SCHEMA: String = "pipelinek.sdk/1"

    /**
     * The 5 SDK coordinates + the BOM.
     *
     * They are READ from the generated source, which the build writes from the one
     * declaration of the published coordinate (the root build's `sdkBundleGroup` and
     * `sdkBundleArtifactNames`). This list used to be written here as well, and that
     * was a second authority over the same fact: the rename of the coordinates would
     * have had to be applied in two places, and the one left behind would have
     * published a CLI that sends consumers after artifacts that do not exist.
     */
    private val COORDINATES: List<Coordinate> =
        SdkCoordinates.ENTRIES.map { Coordinate(it.group, it.artifact, it.scope) }

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
                err.println(admission.error.refusalLines("sdk-info").joinToString("\n"))
                return CliExit.INVALID_ARGUMENT.code
            }
            is CliAdmission.Admitted -> Unit
        }
        val version = RuntimeApiVersion.readImplementationVersion() ?: "unknown"
        val obj: JsonObject = buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA))
            put("version", JsonPrimitive(version))
            put("group", JsonPrimitive(SdkCoordinates.GROUP))
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
        out.write(line.toByteArray(Charsets.UTF_8))
        out.flush()
        return CliExit.SUCCESS.code
    }

    /** One SDK coordinate as exposed by the subcommand. */
    data class Coordinate(
        val group: String,
        val artifact: String,
        val scope: String,
    )
}
