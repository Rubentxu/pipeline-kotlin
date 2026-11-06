package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Base64

/**
 * The limit of a read: a file that does not fit is refused, and a file that
 * fits keeps the contract it had.
 *
 * ## The guarantee
 *
 * A read holds at most the limit, and it holds all of it or none of it. A
 * file that does not fit is refused with a typed error that says which limit
 * and which file — never with a content that looks whole, and never with
 * "the file is not there".
 *
 * ## The instrument
 *
 * The filesystem is the test's own. The unit lane may not touch a real
 * filesystem, and what has to be observed here is a DECISION (does this file
 * fit under the limit?) and a BOUND (no more than the limit crossed the
 * boundary), both of which are the product's policy rather than the JDK's.
 */
class FileReadLimitTest {

    private val filesystem = FakeWorkspaceFileSystem()

    private val workspace: Path = Path.of("/ws")

    /** The limit every row uses unless it says otherwise. */
    private val limit = 64L

    private fun reader(maxBytes: Long = limit) = FileReadExecutor(
        workspaceResolver = { _, _ -> workspace },
        fileSystem = filesystem,
        maxBytes = maxBytes,
    )

    private fun read(file: String = "file.txt", encoding: String = "UTF-8"): FileReadResult =
        reader().execute("stage", 0, StepSpec.ReadFile(file = file, encoding = encoding))

    private fun bytesOf(text: String): ByteArray = text.toByteArray()

    @Test
    fun `a file over the limit is refused with the typed error that names the limit and the size`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "a".repeat(200))

        val refusal = assertThrows(FileReadTooLargeException::class.java) { read() }

        assertEquals(
            FailureKind.USER,
            refusal.failure.kind,
            "the refusal is the user's to fix, not the engine's",
        )
        assertEquals(200L, refusal.atLeastBytes, "the refusal carries the size it measured")
        assertEquals(limit, refusal.limitBytes, "the refusal carries the limit it applied")
        assertTrue(
            refusal.message!!.contains("200"),
            "the message names the size: ${refusal.message}",
        )
        assertTrue(
            refusal.message!!.contains("64"),
            "the message names the limit: ${refusal.message}",
        )
    }

    @Test
    fun `a refused read is bounded and hands over nothing of the file`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "a".repeat(200))

        assertThrows(FileReadTooLargeException::class.java) { read() }

        assertEquals(
            limit + 1,
            filesystem.lastReadMaxBytes(),
            "the read was asked for with a limit, so it could not be a whole-file read",
        )
        assertEquals(
            0,
            filesystem.lastReadBytesHandedOver(),
            "the bytes of a refused file never crossed the boundary",
        )
    }

    @Test
    fun `a file under the limit keeps its bytes, its digest and its size`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "payload")

        val result = read()

        assertTrue(result.exists, "the file was read")
        assertEquals("payload", result.content, "the content is the text of the file")
        assertEquals(
            FileReadExecutor.sha256(bytesOf("payload")),
            result.sha256,
            "the digest is the one of those bytes",
        )
        assertEquals(7L, result.size, "the size is the one of those bytes")
    }

    @Test
    fun `a file under the limit keeps the Base64 representation`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "payload")

        val result = read(encoding = "Base64")

        assertTrue(result.exists, "the file was read")
        assertEquals(
            Base64.getEncoder().encodeToString(bytesOf("payload")),
            result.content,
            "the content is the second representation of the same bytes",
        )
        assertEquals(
            FileReadExecutor.sha256(bytesOf("payload")),
            result.sha256,
            "the digest is the one of the bytes, not of the representation",
        )
        assertEquals(7L, result.size, "the size is the one of the bytes")
    }

    @Test
    fun `a file of exactly the limit is accepted`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "a".repeat(64))

        val result = read()

        assertTrue(result.exists, "a file that fits exactly is not over the limit")
        assertEquals(64, result.content?.length, "the whole file was read")
        assertEquals(64L, result.size, "the size is the whole file")
    }

    @Test
    fun `a file that cannot be read at all fails as infrastructure, not as the user's`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "payload")
        filesystem.theNextReadFailsWithAnIoError()

        val failure = assertThrows(PipelineStepException::class.java) { read() }

        assertEquals(
            FailureKind.INFRASTRUCTURE,
            failure.failure.kind,
            "the storage layer refused us, and that is not the user's input",
        )
    }

    @Test
    fun `a file that grew while it was read is refused instead of published as a prefix`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "a".repeat(10))
        // The file grows between the measurement of its size and the reading
        // of its bytes: the bytes that come back are a prefix of a bigger file.
        filesystem.theFileGrowsBeforeTheRead(4096)

        val refusal = assertThrows(FileReadTooLargeException::class.java) { read() }

        assertEquals(
            limit,
            refusal.limitBytes,
            "the refusal carries the limit it applied",
        )
        assertTrue(
            refusal.atLeastBytes > limit,
            "the refusal says the file holds more than the limit: ${refusal.atLeastBytes}",
        )
    }
}
