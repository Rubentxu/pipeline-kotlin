package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The bytes a Step reads, when they are not the text they claim to be.
 *
 * ## The guarantee under trial
 *
 * That `core-utils.readJson` and `core-utils.readYaml` REFUSE a file whose
 * bytes are not UTF-8, with a failure of the USER kind, and that the text
 * with the substituted characters never reaches the parser.
 *
 * ## The capacity this serves
 *
 * A pipeline that reads a document has to be able to trust that what it read
 * is what is on the disk. The conversion Kotlin offers does not fail: every
 * byte it cannot read becomes U+FFFD, and the parser then reads a document
 * the file does not contain. A file truncated in the middle of a character,
 * or written by a tool that speaks another encoding, would be read as a
 * valid document with a strange name in it — and the pipeline would carry
 * that document forward as if the operator had written it.
 *
 * ## Why this instrument is a UNIT test
 *
 * The Step is exercised through its own handler, with the only capability it
 * declares and a workspace the test owns: no pipeline is started and no
 * engine is involved. The file the Step reads is a temporary file, because
 * the BYTES are the thing under trial, and the only way to hand a Step bytes
 * that are not text is to write them.
 *
 * ## What this does not freeze
 *
 * The shape of the document, the vocabulary of the YAML adapter and the wire
 * form of the output: those are other guarantees, and this file says nothing
 * about them.
 */
class CoreUtilsReadUtf8Test {

    @TempDir
    lateinit var temp: Path

    /** The workspace the Step reads from. */
    private lateinit var workspace: Path

    @BeforeEach
    fun material() {
        workspace = Files.createDirectories(temp.resolve("workspace"))
    }

    @Test
    fun `a JSON file whose bytes are not UTF-8 is refused with a user failure`() {
        Files.write(workspace.resolve("broken.json"), malformedJson())

        val failure = assertThrows<PluginStepException> {
            runBlocking {
                CoreUtilsReadJsonStepDefinition().handler.execute(
                    ReadJsonInput(path = "broken.json"),
                    context(),
                )
            }
        }

        assertEquals(
            FailureKind.USER,
            failure.failure.kind,
            "a file the author wrote is the author's mistake, not a broken infrastructure",
        )
        assertTrue(
            failure.message!!.contains("not valid UTF-8"),
            "the refusal names what is wrong with the file: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("offset $JSON_MALFORMED_OFFSET"),
            "the refusal says WHERE the file stops being UTF-8: ${failure.message}",
        )
        assertFalse(
            failure.message!!.contains(REPLACEMENT),
            "the text with the substituted character never reached the parser: ${failure.message}",
        )
    }

    @Test
    fun `a YAML file whose bytes are not UTF-8 is refused with a user failure`() {
        Files.write(workspace.resolve("broken.yaml"), malformedYaml())

        val failure = assertThrows<PluginStepException> {
            runBlocking {
                CoreUtilsReadYamlStepDefinition().handler.execute(
                    ReadYamlInput(source = ReadYamlSource.FromFile(path = "broken.yaml")),
                    context(),
                )
            }
        }

        assertEquals(
            FailureKind.USER,
            failure.failure.kind,
            "a file the author wrote is the author's mistake, not a broken infrastructure",
        )
        assertTrue(
            failure.message!!.contains("not valid UTF-8"),
            "the refusal names what is wrong with the source: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("offset $YAML_MALFORMED_OFFSET"),
            "the refusal says WHERE the source stops being UTF-8: ${failure.message}",
        )
    }

    @Test
    fun `a JSON file that is UTF-8 keeps its text and its parsed value`() {
        val text = "{\"name\":\"café 日本\"}"
        Files.write(workspace.resolve("good.json"), text.toByteArray(Charsets.UTF_8))

        val output = runBlocking {
            CoreUtilsReadJsonStepDefinition().handler.execute(
                ReadJsonInput(path = "good.json"),
                context(),
            )
        }

        assertEquals(text, output.rawText, "the text of the file is the text the Step publishes")
        assertTrue(output.parsed != null, "the document parsed")
        assertEquals(
            text.toByteArray(Charsets.UTF_8).size.toLong(),
            output.byteSize,
            "the size the Step publishes is the size of the file",
        )
        assertEquals(
            workspace.resolve("good.json").toString(),
            output.absolutePath,
            "the path the Step publishes is the path it read",
        )
    }

