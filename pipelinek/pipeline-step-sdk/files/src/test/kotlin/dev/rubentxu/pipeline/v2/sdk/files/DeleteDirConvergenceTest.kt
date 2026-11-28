package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

/**
 * deleteDir converges, and the filesystem is the authority that says so.
 *
 * ## The guarantee
 *
 * A deleteDir that reports success left nothing behind. The way that is kept is
 * a verification: every invocation walks the real filesystem, deletes what the
 * walk finds, and reads the target back. Whatever is still there is a survivor,
 * and survivors arrive as the typed [DeleteDirIncompleteException] with no
 * marker written. The `.deleted` marker is the journal's evidence at the end of
 * a converged run — never the shortcut that skips the next one.
 *
 * ## What each row holds
 *
 *  - a deletion the filesystem refused is a typed failure naming its survivors,
 *    writes no receipt, and the next attempt converges once the fault is gone;
 *  - an entry that survives a reported delete counts as a survivor, not as
 *    removed progress;
 *  - a marker — forged, or a real receipt of an older run — never skips the
 *    work: content that is there is deleted, and `deletedCount` measures the
 *    filesystem, not the pre-walk promise.
 *
 * The barrier is deterministic: [FaultyFileSystem] answers the port and fails
 * only the deletions a row arms, so the race the guarantee lives through is
 * placed by the test and not waited for.
 */
class DeleteDirConvergenceTest {

    private val filesystem = FakeWorkspaceFileSystem()
    private val faulty = FaultyFileSystem(filesystem)

    private val workspace: Path = Path.of("/ws")
    private val target: Path = Path.of("/ws/sub")
    private val marker: Path = Path.of("/ws/sub/.deleted")

    private fun deleter(fileSystem: WorkspaceFileSystem = faulty) = DeleteDirExecutor(
        workspaceResolver = { _, _ -> workspace },
        fileSystem = fileSystem,
        rootDestruction = RootDestruction.ScratchOwned,
    )

    /** The workspace with a target holding one file per name. */
    private fun targetWith(vararg names: String) {
        filesystem.directory(workspace)
        names.forEach { filesystem.file(target.resolve(it), "bytes") }
    }

    /** What the filesystem holds under the target once the root itself is excluded. */
    private fun entriesUnderTarget(): List<Path> = filesystem.walk(target).filter { it != target }

    @Test
    fun `a refused deletion fails typed with its survivors and writes no marker`() {
        targetWith("gone.txt", "stuck.bin")
        faulty.refuseDeletionOf(target.resolve("stuck.bin"))

        val failure = assertThrows(DeleteDirIncompleteException::class.java) {
            deleter().execute("stage", 0, StepSpec.DeleteDir(path = "sub"))
        }

        assertEquals(
            setOf(target.resolve("stuck.bin")),
            failure.survivors.toSet(),
            "the survivors are exactly the entry the filesystem refused to give up",
        )
        assertEquals(1, failure.deletedCount, "the run reports what it really removed")
        assertFalse(
            filesystem.holds(target.resolve("gone.txt")),
            "the entries that could be removed were removed — a partial deletion is progress, not a rollback",
        )
        assertFalse(
            filesystem.holds(marker),
            "a receipt of cleanliness the verification refuted was written",
        )
    }

    @Test
    fun `the next attempt converges once the fault is gone`() {
        targetWith("gone.txt", "stuck.bin")
        faulty.refuseDeletionOf(target.resolve("stuck.bin"))
        assertThrows(DeleteDirIncompleteException::class.java) {
            deleter().execute("stage", 0, StepSpec.DeleteDir(path = "sub"))
        }

        faulty.allowAllDeletions()
        val result = deleter().execute("stage", 0, StepSpec.DeleteDir(path = "sub"))

        assertEquals(
            listOf(marker),
            entriesUnderTarget(),
            "after converging the target holds nothing but the marker this run wrote",
        )
        assertEquals(1, result.deletedCount, "the second attempt removed the one entry that was left")
        assertTrue(
            Sha256.isSha256Hex(String(filesystem.bytesAt(marker) ?: ByteArray(0))),
            "the marker carries the digest of its content, as journal evidence",
        )
    }

