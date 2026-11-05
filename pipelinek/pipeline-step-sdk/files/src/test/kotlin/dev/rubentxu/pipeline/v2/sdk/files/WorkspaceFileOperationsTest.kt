package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The workspace file operations, and what they do when a link changes under them.
 *
 * ## The guarantee
 *
 * An operation never lands outside the authorized root. The way that is kept is
 * not a promise about the absence of links — a link is a normal thing to find in
 * a workspace — but a promise about the ORDER: the chain of directories is
 * looked at immediately before the effect, so a link that is there when the
 * product looks stops the operation instead of being obeyed.
 *
 * ## What each row holds
 *
 *  - the operation that meets a link in the chain refuses, and nothing is
 *    written outside the root;
 *  - the operation that meets nothing writes inside the root, on the path the
 *    decision judged and not on the name the pipeline wrote;
 *  - a link that points inside the root is honoured, because the workspace of
 *    trust has to keep working the way its owner left it.
 */
class WorkspaceFileOperationsTest {

    private val filesystem = FakeWorkspaceFileSystem()

    private val workspace: Path = Path.of("/ws")
    private val outside: Path = Path.of("/outside")

    private fun writer() = FileWriteExecutor(
        workspaceResolver = { _, _ -> workspace },
        fileSystem = filesystem,
    )

    private fun reader() = FileReadExecutor(
        workspaceResolver = { _, _ -> workspace },
        fileSystem = filesystem,
    )

    private fun deleter() = DeleteDirExecutor(
        workspaceResolver = { _, _ -> workspace },
        fileSystem = filesystem,
        rootDestruction = RootDestruction.ScratchOwned,
    )

    private fun cleaner() = CleanWsExecutor(
        workspaceResolver = { _, _ -> workspace },
        fileSystem = filesystem,
        rootDestruction = RootDestruction.ScratchOwned,
    )

    @Test
    fun `a write whose chain becomes a link is refused and writes nothing outside`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        // The name is not a link yet: the decision accepts it.
        filesystem.linkAppearsAfterRealPathOf(
            watched = workspace,
            path = workspace.resolve("sub"),
            target = outside,
        )

        assertThrows(IllegalArgumentException::class.java) {
            writer().execute(
                "stage",
                0,
                StepSpec.WriteFile(file = "sub/file.txt", text = "payload", encoding = "UTF-8"),
            )
        }

