package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.output.OutputStreamId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: the identity a consumer reads is the operation's OWN, not a
 * name derived from where its bytes happen to live.
 *
 * The capacity this serves: a consumer that can address what it is told
 * about. The Output Plane keeps a stream's bytes under a directory whose name
 * is a folded projection of the stream's identity — every character outside
 * `[A-Za-z0-9._-]` becomes an underscore — so the identity on disk and the
 * identity of the thing are two different strings. A consumer that read the
 * directory could not tell one operation from another whose id folded the
 * same way, and could not tell whether the name it held was the operation's
 * identity or a projection of it.
 *
 * These rows are pure: a set of stream ids in, a published document out. No
 * filesystem, no clock, no process. The product is asked for the truth it
 * already holds, and the truth is that an operation's identity is the one it
 * was minted with.
 */
class MainOperationsCliTest {

    private val runId = "run-7"

    private fun stream(operation: String, channel: String = "stdout"): OutputStreamId =
        OutputStreamId("$runId/$operation/$channel")

    private fun published(document: JsonObject): List<JsonObject> {
        val operations = document["operations"] as? JsonArray ?: error("no operations array")
        return operations.map { it as JsonObject }
    }

    @Test
    fun `the published identity is the operation's own, with its separators`() {
        // An operation whose id carries a separator is exactly the one a folded
        // name loses: the ':' is what the directory name replaced with '_'.
        val operation = "$runId-s0-0-bp2-b0:branch-0:core.sh"

        val document = MainOperationsCli.document(
            runId,
            MainOperationsCli.operationsOf(listOf(stream(operation))),
        )

        val publishedOperation = published(document).single()["operation"]
        assertEquals(operation, (publishedOperation as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `the published streams carry their complete identity`() {
        val operation = "$runId-s0-0-bp2-b0:branch-0:core.sh"

        val document = MainOperationsCli.document(
            runId,
            MainOperationsCli.operationsOf(
                listOf(stream(operation, "stdout"), stream(operation, "stderr")),
            ),
        )

        val streams = published(document).single()["streams"] as JsonArray
        assertEquals(
            listOf("$runId/$operation/stdout", "$runId/$operation/stderr"),
            streams.map { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
    }

    @Test
    fun `two operations whose names fold alike are two operations`() {
        // These two ids differ, and the plane keeps their bytes apart because
        // the index records the identity each of them declared. A name that
        // folds alike is a name the DIRECTORY cannot tell apart, so a listing
        // built from names would report one operation where there are two.
        val first = "$runId-s0-0-bp2-b0:branch-0:core.sh"
        val second = "$runId-s0-0-bp2-b0_branch-0_core.sh"

        val document = MainOperationsCli.document(
            runId,
            MainOperationsCli.operationsOf(listOf(stream(first), stream(second))),
        )

        val operations = published(document).map {
            (it["operation"] as kotlinx.serialization.json.JsonPrimitive).content
        }
        assertEquals(listOf(first, second), operations)
    }

    @Test
    fun `a stream that names no operation is not published`() {
        // A stream id this producer did not mint has no operation to belong
        // to. Publishing it would attribute bytes to an operation that never
        // ran, and the attribution would be an invention.
        val document = MainOperationsCli.document(
            runId,
            MainOperationsCli.operationsOf(listOf(OutputStreamId("some/other/producer/transcript"))),
        )

        assertTrue(published(document).isEmpty())
    }

    @Test
    fun `the text rendering is one identity per line`() {
        val first = "$runId-s0-0"
        val second = "$runId-s0-1"

        val text = MainOperationsCli.text(
            MainOperationsCli.operationsOf(listOf(stream(first), stream(second))),
        )

        assertEquals("$first\n$second\n", text)
    }

    @Test
    fun `the document announces the schema it publishes`() {
        val document = MainOperationsCli.document(runId, emptyList())

        assertEquals(
            MainOperationsCli.SCHEMA,
            (document["schema"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals(
            runId,
            (document["runId"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals("""{"schema":"${MainOperationsCli.SCHEMA}","runId":"$runId","operations":[]}""", Json.encodeToString(JsonObject.serializer(), document))
    }
}
