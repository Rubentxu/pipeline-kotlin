package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The checkout that respects what the run declared: the remote, the branch, and
 * whether the remote may be asked at all.
 *
 * **The guarantee.** The command line of the checkout says what the run
 * declared and nothing else. A checkout that declared `poll=false` does not ask
 * the remote; a download names the remote the run declared and not the one the
 * repository happens to have; the materialisation leaves the DECLARED branch at
 * the commit the remote declared; and the SHA the checkout publishes is the one
 * the working tree holds, read back after the fact.
 *
 * **Why the instrument is a double and not a repository.** The facts that decide
 * this guarantee are the command line git receives and the state the tree ends
 * up in. A double that records the argv and models the tree observes exactly
 * those two facts, and it observes them in the lane the rules allow: a test
 * against a real repository would be the E2E layer AGENTS.md section 2
 * forbids, and it would prove the same thing by the same call.
 *
 * **Why the double is not a second instrument for a guarantee already covered.**
 * It is the only instrument for these four guarantees: no other test observes
 * which commands the checkout runs, and a test that read the source instead
 * would certify that the source says what the test expects, which is what the
 * source says even when it is broken.
 */
class GitCheckoutRemoteContractTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `poll=false does not ask the remote what the branch head is`() {
        val git = FakeGit(head = LOCAL_SHA)
        val executor = executor(git)
        existingCheckout()

