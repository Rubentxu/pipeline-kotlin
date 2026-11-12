package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The coordinates `pipelinek sdk-info` publishes are a CONTRACT: a consumer reads
 * them to know what to depend on. A row that only checks the schema would let the
 * coordinates drift without anything noticing, and the drift is invisible until a
 * consumer follows it into a coordinate that does not exist.
 *
 * The list this test pins is the one the build generates from the single
 * declaration of the published coordinate. The test therefore does not restate the
 * coordinate as a fact of its own: it pins the SHAPE of the answer (eight entries, one
 * group, the BOM as the platform) and the NAME the solution publishes under, which is
 * the thing a rename has to carry.
 */
class MainSdkInfoCoordinatesTest {
    @Test
    fun `the published coordinates carry the name of the solution and one group`() {
        val answer = sdkInfo()
        val coordinates = answer["coordinates"]!!.jsonArray

        assertEquals(8, coordinates.size, "the SDK publishes seven contracts and the BOM: $answer")

        val groups = coordinates.map { it.jsonObject["group"]!!.jsonPrimitive.content }.distinct()
        assertEquals(1, groups.size, "every published coordinate carries the same group: $answer")
        assertEquals("dev.rubentxu.pipelinek", groups.single(), "the group names the solution: $answer")

        val artifacts = coordinates.map { it.jsonObject["artifact"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "pipelinek-domain",
                "pipelinek-events",
                "pipelinek-output",
                "pipelinek-runtime-api",
                "pipelinek-scripting-api",
                "pipelinek-scripting-definition",
                "pipelinek-service-client",
                "pipelinek-sdk-bom",
            ),
            artifacts,
            "the published artifacts carry the name of the solution: $answer",
        )

        val scopes = coordinates.map { it.jsonObject["scope"]!!.jsonPrimitive.content }
        assertEquals("bom", scopes.last(), "the BOM is the platform a consumer pins: $answer")
        assertTrue(
            scopes.dropLast(1).all { it == "implementation" },
            "the seven contracts are the ones a consumer compiles against: $answer",
        )
    }

    @Test
    fun `every published coordinate carries the version the CLI reports`() {
        val answer = sdkInfo()
        val version = answer["version"]!!.jsonPrimitive.content

        answer["coordinates"]!!.jsonArray.forEach { coordinate ->
            assertEquals(
                version,
                coordinate.jsonObject["version"]!!.jsonPrimitive.content,
                "a coordinate that carries another version sends the consumer after another artifact: $answer",
            )
        }
    }

    private fun sdkInfo(): JsonObject {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val exitCode = MainSdkInfoCli.main(arrayOf(), out, err)
        out.flush()
        err.flush()

        assertEquals(0, exitCode, "sdk-info refused: ${errBytes.toString(Charsets.UTF_8)}")
        return Json.parseToJsonElement(outBytes.toString(Charsets.UTF_8)).jsonObject
    }
}
