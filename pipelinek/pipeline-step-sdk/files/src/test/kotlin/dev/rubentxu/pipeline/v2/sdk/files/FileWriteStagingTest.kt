package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

/**
 * The staging of a write: one staging file per operation, and nothing left
 * behind when the operation fails.
 *
 * ## The guarantee
 *
 * A write publishes its bytes through a staging file, and that staging file
 * belongs to ONE operation: two writes that overlap do not share it, do not
 * mix their content, and do not publish each other's digest. A write that
 * fails leaves no staging file behind and does not touch the file the target
 * had.
 *
 * ## The instrument
 *
 * The filesystem is the test's own. The unit lane may not touch a real
 * filesystem, and the case that matters here — two writes that OVERLAP — is
 * a race a real filesystem would not let a test place. The fake can: it runs
 * the second write at the exact point where the first one looks.
 */
class FileWriteStagingTest {

    private val filesystem = FakeWorkspaceFileSystem()

    private val workspace: Path = Path.of("/ws")
    private val outside: Path = Path.of("/outside")

    private fun writer() = FileWriteExecutor(
        workspaceResolver = { _, _ -> workspace },
        fileSystem = filesystem,
    )

    private fun write(text: String, file: String = "file.txt"): FileWriteResult = writer().execute(
        "stage",
        0,
        StepSpec.WriteFile(file = file, text = text, encoding = "UTF-8"),
    )

    private fun digest(text: String): String = FileWriteExecutor.sha256(text.toByteArray())

    private fun bytesAt(path: Path): String = String(filesystem.bytesAt(path) ?: ByteArray(0))

    @Test
    fun `two writes to the same target stage at two different paths`() {
        filesystem.directory(workspace)

        write("one")
        write("two")

        val staged = filesystem.stagedPaths()
        assertEquals(2, staged.size, "each write staged its own file")
        assertNotEquals(staged[0], staged[1], "the two writes did not share a staging path")
    }

    @Test
    fun `a write that overlaps another one publishes the digest of its own content`() {
        filesystem.directory(workspace)
        var second: FileWriteResult? = null
        // The second write runs while the first one is between its staging and
        // its rename: that is the overlap the guarantee is about.
        filesystem.interleaveBeforeTheNextRead {
            second = write("two")
        }

        val first = write("one")

        assertEquals(
            digest("one"),
            first.sha256,
            "the first write published the digest of its own content",
        )
        assertEquals(
            digest("two"),
            second?.sha256,
            "the second write published the digest of its own content",
        )
    }

    @Test
    fun `the target holds one whole content when two writes overlap`() {
        filesystem.directory(workspace)
        filesystem.interleaveBeforeTheNextRead { write("two") }

        write("one")

        val atTarget = bytesAt(workspace.resolve("file.txt"))
        assertTrue(
            atTarget == "one" || atTarget == "two",
            "the target holds a whole content, not a mixture: '$atTarget'",
        )
    }

    @Test
    fun `a link that takes the staging name is refused and nothing lands outside the root`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        filesystem.theNextStagingFileBecomesALink(outside.resolve("stolen.txt"))

        val outcome = runCatching { write("payload") }

        val failure = outcome.exceptionOrNull()
        assertTrue(
            failure is IOException,
            "the write was refused instead of being obeyed: $failure",
        )
        assertFalse(
            filesystem.holds(outside.resolve("stolen.txt")),
            "the bytes landed outside the authorized root",
        )
        assertTrue(
            filesystem.stagedPaths().none { filesystem.holds(it) },
            "the staging file did not survive the refusal",
        )
    }

    @Test
    fun `a rename that fails leaves no staging file and the previous file intact`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "previous")
        filesystem.theNextMoveFails()

        val outcome = runCatching { write("payload") }

        val failure = outcome.exceptionOrNull()
        assertTrue(
            failure is IOException,
            "the write was refused instead of publishing a partial result: $failure",
        )
        assertEquals(
            "previous",
            bytesAt(workspace.resolve("file.txt")),
            "the file the target had is intact",
        )
        assertTrue(
            filesystem.stagedPaths().none { filesystem.holds(it) },
            "the staging file of that operation did not survive the failure",
        )
    }

    @Test
    fun `a staging write that fails leaves no staging file and the previous file intact`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "previous")
        filesystem.theNextStagingWriteFails()

        val outcome = runCatching { write("payload") }

        val failure = outcome.exceptionOrNull()
        assertTrue(
            failure is IOException,
            "the write was refused instead of publishing a partial result: $failure",
        )
        assertEquals(
            "previous",
            bytesAt(workspace.resolve("file.txt")),
            "the file the target had is intact",
        )
        assertTrue(
            filesystem.stagedPaths().none { filesystem.holds(it) },
            "the staging file of that operation did not survive the failure",
        )
    }
}
