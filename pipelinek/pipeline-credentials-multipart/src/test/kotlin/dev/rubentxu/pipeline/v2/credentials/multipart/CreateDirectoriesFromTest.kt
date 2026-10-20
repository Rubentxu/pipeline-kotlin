package dev.rubentxu.pipeline.v2.credentials.multipart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The directory chain `CredentialMaterializer.close()` needs in order to actually delete.
 * `Files.createDirectories` reports only the leaf, so a nested Zip entry (`certs/server.pem`)
 * silently creates every intermediate level on disk while the materializer tracks none of them.
 * `close()` then deletes the top directory, raises `DirectoryNotEmptyException`, and swallows it
 * -- leaving the extracted credential bytes on disk after a run that reported success. That is a
 * credential-retention defect, not a cosmetic one, and it is invisible at runtime: the run is
 * green and the file is still there.
 * The property pinned here is ORDER. `close()` walks `trackedDirs` in reverse insertion order, so
 * a child must be inserted after its parent for the child to be deleted first. Deepest-first is
 * just as plausible to write and fails every time, which is why this file exists rather than a
 * comment.
*/
class CreateDirectoriesFromTest {

    private val root: Path = Paths.get("/tmp/pipeline-zip-abc123-archive-cred")

    // --- the ordinary shapes ------------------------------------------------------

    @Test
    fun `a direct child is the whole chain`() {
        assertEquals(listOf(root.resolve("certs")), createDirectoriesFrom(root, root.resolve("certs")))
    }

    @Test
    fun `a chain is returned shallowest first`() {
        // The insertion order `close()` reverses. Pinned as an ordered list rather than a set
        // because two of these levels contain the others.
        assertEquals(
            listOf(
                root.resolve("a"),
                root.resolve("a/b"),
                root.resolve("a/b/c"),
            ),
            createDirectoriesFrom(root, root.resolve("a/b/c")),
        )
    }

    @Test
    fun `an unnormalised leaf contributes its normalised levels`() {
        // `Files.createDirectories` normalises the path it creates, so the tracked chain has to be
        // normalised too or `close()` deletes a path that was never created and leaves the real one.
        assertEquals(
            listOf(root.resolve("a"), root.resolve("a/b")),
            createDirectoriesFrom(root, root.resolve("a/./x/../b")),
        )
    }

    // --- the shapes that made the retention defect invisible -----------------------

    @Test
    fun `the top directory itself is never in its own chain`() {
        // `from` is exclusive. Including it would make `close()` try to delete `tempDir` through
        // the nested-entry path as well as through the zip path, deleting it twice: the second
        // delete hits an already-absent directory and is swallowed.
        assertEquals(
            listOf(root.resolve("a")),
            createDirectoriesFrom(root, root.resolve("a")),
        )
    }

    @Test
    fun `a leaf equal to the root yields nothing`() {
        assertEquals(emptyList<Path>(), createDirectoriesFrom(root, root))
    }

    @Test
    fun `every returned level is strictly below the root and contains the next`() {
        // The invariant that makes the reverse-order delete safe: each level is a strict descendant
        // of `root`, and each contains the level before it. Checked structurally rather than by
        // expected values so it holds for any depth, not only the two cases spelled out above.
        val chain = createDirectoriesFrom(root, root.resolve("a/b/c/d/e"))

        assertEquals(5, chain.size)
        for (index in chain.indices) {
            assertEquals(true, chain[index].startsWith(root), "${chain[index]} is not below $root")
            assertEquals(true, chain[index] != root, "${chain[index]} is the root itself")
            if (index > 0) {
                assertEquals(true, chain[index].startsWith(chain[index - 1]), "${chain[index]} is not below ${chain[index - 1]}")
            }
        }
    }

    @Test
    fun `reversing the chain yields an end that can be deleted first`() {
        // The property `close()` actually depends on, stated as an ordering fact: the deepest level
        // is last, so iterating in reverse visits it first, and it is the only one that is
        // guaranteed to be empty.
        val chain = createDirectoriesFrom(root, root.resolve("a/b"))

        assertEquals(root.resolve("a/b"), chain.last())
        assertEquals(root.resolve("a"), chain.first())
    }
}
