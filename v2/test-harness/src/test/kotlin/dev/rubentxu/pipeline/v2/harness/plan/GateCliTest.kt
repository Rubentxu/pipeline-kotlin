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
        // The wrapper is half of what identifies the v2 build root, so a scaffold without it would
        // let a test pass for the wrong reason. Empty is enough: nothing in these tests executes it.
        Files.writeString(v2.resolve("gradlew"), "#!/bin/sh\n")
        return v2
    }

    // ---- locating the tree ----

    @Test
    fun `v2 root is found from a module directory several levels down`(@TempDir tmp: Path) {
        val v2 = scaffold(tmp)
        val module = Files.createDirectories(v2.resolve("test-harness/build/classes"))

        assertEquals(v2, GateCli.v2Root(module), "a build directory several levels down must resolve")
    }

    @Test
    fun `repo root is the directory that contains v2`(@TempDir tmp: Path) {
        scaffold(tmp)

        // Starting INSIDE v2/ is the ordinary case: that is where Gradle runs the harness. The
        // answer is the containing directory, which is exactly what `v2Root(...).parent` would give
        // by accident — so the assertion names the containing directory rather than deriving it.
        assertEquals(tmp.toAbsolutePath().normalize(), GateCli.repoRoot(tmp.resolve("v2")))
    }

    /**
     * A repository-shaped tree whose root ALSO carries a `settings.gradle.kts`, as this repository
     * does: the root is a composite build that pulls `v2` in with `includeBuild("v2")`.
     *
     * This is the shape that broke the real gate run. With a bare "walk up until you find
     * settings.gradle.kts" the root's file matched first, `v2Root` returned the REPOSITORY root, and
     * `repoRoot` then returned its PARENT — a directory outside the checkout. The gate refused with
     * `git status failed: fatal: not a git repository`, which is a correct refusal of the wrong tree,
     * not a diagnosis of anything.
     */
    private fun compositeScaffold(root: Path): Path {
        val v2 = scaffold(root)
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            "rootProject.name = \"pipeline-kotlin\"\nincludeBuild(\"v2\")\n",
        )
        return v2
    }

    @Test
    fun `v2 root is not the composite root, which also has a settings file`(@TempDir tmp: Path) {
        val v2 = compositeScaffold(tmp)
        // Resolution STARTS AT THE CHECKOUT ROOT, which is what the JVM does: `main` derives its
        // start from the working directory. Starting inside `v2/` instead would match the right
        // file on the first probe and the test would pass whichever way the walk discriminates —
        // it passed under a mutation of the production code before this start point was fixed.
        assertEquals(
            v2,
            GateCli.v2Root(tmp),
            "the composite build's settings file must not shadow the v2 build it includes",
        )
    }

    @Test
    fun `repo root stays inside the checkout when the composite root has a settings file too`(
        @TempDir tmp: Path,
    ) {
        val v2 = compositeScaffold(tmp)

        assertEquals(
            tmp.toAbsolutePath().normalize(),
            GateCli.repoRoot(tmp),
            "resolution from the checkout root must land on the checkout root, never its parent",
        )
        assertTrue(v2 != GateCli.repoRoot(tmp), "sanity: the composite root is not the v2 build root")
    }

    /**
     * The runner must shell out with the working directory at the BUILD root, not the checkout root.
     *
     * This is not a preference; it is what made the first gate run to reach execution fail every
     * dispatch. The checkout root is a composite build whose own `settings.gradle.kts` has no
     * modules, so running `v2/gradlew` from there resolves the ROOT build and every module path in
     * the dispatch dies with "task not found in root project 'pipeline-kotlin'".
     *
     * The assertion reads the working directory back out of a REAL invocation rather than checking
     * a field, because a field can be set correctly while the process is still started somewhere
     * else. The fake `gradlew` writes its own `$PWD` to a file, so the test observes what the
     * process would actually have seen.
     */
    @Test
    fun `the runner starts gradle in the build root, not the checkout root`(@TempDir tmp: Path) {
        val v2 = compositeScaffold(tmp)
        val marker = tmp.resolve("observed-cwd.txt")
        // A `gradlew` that records where it was started from and exits 0. It must be executable, or
        // the failure would be a permission error rather than the wrong-directory assertion.
        val gradlew = v2.resolve("gradlew")
        Files.writeString(gradlew, "#!/bin/sh\npwd > '$marker'\n")
        gradlew.toFile().setExecutable(true)

        val ran = GradleSuiteRunner(repoRoot = tmp, buildRoot = v2)
            .run(
                Dispatch.GradleInvocation(
                    gradleProject = ":mod-one",
                    task = "test",
                    filters = listOf("AlphaTest"),
                ),
            )

        assertTrue(ran, "the fake wrapper exits 0, so the invocation must be reported as successful")
        assertTrue(Files.exists(marker), "the wrapper must have been able to record its working directory")
        // `toRealPath` on BOTH sides: the process reports a resolved path, and on macOS a temp dir
        // arrives under /private, so comparing the unresolved path would fail for a reason that has
        // nothing to do with the working directory.
        assertEquals(
            v2.toRealPath().toString(),
            Files.readString(marker).trim(),
            "gradle must run where the modules are, not where the checkout root is",
        )
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
    fun `an untracked scratch file does not block, because it is not part of the change under test`(
        @TempDir tmp: Path,
    ) {
        // The distinction matters: an untracked file is not in `git diff base...HEAD` either, but
        // refusing on every scratch file would make the gate unusable in a working checkout. The
        // rule is about changes to tracked files, and about untracked files Gradle would COMPILE —
        // a note in the repo root changes nothing about the candidate's behaviour.
        val repo = gitRepo(tmp)
        Files.writeString(repo.resolve("scratch.txt"), "notes\n")

        assertEquals(emptyList(), GateCli.dirtyPaths(repo))
    }

    @Test
    fun `an untracked doc under v2 does not block either, because it cannot compile`(@TempDir tmp: Path) {
        // The companion row that keeps the rule from becoming "refuse on everything untracked".
        val repo = gitRepo(tmp)
        Files.createDirectories(repo.resolve("v2/docs"))
        Files.writeString(repo.resolve("v2/docs/receipt.md"), "# draft\n")

        assertEquals(
            emptyList(),
            GateCli.dirtyPaths(repo),
            "an untracked markdown file is not compiled and cannot change the candidate's bytes",
        )
    }

    @Test
    fun `an untracked Kotlin source under v2 DOES block, because it compiles into the candidate`(
        @TempDir tmp: Path,
    ) {
        // The defect this closes. `git diff base...HEAD` does not mention this file, so the plan
        // would omit its capability, yet Gradle compiles it into the candidate: a receipt for bytes
        // the commit does not contain.
        val repo = gitRepo(tmp)
        val sneaky = repo.resolve("v2/pipeline-application/src/main/kotlin/Sneaky.kt")
        Files.createDirectories(sneaky.parent)
        Files.writeString(sneaky, "fun sneaky() = Unit\n")

        assertEquals(
            listOf("v2/pipeline-application/src/main/kotlin/Sneaky.kt"),
            GateCli.dirtyPaths(repo),
        )
    }

    @Test
    fun `an untracked Java or script source is treated like Kotlin`(@TempDir tmp: Path) {
        val repo = gitRepo(tmp)
        val java = repo.resolve("v2/mod/src/main/java/A.java")
        Files.createDirectories(java.parent)
        Files.writeString(java, "class A {}\n")
        val script = repo.resolve("v2/mod/build.gradle.kts")
        Files.createDirectories(script.parent)
        Files.writeString(script, "// extra config\n")

        assertEquals(
            listOf("v2/mod/build.gradle.kts", "v2/mod/src/main/java/A.java"),
            GateCli.dirtyPaths(repo).sorted(),
        )
    }

    @Test
    fun `the buildable-source discriminator is about the tree and the extension, not the path name`(
        @TempDir tmp: Path,
    ) {
        // Guards against the rule drifting into "anything untracked under v2": build OUTPUTS live
        // under v2/ too and are untracked by design. What separates them is the extension, not the
        // directory — a generated .kt under v2/ would genuinely be compiled by a build that picks it
        // up, so refusing it is the safe direction rather than a false positive.
        assertTrue(GateCli.isBuildableSource("v2/mod/src/main/kotlin/A.kt"))
        assertTrue(GateCli.isBuildableSource("examples/plugin/src/main/kotlin/B.kt"))
        assertTrue(!GateCli.isBuildableSource("v2/mod/build/reports/report.html"))
        assertTrue(!GateCli.isBuildableSource("v2/build/test-results/TEST-A.xml"))
        assertTrue(!GateCli.isBuildableSource("docs/v2/07-uat/receipt.md"))
        assertTrue(!GateCli.isBuildableSource("notes.kt"), "outside the build tree, a .kt is a note")
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
            provenance = ObservedProvenance.Reported("abc"),
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

        val text = GateCli.render(
            GateCli.run("HEAD", repo, neverRuns, ObservedProvenance.Reported("abc"), "abc"),
        )

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
