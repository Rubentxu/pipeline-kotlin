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
import dev.rubentxu.pipeline.v2.sdk.files.ReadQuota
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The quota a document is read under.
 *
 * ## The guarantee under trial
 *
 * That `core-utils.readJson` and `core-utils.readYaml` REFUSE a document that
 * does not fit the quota, with a failure of the USER kind that names the
 * limit, and that the document is never held whole to find out: the file is
 * measured before it is read.
 *
 * ## The capacity this serves
 *
 * The document is whatever the pipeline brought: a report, a fixture, an
 * artifact that a step wrote. A step that reads it whole decides with its heap
 * what may be read, and a controller with a small heap refuses a document that
 * would fit in a stream. The quota is the size above which the product says
 * no, and it says it with the number it applied.
 *
 * ## Why the memory is MEASURED and not asserted
 *
 * Because the only way to know that the document was not held whole is to see
 * how much the thread allocated. The counter the JDK publishes is exact and
 * does not depend on the collector, and the file is big enough that the two
 * behaviours are far apart.
 *
 * ## Why the quota is INJECTED
 *
 * Because a refusal has to be provable without a large file: the rows that
 * refuse name a quota of a few dozen bytes, and the row that measures the
 * memory names a quota of a few mebibytes against a file of thirty-two. The
 * product's own quota is a separate fact, and it is checked where it is
 * declared.
 *
 * ## What this does not freeze
 *
 * The words of the refusal, beyond the number it carries, and the shape of the
 * document a document within the quota produces: those are other guarantees.
 */
class CoreUtilsReadQuotaTest {

    @TempDir
    lateinit var temp: Path

    /** The workspace the Step reads from. */
    private lateinit var workspace: Path

    @BeforeEach
    fun material() {
        workspace = Files.createDirectories(temp.resolve("workspace"))
    }

    @Test
    fun `a JSON file over the quota is refused with the limit and without content`() {
        Files.write(workspace.resolve("big.json"), jsonOf(BIG_DOCUMENT).toByteArray(Charsets.UTF_8))

        val failure = assertThrows<PluginStepException> {
            runBlocking {
                CoreUtilsReadJsonStepDefinition(readQuotaBytes = QUOTA).handler.execute(
                    ReadJsonInput(path = "big.json"),
                    context(),
                )
            }
        }

        assertEquals(
            FailureKind.USER,
            failure.failure.kind,
            "a document the author wrote is the author's mistake, not a broken infrastructure",
        )
        assertTrue(
            failure.message!!.contains(QUOTA.toString()),
            "the refusal names the limit that was applied: ${failure.message}",
        )
        assertFalse(
            failure.message!!.contains(BIG_DOCUMENT),
            "the refusal exposes nothing of what was read: ${failure.message}",
        )
    }

    @Test
    fun `a YAML file over the quota is refused with the limit and without content`() {
        Files.write(workspace.resolve("big.yaml"), yamlOf(BIG_DOCUMENT).toByteArray(Charsets.UTF_8))

        val failure = assertThrows<PluginStepException> {
            runBlocking {
                CoreUtilsReadYamlStepDefinition(readQuotaBytes = QUOTA).handler.execute(
                    ReadYamlInput(source = ReadYamlSource.FromFile(path = "big.yaml")),
                    context(),
                )
            }
        }

        assertEquals(
            FailureKind.USER,
            failure.failure.kind,
            "a document the author wrote is the author's mistake, not a broken infrastructure",
        )
        assertTrue(
            failure.message!!.contains(QUOTA.toString()),
            "the refusal names the limit that was applied: ${failure.message}",
        )
        assertFalse(
            failure.message!!.contains(BIG_DOCUMENT),
            "the refusal exposes nothing of what was read: ${failure.message}",
        )
    }

    @Test
    fun `a JSON file within the quota keeps the result it had`() {
        val text = "{\"name\":\"café 日本\"}"
        Files.write(workspace.resolve("small.json"), text.toByteArray(Charsets.UTF_8))

        val output = runBlocking {
            CoreUtilsReadJsonStepDefinition(readQuotaBytes = QUOTA).handler.execute(
                ReadJsonInput(path = "small.json"),
                context(),
            )
        }

        assertEquals(text, output.rawText, "the text of the file is the text the Step publishes")
        assertNotNull(output.parsed, "the document parsed, as it did before the quota existed")
        assertEquals(
            text.toByteArray(Charsets.UTF_8).size.toLong(),
            output.byteSize,
            "the size the Step publishes is the size of the file",
        )
        assertEquals(
            workspace.resolve("small.json").toString(),
            output.absolutePath,
            "the path the Step publishes is the path it read",
        )
    }

    @Test
    fun `the document over the quota is never read`() {
        val file = workspace.resolve("big.json")
        writePattern(file, BIG_FILE_BYTES)
        val counter = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(counter != null, "this JVM does not report the memory a thread allocates")

        val before = counter!!.getCurrentThreadAllocatedBytes()
        assumeTrue(before >= 0, "this JVM does not report the memory a thread allocates")
        assertThrows<PluginStepException> {
            runBlocking {
                CoreUtilsReadJsonStepDefinition(readQuotaBytes = MEASURED_QUOTA).handler.execute(
                    ReadJsonInput(path = "big.json"),
                    context(),
                )
            }
        }
        val allocated = counter.getCurrentThreadAllocatedBytes() - before

        assertTrue(
            allocated < MEASURED_QUOTA / 2,
            "the Step held the document in memory: it allocated $allocated bytes to refuse a file " +
                "of $BIG_FILE_BYTES bytes, and a refusal is taken on the size of the file",
        )
    }

    @Test
    fun `the quota of the product is the number every reader takes`() {
        assertEquals(
            10L * 1024L * 1024L,
            ReadQuota.MAX_FILE_BYTES,
            "the quota is ten mebibytes, and it is the number the product already reads a whole " +
                "file under",
        )
    }

    /** A JSON document whose value is [value]. */
    private fun jsonOf(value: String): String = "{\"name\":\"$value\"}"

    /** A YAML document whose value is [value]. */
    private fun yamlOf(value: String): String = "name: $value\n"

    /**
     * Writes [size] bytes of a repeating pattern, in chunks.
     *
     * The file is written without ever holding it whole: a test that built the
     * file in memory would be measuring the memory of the TEST.
     */
    private fun writePattern(file: Path, size: Int) {
        val chunk = ByteArray(CHUNK_BYTES) { (it % 251).toByte() }
        Files.newOutputStream(file).use { out ->
            var written = 0
            while (written < size) {
                val count = minOf(CHUNK_BYTES, size - written)
                out.write(chunk, 0, count)
                written += count
            }
        }
    }

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
        /** The quota the rows that refuse a document use. */
        private const val QUOTA: Long = 64L

        /** The quota the row that measures the memory uses. */
        private const val MEASURED_QUOTA: Long = 4L * 1024L * 1024L

        /** The text of the document that does not fit the quota. */
        private const val BIG_DOCUMENT: String =
            "a document that does not fit the quota of the step, and it is longer than the quota " +
                "that was applied to it"

        /** How big the file of the row that measures the memory is. */
        private const val BIG_FILE_BYTES: Int = 32 * 1024 * 1024

        /** How many bytes the test writes at a time. */
        private const val CHUNK_BYTES: Int = 64 * 1024
    }
}