        val result = executor.execute(request(poll = false))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        assertTrue(
            git.argvs.none { it.contains("ls-remote") },
            "a checkout that was told not to poll the remote asked it anyway: ${git.argvs}",
        )
        assertTrue(
            git.argvs.none { it.contains("fetch") },
            "the remote was not asked for the head, and it was still asked for the objects: " +
                "${git.argvs}",
        )
    }

    @Test
    fun `poll=false publishes the local state and says that it is local`() {
        val git = FakeGit(head = LOCAL_SHA)
        val executor = executor(git)
        existingCheckout()

        val result = executor.execute(request(poll = false))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        assertEquals(
            LOCAL_SHA,
            result.getOrNull()!!.sha,
            "the published state must be the commit the local working tree holds",
        )
        assertFalse(
            result.getOrNull()!!.remotePolled,
            "the record says the remote was polled, so a reader cannot tell a local state " +
                "from a remote one",
        )
    }

    @Test
    fun `the download names the declared remote and the declared ref`() {
        val git = FakeGit(remote = REMOTE_SHA, head = LOCAL_SHA)
        val executor = executor(git)
        existingCheckout()

        val result = executor.execute(request(poll = true))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        val fetch = git.argvs.firstOrNull { it.contains("fetch") }
        assertEquals(
            listOf("git", "-C", workspace().toString(), "fetch", "--", URL, "refs/heads/main"),
            fetch,
            "the download has to name the remote the run declared: a `git fetch` without a " +
                "remote downloads from the `origin` the repository happens to have, which may " +
                "be another remote entirely",
        )
    }

    @Test
    fun `the materialisation leaves the declared branch at the declared commit`() {
        val git = FakeGit(remote = REMOTE_SHA, head = LOCAL_SHA)
        val executor = executor(git)
        existingCheckout()

        val result = executor.execute(request(poll = true))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        val materialise = git.argvs.firstOrNull { it.contains("checkout") }
        assertEquals(
            listOf("git", "-C", workspace().toString(), "checkout", "--force", "-B", "main", REMOTE_SHA),
            materialise,
            "the declared branch has to be the one that moves to the declared commit",
        )
        assertTrue(
            git.argvs.none { it.contains("reset") },
            "a `git reset --hard` moves the branch the checkout HAPPENS to be on: a workspace " +
                "on another branch would have that branch rewritten instead of the declared one. " +
                "The commands were: ${git.argvs}",
        )
    }

    @Test
    fun `a tree that does not hold the declared commit is not announced as a success`() {
        val git = FakeGit(remote = REMOTE_SHA, head = LOCAL_SHA, checkoutLandsOn = OTHER_SHA)
        val executor = executor(git)
        existingCheckout()

        val result = executor.execute(request(poll = true))

        assertTrue(
            result.isFailure,
            "the checkout announced a commit the working tree does not hold: ${result.getOrNull()}",
        )
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("did not obtain the objects"),
            "the failure has to say that the objects were not obtained; it said: " +
                "${result.exceptionOrNull()!!.message}",
        )
    }

    @Test
    fun `the published commit is the one the tree holds, not the one the remote announced`() {
        // The remote moved between the probe and the clone: the probe announced one commit
        // and the clone materialised another. The state of the checkout is the one the tree
        // holds.
        val git = FakeGit(remote = REMOTE_SHA, head = CLONED_SHA)
        val executor = executor(git)

        val result = executor.execute(request(poll = true))

        assertTrue(result.isSuccess, "the checkout failed: ${result.exceptionOrNull()?.message}")
        assertEquals(
            CLONED_SHA,
            result.getOrNull()!!.sha,
            "the published commit has to be the one the working tree holds",
        )
    }

    @Test
    fun `a clone whose commit cannot be read fails instead of publishing the announced one`() {
        val git = FakeGit(remote = REMOTE_SHA, head = null)
        val executor = executor(git)

        val result = executor.execute(request(poll = true))

        assertTrue(
            result.isFailure,
            "the checkout published the commit the remote announced even though it could not " +
                "read the tree: ${result.getOrNull()}",
        )
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("could not be read back"),
            "the failure has to say that the tree could not be read; it said: " +
                "${result.exceptionOrNull()!!.message}",
        )
    }

    @Test
    fun `an answer that is not a commit id never reaches the command line`() {
        val git = FakeGit(remote = "--upload-pack=touch /tmp/pwned", head = LOCAL_SHA)
        val executor = executor(git)
        existingCheckout()

        val result = executor.execute(request(poll = true))

        assertTrue(result.isFailure, "the checkout accepted an answer that is not a commit id")
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("not a commit id"),
            "the failure has to name what was wrong with the answer; it said: " +
                "${result.exceptionOrNull()!!.message}",
        )
        assertTrue(
            git.argvs.none { it.any { token -> token.contains("upload-pack") } },
            "the answer of the remote became an argument of git: ${git.argvs}",
        )
    }

    /** The workspace of this test: the only directory the checkout may write to. */
    private fun workspace(): Path = temp.resolve("workspace")

    /** A checkout that already exists: the `.git` directory is there. */
    private fun existingCheckout() {
        Files.createDirectories(workspace().resolve(".git"))
    }

    private fun executor(git: FakeGit): GitCheckoutExecutor = GitCheckoutExecutor(
        poll = GitPollExecutor(taskRuntime = git, controlRoot = temp.resolve("poll")),
        changelog = GitChangelogWriter(taskRuntime = git, controlRoot = temp.resolve("changelog")),
        credentialsApplier = null,
        clock = FIXED_CLOCK,
        taskRuntime = git,
        gitControlRoot = temp.resolve("control"),
    )

    private fun request(poll: Boolean) = GitCheckoutRequest(
        spec = CheckoutSpec(GitScm(url = URL, branch = "main", poll = poll)),
        runId = "run-1",
        workspaceRoot = temp.resolve("workspace"),
        authorizedTarget = AuthorizedCheckoutTarget.resolve(
            ExecutionLocation(WorkspaceLease.Attached(temp.resolve("workspace")), temp.resolve("workspace")),
            "scm-git.checkout",
            ".",
        ),
        eventSink = object : EventSink {
            override fun append(event: DomainEvent) = Unit

            override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()
        },
        clock = FIXED_CLOCK,
        secretStore = null,
        stepIndex = 0,
        previousRemoteSha = null,
    )

    /**
     * A git that does not exist.
     *
     * It records the argv of every command and models the tree: the materialisation
     * leaves the tree at the commit it was asked for, and the tree can be told to
     * end up somewhere else to model a divergence.
     */
    private class FakeGit(
        private val remote: String? = REMOTE_SHA,
        private val head: String? = null,
        private val checkoutLandsOn: String? = null,
    ) : DurableTaskRuntime {
        val argvs: MutableList<List<String>> = mutableListOf()

        private var treeHead: String? = head

        override suspend fun execute(
            request: TaskExecutionRequest,
            outputSink: ExecutionOutputSink,
        ): TaskExecutionResult {
            val argv = (request.task as TaskSpec.ExecTask).argv
            argvs.add(argv)
            return when (commandOf(argv)) {
                "ls-remote" -> {
                    val answer = remote ?: return exit(0)
                    outputSink.append(OutputChunk.stdout("$answer\trefs/heads/main\n".toByteArray()))
                    exit(0)
                }
                "rev-parse" -> {
                    val answer = treeHead ?: return exit(128)
                    outputSink.append(OutputChunk.stdout("$answer\n".toByteArray()))
                    exit(0)
                }
                "checkout" -> {
                    treeHead = checkoutLandsOn ?: argv.last()
                    exit(0)
                }
                else -> exit(0)
            }
        }

        private fun exit(code: Int): TaskExecutionResult = TaskExecutionResult(
            exitCode = code,
            startedAtEpochMs = 0,
            endedAtEpochMs = 0,
        )

        private companion object {
            /**
             * The git subcommand of an argv, with the `-C <dir>` prefix skipped.
             */
            fun commandOf(argv: List<String>): String {
                var i = 1
                while (i < argv.size) {
                    val token = argv[i]
                    if (token == "-C") {
                        i += 2
                        continue
                    }
                    return token
                }
                return ""
            }
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

        /** A remote that is not a remote: it never leaves this machine. */
        const val URL = "https://example.invalid/org/repo.git"

        /** The commit the remote announced. */
        const val REMOTE_SHA = "1111111111111111111111111111111111111111"

        /** The commit the local working tree held before the checkout. */
        const val LOCAL_SHA = "2222222222222222222222222222222222222222"

        /** The commit the materialisation left the tree on, when it is not the requested one. */
        const val OTHER_SHA = "3333333333333333333333333333333333333333"

        /** The commit the clone left the tree on: the remote moved between the probe and it. */
        const val CLONED_SHA = "4444444444444444444444444444444444444444"
    }
}
