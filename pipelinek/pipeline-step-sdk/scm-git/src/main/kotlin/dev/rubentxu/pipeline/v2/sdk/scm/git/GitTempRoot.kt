package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Files
import java.nio.file.Path

/**
 * The temporary roots a checkout creates, and how they go away.
 *
 * A checkout opens four of them: the credential directory (the SSH key, the
 * answer file, the helper scripts), the executor's own control root, and the
 * control roots of the poll executor and of the changelog writer. Each one is
 * created with `Files.createTempDirectory`, which means each one is a
 * directory in the system temporary area that nobody else is looking after.
 *
 * **Why they have to be deleted, and not only emptied.** Wiping a file leaves
 * the file's NAME in a directory listing, and leaves the directory itself. A
 * secret is unreadable once its bytes are gone, but the fact that a key was
 * written for a given host, at a given moment, is itself information — and the
 * directory that holds it is a place a later reader looks. The checkout
 * therefore removes the tree, not only its contents.
 *
 * **Why the deletion is best effort.** A temporary directory that cannot be
 * removed (a file another process still holds open, a permission the operator
 * changed) is not a reason to fail a checkout that already did its work. The
 * failure is reported on stderr instead of being swallowed: silence here would
 * be indistinguishable from "there was nothing to remove".
 */
internal object GitTempRoot {

    /**
     * Removes a directory tree, children before parents.
     *
     * Every removal is attempted: one file that refuses to go does not leave
     * the rest of the tree behind.
     */
    fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        try {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (failure: Exception) {
                        System.err.println("Warning: failed to delete $path: ${failure.message}")
                    }
                }
            }
        } catch (failure: Exception) {
            System.err.println("Warning: failed to delete the tree at $root: ${failure.message}")
        }
    }
}