    @Test
    fun `a YAML file that is UTF-8 keeps its document`() {
        Files.write(workspace.resolve("good.yaml"), "name: café\n".toByteArray(Charsets.UTF_8))

        val output = runBlocking {
            CoreUtilsReadYamlStepDefinition().handler.execute(
                ReadYamlInput(source = ReadYamlSource.FromFile(path = "good.yaml")),
                context(),
            )
        }

        assertFalse(output.multipleDocuments, "one document is not many documents")
        val mapping = assertInstanceOf(
            YamlDocument.Map::class.java,
            output.single,
            "the document of this YAML is a mapping",
        )
        assertEquals(
            YamlDocument.Str("café"),
            mapping.entries.single().value,
            "the value of the document is the text of the file",
        )
    }

    @Test
    fun `the YAML of an inline text is read as it always was`() {
        val output = runBlocking {
            CoreUtilsReadYamlStepDefinition().handler.execute(
                ReadYamlInput(source = ReadYamlSource.FromText(text = "name: café")),
                context(),
            )
        }

        val mapping = assertInstanceOf(
            YamlDocument.Map::class.java,
            output.single,
            "the document of this YAML is a mapping",
        )
        assertEquals(
            YamlDocument.Str("café"),
            mapping.entries.single().value,
            "an inline text is read exactly as it was written",
        )
        assertNull(output.absolutePath, "an inline text has no file behind it")
    }

    @Test
    fun `the two steps keep the names and the fields they publish`() {
        assertEquals(
            "core-utils.readJson",
            CoreUtilsReadJsonStepDefinition().contract.descriptor.name,
            "the name of the Step is what a pipeline writes, and it does not change",
        )
        assertEquals(
            "core-utils.readYaml",
            CoreUtilsReadYamlStepDefinition().contract.descriptor.name,
            "the name of the Step is what a pipeline writes, and it does not change",
        )

        val json = ReadJsonInput(path = "a.json", prettyPrint = false, returnRawText = true)
        assertEquals(
            json,
            CoreUtilsReadJsonInputCodec.decode(CoreUtilsReadJsonInputCodec.encode(json)),
            "the fields of the JSON input travel as they did",
        )
        val yaml = ReadYamlInput(source = ReadYamlSource.FromFile(path = "a.yaml"))
        assertEquals(
            yaml,
            CoreUtilsReadYamlInputCodec.decode(CoreUtilsReadYamlInputCodec.encode(yaml)),
            "the fields of the YAML input travel as they did",
        )
    }

    /**
     * The bytes of a JSON document whose name carries a truncated character.
     *
     * The truncation is INSIDE a string, so the text with the character
     * replaced would parse: that is what makes the row discriminating, and
     * not a file that was refused for being broken anyway.
     */
    private fun malformedJson(): ByteArray =
        "{\"name\":\"caf".toByteArray(Charsets.UTF_8) +
            byteArrayOf(TRUNCATED_CHARACTER.toByte()) +
            "\"}".toByteArray(Charsets.UTF_8)

    /**
     * The bytes of a YAML document whose value carries a truncated character.
     */
    private fun malformedYaml(): ByteArray =
        "name: caf".toByteArray(Charsets.UTF_8) +
            byteArrayOf(TRUNCATED_CHARACTER.toByte()) +
            "\n".toByteArray(Charsets.UTF_8)

    /** The context the Step runs in: one capability, and a workspace of the test's own. */
    private fun context(): StepHandlerContext = StepHandlerContext(
        runId = RunId("run-1"),
        stepIndex = 0,
        capabilities = ReadCapabilities(
            ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        ),
    )

    /**
     * The one capability these two Steps read, and nothing else.
     *
     * The Steps declare it and the handler resolves it; a test that offered
     * anything else would be testing a different admission.
     */
    private class ReadCapabilities(private val location: ExecutionLocation) : StepCapabilityAccess {

        override fun available(): Set<StepCapability> = setOf(EXECUTION_LOCATION_CAPABILITY)

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T = when (key) {
            EXECUTION_LOCATION_CAPABILITY -> location as T
            else -> throw IllegalArgumentException("unexpected capability: $key")
        }
    }

    private companion object {
        /** The byte that starts the character the file cuts in half. */
        private const val TRUNCATED_CHARACTER: Int = 0xC3

        /** The offset of that byte in the JSON the rows write. */
        private const val JSON_MALFORMED_OFFSET: Int = 12

        /** The offset of that byte in the YAML the rows write. */
        private const val YAML_MALFORMED_OFFSET: Int = 9

        /** What a lenient conversion puts in the place of a byte it cannot read. */
        private const val REPLACEMENT: String = "�"
    }
}
