package dev.rubentxu.pipeline.v2.sdk.files

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The quota a file is read under.
 *
 * ## The guarantee under trial
 *
 * That a file which does not fit the quota is NOT read: the size is measured
 * before the bytes are, and a file over the limit hands over nothing at all.
 * And that a file which fits is read WHOLE: the bytes that come back are all
 * of them, and a file that grew while it was being read is refused rather
 * than published as a prefix.
 *
 * ## The capacity this serves
 *
 * Every reader of the product that holds a document in memory goes through
 * this one: the JSON and the YAML of a pipeline, and the authorizations of the
 * local service. A quota that each of them enforced on its own would be
 * three quotas to keep in step, and the first one that drifted would be a
 * reader that holds a file the product says it does not.
 *
 * ## Why this instrument is a UNIT test
 *
 * The filesystem is the test's own. What has to be observed here is a
 * DECISION (does this file fit under the quota?) and a BOUND (no more than
 * the quota crossed the boundary), and both are the product's policy rather
 * than the JDK's. The fake also knows how to grow a file between the
 * measurement of its size and the reading of its bytes, which is the one
 * moment a real filesystem does not let a test arrange.
 *
 * ## What this does not freeze
 *
 * The wording of a refusal, and the shape of the exception a caller raises
 * out of it: every reader of the product refuses in its own vocabulary, and
 * this file says nothing about those.
 */
class BoundedFileReaderTest {

    /** The filesystem the reads are applied to. */
    private val filesystem = FakeWorkspaceFileSystem()

    /** The workspace the file lives in. */
    private val workspace: Path = Path.of("/ws")

    /** The quota every row uses unless it says otherwise. */
    private val limit = 64L

    @Test
    fun `a file over the quota is refused and none of its bytes cross the boundary`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "a".repeat(200))

        val read = BoundedFileReader.read(workspace.resolve("file.txt"), limit, filesystem)

        val tooLarge = assertInstanceOf(
            BoundedRead.TooLarge::class.java,
            read,
            "a file that does not fit is refused, and not read",
        )
        assertEquals(200L, tooLarge.atLeastBytes, "the refusal carries the size the file has")
        assertEquals(limit, tooLarge.limitBytes, "the refusal carries the limit it applied")
        assertEquals(
            limit + 1,
            filesystem.lastReadMaxBytes(),
            "the read was asked for with a limit, so it could not be a whole-file read",
        )
        assertEquals(
            0,
            filesystem.lastReadBytesHandedOver(),
            "the bytes of a refused file never crossed the boundary: the file is measured, not read",
        )
    }

    @Test
    fun `a file that grew while it was read is refused instead of published as a prefix`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "a".repeat(10))
        // The file grows between the measurement of its size and the reading
        // of its bytes: the bytes that come back are a prefix of a bigger file.
        filesystem.theFileGrowsBeforeTheRead(4096)

        val read = BoundedFileReader.read(workspace.resolve("file.txt"), limit, filesystem)

        val tooLarge = assertInstanceOf(
            BoundedRead.TooLarge::class.java,
            read,
            "a file that grew past the quota is refused: a prefix that looks like a whole file " +
                "is a wrong answer that nobody can see",
        )
        assertTrue(
            tooLarge.atLeastBytes > limit,
            "the refusal says the file holds more than the quota: ${tooLarge.atLeastBytes}",
        )
    }

    @Test
    fun `a file within the quota is read whole`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "payload")

        val read = BoundedFileReader.read(workspace.resolve("file.txt"), limit, filesystem)

        val fits = assertInstanceOf(
            BoundedRead.Fits::class.java,
            read,
            "a file that fits is read, and not refused",
        )
        assertEquals(
            "payload",
            String(fits.bytes),
            "the bytes that come back are all of them: a file is read whole or not at all",
        )
    }

    @Test
    fun `the quota of the product is ONE number and every reader takes it`() {
        assertEquals(
            10L * 1024L * 1024L,
            ReadQuota.MAX_FILE_BYTES,
            "the quota is ten mebibytes, and it is the number the product already reads a whole " +
                "file under",
        )
        assertEquals(
            ReadQuota.MAX_FILE_BYTES,
            FileReadExecutor.DEFAULT_MAX_READ_BYTES,
            "the limit of the file step is the quota: a limit raised in one place is raised everywhere",
        )
    }
}
