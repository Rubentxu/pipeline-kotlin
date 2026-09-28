package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.ProjectCheckoutDetector
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the behaviour of the single [ProjectCheckoutDetector] definition that
 * `core.deleteDir` and `core.cleanWs` now share.
 *
 * ## Why this test exists
 *
 * The predicate used to be duplicated in `DeleteDirOperationsAdapter` and
 * `CleanWsOperationsAdapter`. The two copies were byte-identical, but nothing
 * held them there: a fix applied to one would silently leave the other Step
 * guarding a deletion differently. Extracting it removes the drift risk, and
 * this test removes the "did the extraction change the meaning?" risk.
 *
 * ## What is deliberately NOT asserted
 *
 * A bare non-VCS project tree reads as scratch and is NOT protected. That is
 * known follow-up debt (closure receipt item 2), not an accident of this
 * refactor, and the test below pins the current behaviour explicitly so a
 * future change to that default has to be a deliberate, visible edit here
 * rather than a silent drift.
 */
@Timeout(20)
class ProjectCheckoutDetectorTest {

    @Test
    fun `a directory carrying a git marker is a project checkout`(@TempDir tmp: Path) {
        Files.createDirectory(tmp.resolve(".git"))

        assertTrue(ProjectCheckoutDetector.isProjectCheckout(tmp))
    }

    @Test
    fun `a git marker file counts as well as a directory`(@TempDir tmp: Path) {
        // Submodules and linked worktrees store .git as a file, not a directory.
        Files.writeString(tmp.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/x")

        assertTrue(ProjectCheckoutDetector.isProjectCheckout(tmp))
    }

    @Test
    fun `mercurial and subversion markers are recognised`(@TempDir tmp: Path) {
        Files.createDirectory(tmp.resolve(".hg"))
        assertTrue(ProjectCheckoutDetector.isProjectCheckout(tmp), ".hg must be recognised")

        val svnRoot = Files.createDirectories(tmp.resolve("svn-project"))
        Files.createDirectory(svnRoot.resolve(".svn"))
        assertTrue(ProjectCheckoutDetector.isProjectCheckout(svnRoot), ".svn must be recognised")
    }

    @Test
    fun `a scratch workspace with no marker is not a project checkout`(@TempDir tmp: Path) {
        // This is the C12 regression shape: the corpus runs
        // `pipelinek run --workspace <@TempDir>` and fixture 11-workflow-control
        // calls deleteDir() there. Refusing this wipe is the regression.
        Files.writeString(tmp.resolve("build.log"), "scratch")

        assertFalse(ProjectCheckoutDetector.isProjectCheckout(tmp))
    }

    @Test
    fun `a workspace directly nested inside a checkout is still protected`(@TempDir tmp: Path) {
        // `repo/build` has no marker of its own; its parent `repo` does.
        // Without the parent lookup the guard would miss the real project.
        Files.createDirectory(tmp.resolve(".git"))
        val nested = Files.createDirectories(tmp.resolve("build"))

        assertTrue(
            ProjectCheckoutDetector.isProjectCheckout(nested),
            "the parent-directory lookup is load-bearing for nested workspaces",
        )
    }

    @Test
    fun `a workspace two levels below a checkout is NOT protected`(@TempDir tmp: Path) {
        // Only ONE level up is checked, so `repo/build/agent-ws` is NOT seen as
        // part of the checkout. Verified empirically before this test was
        // written: an earlier version of it asserted the opposite and failed.
        //
        // This is a real limitation of the current guard, not an aspiration.
        // Widening the lookup is a behaviour change, so it is pinned here to
        // keep it visible rather than accidental.
        Files.createDirectory(tmp.resolve(".git"))
        val grandchild = Files.createDirectories(
            tmp.resolve("build").resolve("agent-ws"),
        )

        assertFalse(
            ProjectCheckoutDetector.isProjectCheckout(grandchild),
            "only the directory itself and its immediate parent are inspected",
        )
    }

    @Test
    fun `a bare non-VCS project tree is unprotected by design`(@TempDir tmp: Path) {
        // Known limitation, recorded in docs/v2/07-uat/C10_C13_SESSION_CLOSURE.md
        // item 2. Pinned so that improving it must be a deliberate change.
        Files.writeString(tmp.resolve("build.gradle.kts"), "// a real project, but not a checkout")

        assertFalse(ProjectCheckoutDetector.isProjectCheckout(tmp))
    }

    @Test
    fun `an existing non-marker directory does not make a checkout`(@TempDir tmp: Path) {
        // Guards against a too-broad existence check passing by accident.
        Files.createDirectory(tmp.resolve("src"))

        assertFalse(ProjectCheckoutDetector.isProjectCheckout(tmp))
    }
}
