package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The shared default workspace of [ShOptions] is ONE directory per JVM: it is
 * created when a caller asks for it, every later caller gets the same one, and
 * it exists.
 *
 * **The guarantee.** Two accesses to the shared default resolve to the same
 * path, and that path is a directory that exists. The existence half is not
 * decoration: the shell executor passes the workspace root to
 * `ProcessBuilder.directory(...)`, and the process builder rejects an absent
 * path, so a shared default that was announced but never created would fail
 * every shell step that asked for it.
 *
 * **The observable capacity it serves.** A caller that takes the default — the
 * tests and the legacy factory do — receives a workspace it can hand to a
 * child process without preparing anything, and receives the SAME workspace
 * every time, so two steps of one JVM do not each invent a directory of their
 * own.
 *
 * **Why this instrument and not another.** The facts are observable from inside
 * the JVM: the identity of two accesses and the presence of the directory.
 * The other half of the guarantee — that the JVM which created the directory
 * is the one that removes it — is not observable from inside, because the
 * removal happens on the way out; the gate observes that half, censusing the
 * temporaries a run leaves in its own directory. This test is also the reason
 * that census has something to see inside a green run: it is the code that
 * asks for the shared default, so the path that creates the temporary is
 * exercised on every run instead of only when something fails.
 *
 * **What this does not freeze.** The name of the directory, which is the one
 * `createTempDirectory` gives it; only its prefix is fixed, because the prefix
 * is what makes a leftover attributable to this project.
 */
class ShOptionsSharedWorkspaceTest {
    @Test
    fun `the shared default workspace is one directory, reused, and it exists`() {
        val first = ShOptions.EMPTY.workspaceRoot
        val second = ShOptions.EMPTY.workspaceRoot

        assertEquals(
            first,
            second,
            "the shared default workspace is created once and reused by every caller of this JVM",
        )
        assertTrue(
            Files.exists(first),
            "the shared default workspace has to exist: the shell executor hands it to ProcessBuilder.directory(...)",
        )
        assertTrue(
            first.fileName.toString().startsWith("shoptions-shared"),
            "the shared default workspace lives under its own prefix, so a leftover is attributable: ${first.fileName}",
        )
    }
}
