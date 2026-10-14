package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contracts for the gate's own entry point: the dirty-tree refusal, and the rendering.
 *
 * ## Why the dirty tree is tested here and not deeper
 *
 * [GateCli.run] consults `git status` before it does anything else, so a dirty tree short-circuits
 * every later decision. That makes it unreachable through [Gate] alone, and it is the one refusal
 * that protects the others: without it, a plan computed from `git diff base...HEAD` silently
 * ignores uncommitted work and the evidence gets attributed to a commit that does not contain it.
 *
 * ## What is NOT tested here
 *
 * No end-to-end run against a real repository. That belongs to the installed-distribution
 * evidence, not to a unit test: it would measure the checkout rather than the contract.
 */
class GateCliTest {

    /**
     * A repository-shaped tree: a `v2/settings.gradle.kts` so [GateCli.v2Root] resolves, and enough
     * for the caller under test to look at.
     */
    private fun scaffold(root: Path): Path {
        val v2 = root.resolve("v2")
        Files.createDirectories(v2)
        Files.writeString(v2.resolve("settings.gradle.kts"), "// test scaffold\n")
        return v2
    }

    // ---- locating the tree ----

    @Test
    fun `v2 root is found from a module directory, not from a parent`(@TempDir tmp: Path) {
        val v2 = scaffold(tmp)
        val module = Files.createDirectories(v2.resolve("test-harness/build/classes"))

        assertEquals(v2, GateCli.v2Root(module), "a build directory several levels down must resolve")
    }

    @Test
    fun `repo root is the parent of v2`(@TempDir tmp: Path) {
        val v2 = scaffold(tmp)

        assertEquals(tmp.toAbsolutePath().normalize(), GateCli.repoRoot(v2))
    }

    // ---- the dirty tree ----

    @Test
    fun `a clean tree has no dirty paths`(@TempDir tmp: Path) {
        val repo = gitRepo(tmp)

        assertEquals(emptyList(), GateCli.dirtyPaths(repo))
    }

    @Test
    fun `a modified tracked file is dirty`(@TempDir tmp: Path) {
        val repo = gitRepo(tmp)
        Files.writeString(repo.resolve("tracked.txt"), "changed\n")

        assertEquals(listOf("tracked.txt"), GateCli.dirtyPaths(repo))
    }

    @Test
    fun `a staged file is dirty too, because the index is not HEAD yet`(@TempDir tmp: Path) {
        val repo = gitRepo(tmp)
        Files.writeString(repo.resolve("tracked.txt"), "staged\n")
        git(repo, "add", "tracked.txt")

        assertEquals(
            listOf("tracked.txt"),
            GateCli.dirtyPaths(repo),
            "a staged change is still not in the commit the evidence would be bound to",
        )
    }

    @Test
    fun `an untracked file does not block, because it is not part of the change under test`(
        @TempDir tmp: Path,
    ) {
        // The distinction matters: an untracked file is not in `git diff base...HEAD` either, but
        // refusing on every scratch file would make the gate unusable in a working checkout. The
        // rule is about changes to tracked files, which is what the plan describes.
        val repo = gitRepo(tmp)
        Files.writeString(repo.resolve("scratch.txt"), "notes\n")

        assertEquals(emptyList(), GateCli.dirtyPaths(repo))
    }

    @Test
    fun `a dirty tree refuses before any suite runs`(@TempDir tmp: Path) {
        val repo = gitRepo(tmp)
        Files.writeString(repo.resolve("tracked.txt"), "changed\n")

        var ran = false
        val outcome = GateCli.run(
            base = "HEAD",
            repoRoot = repo,
            runner = object : SuiteRunner {
                override fun run(invocation: Dispatch.GradleInvocation): Boolean {
                    ran = true
                    return true
                }
            },
            observedCommit = "abc",
            expectedCommit = "abc",
        )

        assertTrue(ran.not(), "a dirty tree must not spend a build deciding what to run")
        assertTrue(
            outcome is GateCli.Outcome.DirtyTree,
            "a dirty tree is its own refusal, not a planner refusal: $outcome",
        )
    }

    // ---- rendering ----

    @Test
    fun `a dirty tree is rendered with the offending paths`(@TempDir tmp: Path) {
        val repo = gitRepo(tmp)
        Files.writeString(repo.resolve("tracked.txt"), "changed\n")

        val text = GateCli.render(GateCli.run("HEAD", repo, neverRuns, "abc", "abc"))

        assertTrue("tracked.txt" in text, "the operator must see which file blocks the gate:\n$text")
        assertTrue("dirty" in text, "the refusal must say why:\n$text")
    }

    @Test
    fun `every refusal case renders an action, never a bare type name`(@TempDir tmp: Path) {
        val repo = gitRepo(tmp)
        val refusals = GateCli.render(
            GateCli.Outcome.Refused(
                ValidationPlan.Refused(
                    listOf(
                        SelectionRefusal.UnclassifiedPath("v2/x/Y.kt"),
                        SelectionRefusal.MissingSuite("cap", "NoTest"),
                        SelectionRefusal.SelectedButNotRun("ZTest"),
                        SelectionRefusal.SuiteDidNotRun("WTest", ":mod"),
                        SelectionRefusal.StaleEvidence("abc", "def"),
                    ),
                ),
            ),
        )

        // Each case names a different operator action; a message that only said the class name
        // would leave the reader to guess, which is how refusals get worked around.
        listOf("v2/x/Y.kt", "cap", "ZTest", "WTest", ":mod", "def").forEach {
            assertTrue(it in refusals, "the rendered refusal must name '$it':\n$refusals")
        }
    }

    // ---- helpers ----

    private val neverRuns = object : SuiteRunner {
        override fun run(invocation: Dispatch.GradleInvocation): Boolean = false
    }

    /** A minimal Git repository with one committed file, created without touching global config. */
    private fun gitRepo(tmp: Path): Path {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        git(repo, "init", "--quiet", "--initial-branch=main")
        // Explicit identity: the machine's global config may not have one, and a commit without it
        // fails, which would turn a helper into an unrelated source of red.
        git(repo, "config", "user.email", "harness@example.invalid")
        git(repo, "config", "user.name", "harness")
        Files.writeString(repo.resolve("tracked.txt"), "original\n")
        git(repo, "add", "tracked.txt")
        git(repo, "commit", "--quiet", "-m", "initial")
        return repo
    }

    private fun git(repo: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
