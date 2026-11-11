package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Output
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The digest of a file, and the memory it takes to have it.
 *
 * ## The guarantee under trial
 *
 * That `core-utils.sha256` returns the digest of the file's BYTES for both
 * algorithms it admits, that it publishes the size of what it hashed, and
 * that it does not need the file in memory to do it.
 *
 * ## The capacity this serves
 *
 * The step runs on the controller, and the artifact it digests is whatever
 * the pipeline brought: a jar, a tarball, a bundle. A step that reads the
 * file whole decides with its heap what may be digested, and a controller
 * with a small heap refuses an artifact that would fit in a stream. The
 * digest is a fact about the bytes, and the bytes do not have to be
 * resident to be hashed.
 *
 * ## Why the memory is MEASURED and not asserted
 *
 * Because the only way to know that the file was not held whole is to see
 * how much the thread allocated. The counter the JDK publishes is exact and
 * does not depend on the collector, and the file is big enough that the two
 * behaviours are far apart: a file held whole allocates the size of the
 * file, and a file read in chunks allocates the size of the window.
 *
 * ## What this does not freeze
 *
 * The size of the window, the algorithm of the digest (that is the JDK's)
 * and the way the failure of a file that cannot be read is worded beyond
 * its classification.
 */
class CoreUtilsSha256DigestTest {

    @TempDir
    lateinit var temp: Path

    /** The workspace the Step reads from. */
    private lateinit var workspace: Path

    @BeforeEach
    fun material() {
        workspace = Files.createDirectories(temp.resolve("workspace"))
    }

    @Test
    fun `the digest of a small file is the reference, for both algorithms`() {
        val content = "the content of a small file".toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("small.bin"), content)

        assertEquals(
            referenceOf(content, "SHA-256"),
            step("small.bin", "SHA-256").hexDigest,
            "the digest of a small file is the digest of its bytes",
        )
        assertEquals(
            referenceOf(content, "SHA-1"),
            step("small.bin", "SHA-1").hexDigest,
            "the digest of a small file is the digest of its bytes",
        )
    }

    @Test
    fun `the digest of a file bigger than the buffer is the reference, for both algorithms`() {
        val file = workspace.resolve("big.bin")
        writePattern(file, BIG_FILE_BYTES)

        assertEquals(
            Sha256.ofFile(file),
            step("big.bin", "SHA-256").hexDigest,
            "the digest of a big file is the digest of its bytes, and the domain's own " +
                "streaming digest says so",
        )
        assertEquals(
            referenceOfFile(file, "SHA-1"),
            step("big.bin", "SHA-1").hexDigest,
            "the digest of a big file is the digest of its bytes",
        )
    }

    @Test
    fun `the file is not held in memory`() {
        val file = workspace.resolve("big.bin")
        writePattern(file, BIG_FILE_BYTES)
        val counter = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(counter != null, "this JVM does not report the memory a thread allocates")

        val before = counter!!.getCurrentThreadAllocatedBytes()
        assumeTrue(before >= 0, "this JVM does not report the memory a thread allocates")
        val output = step("big.bin", "SHA-256")
        val allocated = counter.getCurrentThreadAllocatedBytes() - before

        assertTrue(
            allocated < BIG_FILE_BYTES / 4,
            "the step held the file in memory: it allocated $allocated bytes for a file of " +
                "$BIG_FILE_BYTES bytes",
        )
        assertEquals(64, output.hexDigest.length, "the digest was computed anyway")
    }

    @Test
    fun `the size the step publishes is the size of the bytes it hashed`() {
        val file = workspace.resolve("big.bin")
        writePattern(file, BIG_FILE_BYTES)

        val output = step("big.bin", "SHA-256")

        assertEquals(
            BIG_FILE_BYTES.toLong(),
            output.byteSize,
            "the size the step publishes is the size of the file it hashed",
        )
    }

    @Test
    fun `a file that cannot be read keeps its classification and its detail`() {
        val file = workspace.resolve("unreadable.bin")
        Files.write(file, "content".toByteArray(Charsets.UTF_8))
        Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_WRITE))
        assumeTrue(
            !Files.isReadable(file),
            "this user can read a file that has no read permission, so the row cannot be measured",
        )

        val failure = assertThrows<PluginStepException> {
            runBlocking {
                CoreUtilsSha256StepDefinition().handler.execute(
                    Sha256Input(path = "unreadable.bin"),
                    context(),
                )
            }
        }

        assertEquals(
            FailureKind.INFRASTRUCTURE,
            failure.failure.kind,
            "a file the machine cannot read is not the author's mistake",
        )
        assertTrue(
            failure.message!!.contains("failed to read file"),
            "the refusal names what failed: ${failure.message}",
        )
    }

    @Test
    fun `a file that is not there is refused by name`() {
        val failure = assertThrows<PluginStepException> {
            runBlocking {
                CoreUtilsSha256StepDefinition().handler.execute(
                    Sha256Input(path = "nowhere.bin"),
                    context(),
                )
            }
        }

        assertEquals(
            FailureKind.USER,
            failure.failure.kind,
            "a path the author wrote is the author's mistake",
        )
        assertTrue(
            failure.message!!.contains("file not found"),
            "the refusal names what is missing: ${failure.message}",
        )
    }

    /** The digest the Step publishes for [path]. */
    private fun step(path: String, algorithm: String): Sha256Output = runBlocking {
        CoreUtilsSha256StepDefinition().handler.execute(
            Sha256Input(path = path, algorithm = algorithm),
            context(),
        )
    }

    /**
     * Writes [size] bytes of a repeating pattern, in chunks.
     *
     * The file is written without ever holding it whole: a test that built
     * the file in memory would be measuring the memory of the TEST.
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

    /** The digest of [content], by the JDK. */
    private fun referenceOf(content: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(content).joinToString("") { hex(it) }

    /**
     * The digest of the bytes of [file], by the JDK over a stream.
     *
     * A different shape from the Step's own: here the stream feeds the
     * digest as it is read, and the Step writes the chunks into the digest.
     */
    private fun referenceOfFile(file: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        DigestInputStream(Files.newInputStream(file), digest).use { stream ->
            stream.transferTo(OutputStream.nullOutputStream())
        }
        return digest.digest().joinToString("") { hex(it) }
    }

    /**
     * The byte [value], in hexadecimal.
     *
     * The locale is stated: a digest written in the locale of the machine
     * would spell the digits differently, and a reference that changes with
     * the machine cannot serve as a reference.
     */
    private fun hex(value: Byte): String = String.format(Locale.ROOT, "%02x", value)

    /** The context the Step runs in: one capability, and a workspace of the test's own. */
    private fun context(): StepHandlerContext = StepHandlerContext(
        runId = RunId("run-1"),
        stepIndex = 0,
        capabilities = Sha256Capabilities(
            ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        ),
    )

    /**
     * The one capability this Step reads, and nothing else.
     */
    private class Sha256Capabilities(private val location: ExecutionLocation) : StepCapabilityAccess {

        override fun available(): Set<StepCapability> = setOf(EXECUTION_LOCATION_CAPABILITY)

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T = when (key) {
            EXECUTION_LOCATION_CAPABILITY -> location as T
            else -> throw IllegalArgumentException("unexpected capability: $key")
        }
    }

    private companion object {
        /** How big the file of the rows that measure the memory is. */
        private const val BIG_FILE_BYTES: Int = 32 * 1024 * 1024

        /** How many bytes the test writes at a time. */
        private const val CHUNK_BYTES: Int = 64 * 1024
    }
}