    @Test
    fun `an entry that survives a reported delete is a survivor and not a counted success`() {
        targetWith("real.txt", "ghost.bin")
        faulty.resurrectAfterDelete(target.resolve("ghost.bin"))

        val failure = assertThrows(DeleteDirIncompleteException::class.java) {
            deleter().execute("stage", 0, StepSpec.DeleteDir(path = "sub"))
        }

        assertEquals(
            setOf(target.resolve("ghost.bin")),
            failure.survivors.toSet(),
            "the post-deletion walk is the authority: a delete that reported true and left the entry is a survivor",
        )
        assertEquals(
            1,
            failure.deletedCount,
            "the count is what the filesystem confirms gone, not what the pre-walk list promised",
        )
        assertFalse(filesystem.holds(marker), "no marker was written over a refuted deletion")
    }

    @Test
    fun `a forged marker does not skip the deletion`() {
        filesystem.directory(workspace)
        filesystem.file(target.resolve("keep.txt"), "content")
        // A value that IS a well-formed sha256 hex: the old reading accepted it as
        // proof of a past deletion and returned a no-op. Forgery is enough to pass
        // a marker that is read as authority, so the marker cannot be the authority.
        filesystem.file(marker, "a".repeat(64))

        val result = deleter(fileSystem = filesystem).execute("stage", 0, StepSpec.DeleteDir(path = "sub"))

        assertFalse(filesystem.holds(target.resolve("keep.txt")), "the content was deleted, not memoized away")
        assertEquals(2, result.deletedCount, "the forged marker and the content are the two entries really removed")
        assertEquals(listOf(marker), entriesUnderTarget(), "the target converged to empty of content")
    }

    @Test
    fun `content created after a historical receipt is not omitted`() {
        targetWith("a.txt")
        deleter(fileSystem = filesystem).execute("stage", 0, StepSpec.DeleteDir(path = "sub"))

        // A genuine receipt from a previous converged run, and then new content
        // under the target. The receipt must not make the second deletion blind.
        filesystem.file(target.resolve("fresh.txt"), "created after the receipt")
        val second = deleter(fileSystem = filesystem).execute("stage", 0, StepSpec.DeleteDir(path = "sub"))

        assertFalse(filesystem.holds(target.resolve("fresh.txt")), "the new content was removed by the new attempt")
        assertEquals(2, second.deletedCount, "fresh.txt and the superseded marker are what the second run removed")
        assertEquals(listOf(marker), entriesUnderTarget(), "the second run converged too")
        assertTrue(
            Sha256.isSha256Hex(String(filesystem.bytesAt(marker) ?: ByteArray(0))),
            "the receipt was rewritten for the deletion that actually happened",
        )
    }

    /**
     * The fake's filesystem, selective about deletions.
     *
     * It answers every port question through [delegate] and fails only the
     * removals a row arms, so the window between "the walk said done" and "the
     * filesystem says otherwise" is placed by the test at the exact entry the
     * row is about. Nothing here sleeps and no permission is faked: the seam is
     * the port itself.
     */
    private class FaultyFileSystem(
        private val delegate: FakeWorkspaceFileSystem,
    ) : WorkspaceFileSystem by delegate {

        private val refused = mutableSetOf<Path>()
        private val resurrected = mutableSetOf<Path>()

        /** The next removal of [path] fails the way a busy storage layer does. */
        fun refuseDeletionOf(path: Path) {
            refused.add(path.normalize())
        }

        /** Every removal answers normally again. */
        fun allowAllDeletions() {
            refused.clear()
        }

        /** A removal of [path] reports success and the entry comes back anyway. */
        fun resurrectAfterDelete(path: Path) {
            resurrected.add(path.normalize())
        }

        override fun deleteIfExists(path: Path): Boolean {
            val p = path.normalize()
            if (p in refused) {
                throw IOException("the storage layer refused to remove $p")
            }
            val deleted = delegate.deleteIfExists(p)
            if (deleted && p in resurrected) {
                delegate.file(p, "the entry the delete reported gone and the filesystem kept")
            }
            return deleted
        }
    }
}
