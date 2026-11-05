package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The containment anchor, and the two moments it decides in.
 *
 * ## What these rows hold
 *
 * The anchor decides twice, and the two decisions answer different questions.
 * The first asks whether the target is inside the root, on the real path. The
 * second asks whether the chain of directories between the root and the effect
 * is free of symbolic links, and it is asked immediately before the effect.
 * The second one is not a repetition: a component that was not there when the
 * first decision was taken is a name, and the name can be something else by
 * the time the effect happens.
 *
 * ## Why the filesystem is a fake here
 *
 * The window between the two decisions is a few microseconds wide, and a test
 * against a real filesystem cannot put an actor inside it. The fake the test
 * owns can, and that is the whole reason the filesystem is a port.
 */
class WorkspaceAnchoringTest {

    private val filesystem = FakeWorkspaceFileSystem()
    private val anchor = WorkspaceAnchoring(filesystem)

    private val workspace: Path = Path.of("/ws")
    private val outside: Path = Path.of("/outside")

    @Test
    fun `a link that leaves the root is refused`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        filesystem.link(workspace.resolve("sub"), outside)

        val resolution = anchor.authorize(workspace, workspace.resolve("sub/file.txt"))

        val reason = (resolution as PathResolution.Rejected).reason
        assertTrue(reason is WorkspacePathError.SymlinkEscape, "was $reason")
    }

    @Test
    fun `a target under a link that stays inside the root resolves to the real path`() {
        // The resolution is not the declared path: the link is resolved and the
        // target is the directory it points at.
        filesystem.directory(workspace)
        filesystem.directory(workspace.resolve("real"))
        filesystem.link(workspace.resolve("inner"), workspace.resolve("real"))

        val resolution = anchor.authorize(workspace, workspace.resolve("inner/file.txt"))

        assertEquals(
            workspace.resolve("real/file.txt"),
            (resolution as PathResolution.Resolved).path,
        )
    }

    @Test
    fun `a link that appears between the two decisions stops the effect`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        // The actor replaces the name with a link once the decision has resolved
        // what it will judge, and before the chain is looked at.
        filesystem.linkAppearsAfterRealPathOf(
            watched = workspace,
            path = workspace.resolve("sub"),
            target = outside,
        )

        val resolution = anchor.authorize(workspace, workspace.resolve("sub/file.txt"))
        assertTrue(resolution is PathResolution.Resolved, "the first decision has to accept it")

        val prepared = anchor.prepareDirectoryChain(
            realRoot = anchor.realWorkspace(workspace),
            directory = workspace.resolve("sub"),
        )

        assertFalse(
            prepared,
            "the chain was crossed by a link, and the effect would land on the target of the link",
        )
    }

    @Test
    fun `a chain of real directories is prepared`() {
        filesystem.directory(workspace)

        val prepared = anchor.prepareDirectoryChain(
            realRoot = anchor.realWorkspace(workspace),
            directory = workspace.resolve("sub/inner"),
        )

        assertTrue(prepared, "the chain is a run of directories and nothing refused it")
        assertTrue(filesystem.isDirectory(workspace.resolve("sub")), "the first component is a directory")
        assertTrue(filesystem.isDirectory(workspace.resolve("sub/inner")), "the second component is a directory")
    }

    @Test
    fun `a chain whose creation finds a link is not prepared`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        // The link appears at the creation, which is the moment the chain would
        // become real. The answer has to look at the entry the creation found.
        filesystem.linkAppearsOnFirstCreate(workspace.resolve("sub"), outside)

        val prepared = anchor.prepareDirectoryChain(
            realRoot = anchor.realWorkspace(workspace),
            directory = workspace.resolve("sub/inner"),
        )

        assertFalse(
            prepared,
            "the chain crossed a link, and the effect would land on the target of the link",
        )
    }

    @Test
    fun `a chain that crosses a link is not prepared`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        filesystem.link(workspace.resolve("sub"), outside)

        val prepared = anchor.prepareDirectoryChain(
            realRoot = anchor.realWorkspace(workspace),
            directory = workspace.resolve("sub/inner"),
        )

        assertFalse(
            prepared,
            "the chain crosses a link, and the effect would land on the target of the link",
        )
    }

    @Test
    fun `a chain that crosses a link is not free`() {
        filesystem.directory(workspace)
        filesystem.directory(outside)
        filesystem.link(workspace.resolve("sub"), outside)

        val free = anchor.chainIsFreeOfLinks(
            realRoot = anchor.realWorkspace(workspace),
            path = workspace.resolve("sub/file.txt"),
        )

        assertFalse(free, "the chain crosses a link, and the read would follow it")
    }

    @Test
    fun `a link that stays inside the root is free`() {
        // The workspace of trust: the operator's own link, pointing at their own
        // directory. The resolution honours it, and the chain is free of links.
        filesystem.directory(workspace)
        filesystem.directory(workspace.resolve("real"))
        filesystem.link(workspace.resolve("inner"), workspace.resolve("real"))

        val free = anchor.chainIsFreeOfLinks(
            realRoot = anchor.realWorkspace(workspace),
            path = workspace.resolve("real/file.txt"),
        )

        assertTrue(free, "a link that points inside the root is not an escape")
    }
}
