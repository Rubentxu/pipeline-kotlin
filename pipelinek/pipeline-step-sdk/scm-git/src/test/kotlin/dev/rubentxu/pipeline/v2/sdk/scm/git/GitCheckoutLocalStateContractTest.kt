package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutInput
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.GitCheckoutStepDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The checkout that respects what the TREE holds, not only what the run declared.
 *
 * **The guarantee.** A tree that carries changes the run did not make is not
 * moved, and the reason it was not moved is TYPED: the caller reads the reason,
 * not a sentence. The state of the tree travels next to the outcome, so a
 * checkout that leaves a tree alone over a dirty tree cannot be read as a clean
 * checkout.
 *
 * **Why the instrument is the double and not a repository.** What decides this
 * guarantee is the command line the checkout DOES NOT run and the value it
 * publishes. The double records both: it is the same instrument the module
 * already uses for the commands the checkout runs, and a test against a real
 * repository would exercise git rather than the policy — the E2E layer AGENTS.md
 * section 2 forbids.
 */
class GitCheckoutLocalStateContractTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `a tree that carries work is not moved`() {
        val git = FakeGit(
            remote = REMOTE_SHA,
            head = LOCAL_SHA,
            statusOutput = " M tracked.txt\n",
        )
        val executor = GitCheckoutFixtures.executor(git, temp)
        GitCheckoutFixtures.existingCheckout(workspace())

        val result = executor.execute(GitCheckoutFixtures.request(workspace(), poll = true))