        assertFalse(
            filesystem.holds(outside.resolve("file.txt")),
            "the bytes landed outside the authorized root",
        )
    }

    @Test
    fun `a link that appears between the check and the creation stops the write`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        // The link appears at the creation, which is the moment the chain would
        // become real.
        filesystem.linkAppearsOnFirstCreate(workspace.resolve("sub"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            writer().execute(
                "stage",
                0,
                StepSpec.WriteFile(file = "sub/file.txt", text = "payload", encoding = "UTF-8"),
            )
        }

        assertFalse(
            filesystem.holds(outside.resolve("file.txt")),
            "the bytes landed outside the authorized root",
        )
    }

    @Test
    fun `a write inside the workspace lands on the path the decision judged`() {
        filesystem.directory(workspace)
        filesystem.directory(workspace.resolve("real"))
        // The operator's own link, pointing inside their own workspace.
        filesystem.link(workspace.resolve("inner"), workspace.resolve("real"))

        val result = writer().execute(
            "stage",
            0,
            StepSpec.WriteFile(file = "inner/file.txt", text = "payload", encoding = "UTF-8"),
        )

        assertEquals(
            workspace.resolve("inner/file.txt").toString(),
            result.path.toString(),
            "the result reports the path the pipeline wrote",
        )
        assertEquals(
            "payload",
            String(filesystem.bytesAt(workspace.resolve("real/file.txt")) ?: ByteArray(0)),
            "the effect landed on the real path the decision judged",
        )
    }

    @Test
    fun `a read whose chain becomes a link reports nothing`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        filesystem.file(outside.resolve("secret.txt"), "secret")
        filesystem.linkAppearsAfterRealPathOf(
            watched = workspace,
            path = workspace.resolve("sub"),
            target = outside,
        )

        val result = reader().execute(
            "stage",
            0,
            StepSpec.ReadFile(file = "sub/secret.txt", encoding = "UTF-8"),
        )

        assertFalse(result.exists, "the read was refused")
        assertNull(result.content, "the bytes of the target of the link did not come in")
    }

    @Test
    fun `a read inside the workspace returns the bytes`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "content")

        val result = reader().execute(
            "stage",
            0,
            StepSpec.ReadFile(file = "file.txt", encoding = "UTF-8"),
        )

        assertTrue(result.exists, "the file is there")
        assertEquals("content", result.content, "the bytes are the ones of the file")
    }

    @Test
    fun `a read through a link that stays inside the root returns the bytes`() {
        // The workspace of trust: the operator's own link, pointing at their own
        // directory. The read follows it, because the target is inside the root.
        filesystem.directory(workspace)
        filesystem.directory(workspace.resolve("real"))
        filesystem.file(workspace.resolve("real/file.txt"), "content")
        filesystem.link(workspace.resolve("inner"), workspace.resolve("real"))

        val result = reader().execute(
            "stage",
            0,
            StepSpec.ReadFile(file = "inner/file.txt", encoding = "UTF-8"),
        )

        assertTrue(result.exists, "the file is there")
        assertEquals("content", result.content, "the bytes are the ones of the file")
    }

    @Test
    fun `a deletion whose chain becomes a link is refused and writes no marker`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        filesystem.linkAppearsAfterRealPathOf(
            watched = workspace,
            path = workspace.resolve("sub"),
            target = outside,
        )

        assertThrows(IllegalArgumentException::class.java) {
            deleter().execute("stage", 0, StepSpec.DeleteDir(path = "sub"))
        }

        assertFalse(
            filesystem.holds(outside.resolve(".deleted")),
            "the marker of the deletion landed outside the authorized root",
        )
    }

    @Test
    fun `a deletion inside the workspace removes the content and writes its marker`() {
        filesystem.directory(workspace)
        filesystem.directory(workspace.resolve("sub"))
        filesystem.file(workspace.resolve("sub/file.txt"), "content")

        val result = deleter().execute("stage", 0, StepSpec.DeleteDir(path = "sub"))

        assertTrue(result.deletedCount > 0, "the content of the directory is gone")
        assertFalse(filesystem.holds(workspace.resolve("sub/file.txt")), "the file is gone")
        assertTrue(filesystem.holds(workspace.resolve("sub/.deleted")), "the marker is inside the workspace")
    }

    // --- the names the product chooses for itself --------------------------------------
    //
    // The chain of directories is not the only path an effect travels through. A write
    // stages its bytes at a name of its own choosing next to the target, and the
    // idempotency markers live at a name of their own. Those names are inside the
    // workspace, so an actor that can write inside the workspace can occupy them with
    // a link before the effect — and a write FOLLOWS a link, so the bytes land on the
    // target of it, outside the root.

    @Test
    fun `a write whose staging name is a link is refused and writes nothing outside`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        // The staging name is `<file>.tmp`, next to the target. The product chooses
        // it, but the workspace is writable, so it can be occupied before the write.
        filesystem.link(workspace.resolve("file.txt.tmp"), outside.resolve("stolen.txt"))

        val outcome = runCatching {
            writer().execute(
                "stage",
                0,
                StepSpec.WriteFile(file = "file.txt", text = "payload", encoding = "UTF-8"),
            )
        }

        assertFalse(
            filesystem.holds(outside.resolve("stolen.txt")),
            "the bytes landed outside the authorized root",
        )
        assertTrue(
            outcome.exceptionOrNull() is IllegalArgumentException,
            "the write was refused instead of being obeyed: ${outcome.exceptionOrNull()}",
        )
    }

    @Test
    fun `a deletion whose marker is a link does not consult the target of the link`() {
        filesystem.directory(workspace)
        filesystem.directory(workspace.resolve("sub"))
        filesystem.file(workspace.resolve("sub/file.txt"), "content")
        filesystem.directory(outside)
        // The marker name is the product's own, and a digest on the other side of
        // the link is not a marker this product wrote. Reading it would make the
        // deletion a no-op that reports itself as done.
        filesystem.link(workspace.resolve("sub/.deleted"), outside.resolve("stolen.txt"))
        filesystem.file(outside.resolve("stolen.txt"), "a".repeat(64))

        val result = deleter().execute("stage", 0, StepSpec.DeleteDir(path = "sub"))

        assertTrue(result.deletedCount > 0, "the deletion happened instead of being a no-op")
        assertFalse(filesystem.holds(workspace.resolve("sub/file.txt")), "the content is gone")
    }

    @Test
    fun `a cleanup whose marker is a link does not consult the target of the link`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("file.txt"), "content")
        filesystem.directory(outside)
        filesystem.link(workspace.resolve(".cleaned"), outside.resolve("stolen.txt"))
        filesystem.file(outside.resolve("stolen.txt"), "a".repeat(64))

        val result = cleaner().execute("stage", 0, StepSpec.CleanWs(deleteDirs = false, patterns = null))

        assertTrue(result.deletedFiles > 0, "the cleanup happened instead of being a no-op")
        assertFalse(filesystem.holds(workspace.resolve("file.txt")), "the content is gone")
    }

    @Test
    fun `a cleanup writes its marker without following a link`() {
        filesystem.directory(workspace)
        filesystem.file(workspace.resolve("build/file.txt"), "content")
        filesystem.directory(outside)
        // The sweep does not match the marker name, so the link is still there
        // when the marker is written, and a write follows it.
        filesystem.link(workspace.resolve(".cleaned"), outside.resolve("stolen.txt"))

        cleaner().execute("stage", 0, StepSpec.CleanWs(deleteDirs = false, patterns = listOf("build/**")))

        assertFalse(
            filesystem.holds(outside.resolve("stolen.txt")),
            "the marker landed outside the authorized root",
        )
    }

    @Test
    fun `a cleanup does not look through a link to decide that a directory is empty`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        filesystem.directory(outside.resolve("empty"))
        // The link points at a directory outside the workspace. Whether that
        // directory is empty is not the workspace's business, and the link is
        // an entry, not a directory to remove.
        filesystem.link(workspace.resolve("link"), outside.resolve("empty"))

        cleaner().execute("stage", 0, StepSpec.CleanWs(deleteDirs = true, patterns = null))

        assertTrue(
            filesystem.holds(workspace.resolve("link")),
            "the link is an entry, not a directory to remove",
        )
    }
}