        assertTrue(
            result.isFailure,
            "the checkout moved a tree that carries work this run did not make: ${result.getOrNull()}",
        )
        assertTrue(
            git.argvs.none { it.contains("checkout") },
            "the tree was moved even though it carries local work. The commands were: ${git.argvs}",
        )
        assertTrue(
            git.argvs.none { it.contains("fetch") },
            "the checkout downloaded objects for a tree it was not going to move. The " +
                "commands were: ${git.argvs}",
        )
    }

    @Test
    fun `the refusal names the state it found and the commit it did not install`() {
        val git = FakeGit(
            remote = REMOTE_SHA,
            head = LOCAL_SHA,
            statusOutput = " M tracked.txt\n",
        )
        val executor = GitCheckoutFixtures.executor(git, temp)
        GitCheckoutFixtures.existingCheckout(workspace())

        val result = executor.execute(GitCheckoutFixtures.request(workspace(), poll = true))

        val refusal = result.exceptionOrNull()
        assertTrue(
            refusal is GitCheckoutRefused,
            "the checkout refused with something that is not a typed refusal: $refusal",
        )
        assertEquals(
            GitCheckoutRefusal.LOCAL_CHANGES,
            (refusal as GitCheckoutRefused).reason,
            "the refusal has to say that the tree carries local work",
        )
        assertEquals(
            GitLocalState.MODIFIED,
            refusal.localState,
            "the refusal has to carry the state the tree was found in",
        )
        assertEquals(
            REMOTE_SHA,
            refusal.declaredSha,
            "the refusal has to carry the commit the run wanted and did not get",
        )
    }

    @Test
    fun `a tree that holds the declared commit and carries work is published as modified`() {
        val git = FakeGit(
            remote = LOCAL_SHA,
            head = LOCAL_SHA,
            statusOutput = " M tracked.txt\n",
        )
        val executor = GitCheckoutFixtures.executor(git, temp)
        GitCheckoutFixtures.existingCheckout(workspace())

        val result = executor.execute(GitCheckoutFixtures.request(workspace(), poll = true))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        assertEquals(
            GitCheckoutOutcome.ALREADY_CURRENT,
            result.getOrNull()!!.outcome,
            "the commit is the declared one, so the checkout left the tree alone",
        )
        assertEquals(
            GitLocalState.MODIFIED,
            result.getOrNull()!!.localState,
            "the tree carries changes that are not the run's, and the record has to say so: " +
                "an outcome alone would read as a clean checkout",
        )
    }

    @Test
    fun `a clean tree is moved and published as clean`() {
        val git = FakeGit(remote = REMOTE_SHA, head = LOCAL_SHA)
        val executor = GitCheckoutFixtures.executor(git, temp)
        GitCheckoutFixtures.existingCheckout(workspace())

        val result = executor.execute(GitCheckoutFixtures.request(workspace(), poll = true))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        assertEquals(
            GitCheckoutOutcome.UPDATED,
            result.getOrNull()!!.outcome,
            "the tree does not hold the declared commit and carries nothing: it is moved",
        )
        assertEquals(
            GitLocalState.CLEAN,
            result.getOrNull()!!.localState,
            "the tree was clean when the checkout found it",
        )
    }

    @Test
    fun `the command that moves a tree cannot overwrite a local change`() {
        val git = FakeGit(remote = REMOTE_SHA, head = LOCAL_SHA)
        val executor = GitCheckoutFixtures.executor(git, temp)
        GitCheckoutFixtures.existingCheckout(workspace())

        val result = executor.execute(GitCheckoutFixtures.request(workspace(), poll = true))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        val materialise = git.argvs.firstOrNull { it.contains("checkout") }
        assertTrue(
            materialise != null && materialise.none { it == "--force" },
            "the command that moves the tree has to be unable to discard a local change: " +
                "with `--force`, git overwrites the modified files of the tree and reverts " +
                "its staged ones (measured with git 2.56.0). The command was: $materialise",
        )
    }

    @Test
    fun `a tree whose state could not be read is not moved`() {
        val git = FakeGit(remote = REMOTE_SHA, head = LOCAL_SHA, statusFails = true)
        val executor = GitCheckoutFixtures.executor(git, temp)
        GitCheckoutFixtures.existingCheckout(workspace())

        val result = executor.execute(GitCheckoutFixtures.request(workspace(), poll = true))

        val refusal = result.exceptionOrNull()
        assertTrue(
            refusal is GitCheckoutRefused,
            "the checkout moved a tree whose state it could not read: $refusal",
        )
        assertEquals(
            GitCheckoutRefusal.UNKNOWN_STATE,
            (refusal as GitCheckoutRefused).reason,
            "a tree nobody inspected is not moved",
        )
        assertTrue(
            git.argvs.none { it.contains("checkout") },
            "the tree was moved even though its state could not be read. The commands were: " +
                "${git.argvs}",
        )
    }

    @Test
    fun `the output the consumer reads says that the tree carries work`() {
        val workspace = Files.createDirectories(temp.resolve("workspace"))
        GitCheckoutFixtures.existingCheckout(workspace)
        val git = FakeGit(
            remote = LOCAL_SHA,
            head = LOCAL_SHA,
            statusOutput = " M tracked.txt\n",
        )
        val definition = GitCheckoutStepDefinition(
            executorFactory = { _, _ -> GitCheckoutFixtures.executor(git, temp) },
        )

        val output = runBlocking {
            definition.handler.execute(
                GitCheckoutInput(url = GitCheckoutFixtures.URL, branch = "main"),
                StepHandlerContext(
                    runId = RunId("run-1"),
                    stepIndex = 0,
                    capabilities = GitCheckoutTestCapabilities(
                        ExecutionLocation(WorkspaceLease.Attached(workspace), workspace),
                    ),
                ),
            )
        }

        assertEquals(
            GitLocalState.MODIFIED,
            output.localState,
            "what a consumer reads is the OUTPUT of the Step: a tree that carries changes " +
                "that are not the run's has to be announced as such, and not as a clean " +
                "checkout",
        )
    }

    @Test
    fun `the status that reads the tree leaves the untracked files out`() {
        assertEquals(
            listOf(
                "git",
                "-C",
                workspace().toString(),
                "status",
                "--porcelain",
                "--untracked-files=no",
            ),
            GitLocalState.statusArgv(workspace()),
            "the status has to read the TRACKED content of the tree: an untracked file is " +
                "not a modification of it, and a status that listed them would report a tree " +
                "whose tracked content does not differ as one that does",
        )
    }

    @Test
    fun `a status with no differences reads as a clean tree`() {
        assertEquals(
            GitLocalState.CLEAN,
            GitLocalState.stateOf(""),
            "the porcelain format prints one line per difference and nothing when there is " +
                "none",
        )
    }

    @Test
    fun `a status with a difference reads as a tree that carries work`() {
        assertEquals(
            GitLocalState.MODIFIED,
            GitLocalState.stateOf(" M tracked.txt\n"),
            "one line of difference is a tree whose tracked content differs from its commit",
        )
    }

    /** The workspace of this test: the only directory the checkout may write to. */
    private fun workspace(): Path = temp.resolve("workspace")

    private companion object {
        /** The commit the remote announced. */
        const val REMOTE_SHA = GitCheckoutFixtures.REMOTE_SHA

        /** The commit the local working tree held before the checkout. */
        const val LOCAL_SHA = GitCheckoutFixtures.LOCAL_SHA
    }
}
