package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
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
 * The checkout that refuses to be tricked, and that leaves nothing behind.
 *
 * **The guarantee.** A remote that git would read as an option never becomes one,
 * a branch that git would read as an option is refused, a transport the product
 * does not support is refused, a remote that carries a credential in its argv
 * never reaches a process, the configuration the checkout hands to git is a
 * configuration git accepts, the ssh connection refuses a host whose key changed,
 * and every temporary root the checkout opened is gone when the checkout ends.
 *
 * **Why the argv is the place to look.** git reads every argument that starts with
 * `-` as an option. `--upload-pack=…` is not a repository: it is an instruction to
 * run a program, and with a `file://` remote the program runs on this machine. The
 * `--` before the positionals is what makes the value a value, and the policy is
 * what refuses the value that has no business being there.
 *
 * **Why the configuration is checked with the real git.** The header that was
 * missing its brackets did not weaken the authentication, it disabled it: git
 * refuses a file with a bad line and the whole configuration stops being read. A
 * test that only reads the text would have called that file correct.
 */
class GitCheckoutHardeningTest {

    @TempDir
    lateinit var temp: Path

    /**
     * How many credential directories this test has opened.
     *
     * The applier owns a directory it wipes when it closes, and a test that opens
     * one never closes it. The directories live under the test's own temporary
     * directory, which the framework deletes, so the counter only has to keep the
     * names apart.
     */
    private var appliers = 0

    @Test
    fun `the ls-remote argv separates the positionals from the options`() {
        val runtime = CapturingRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\trefs/heads/main\n")
        val poll = GitPollExecutor(taskRuntime = runtime, controlRoot = temp.resolve("poll"))

        poll.execute(GitRemoteSelection(url = "https://github.com/org/repo.git", branch = "main"))

        assertEquals(
            listOf("git", "ls-remote", "--", "https://github.com/org/repo.git", "refs/heads/main"),
            runtime.argvs.single(),
            "the remote and the branch are positionals, and without `--` git reads a " +
                "remote that starts with `-` as an option. The branch is asked for as " +
                "`refs/heads/<branch>` because `git ls-remote` matches its patterns by " +
                "TAIL: a bare `main` also matches `refs/tags/main`, so a branch that " +
                "exists as a tag would be probed as the tag",
        )
    }

    @Test
    fun `the clone argv separates the positionals from the options`() {
        val runtime = CapturingRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\trefs/heads/main\n")
        val executor = executor(runtime = runtime)
        val url = "https://github.com/org/repo.git"

        executor.execute(request(url))

        val clone = runtime.argvs.first { it.contains("clone") }
        assertEquals(
            listOf("git", "clone", "--branch", "main", "--", url, clone.last()),
            clone,
            "the remote is a positional, and `--` is what keeps a remote that looks like " +
                "an option from becoming one",
        )
    }

    @Test
    fun `a remote that looks like an option is refused before any process is spawned`() {
        val runtime = CapturingRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\trefs/heads/main\n")
        val executor = executor(runtime = runtime)

        val result = executor.execute(request("--upload-pack=touch /tmp/pwned"))

        assertTrue(result.isFailure, "the checkout accepted a remote that git would read as an option")
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(
            message.contains("option"),
            "the refusal must say that git would read the remote as an option; it said: $message",
        )
        assertFalse(
            message.contains("touch /tmp/pwned"),
            "the refusal repeated the command it refused to run; it said: $message",
        )
        assertTrue(
            runtime.argvs.isEmpty(),
            "the checkout spawned a process before refusing the remote: ${runtime.argvs}",
        )
    }

    @Test
    fun `a branch that looks like an option is refused before any process is spawned`() {
        val runtime = CapturingRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\trefs/heads/main\n")
        val executor = executor(runtime = runtime)

        val result = executor.execute(
            request("https://github.com/org/repo.git", branch = "--upload-pack=touch /tmp/pwned"),
        )

        assertTrue(
            result.isFailure,
            "the checkout accepted a branch that git would read as an option",
        )
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(
            message.contains("branch"),
            "the refusal must say that the BRANCH is the option; it said: $message",
        )
        assertFalse(
            message.contains("touch /tmp/pwned"),
            "the refusal repeated the command it refused to run; it said: $message",
        )
        assertTrue(
            runtime.argvs.isEmpty(),
            "the checkout spawned a process before refusing the branch: ${runtime.argvs}",
        )
    }

    @Test
    fun `a remote whose transport is not one of the three is refused before any process is spawned`() {
        val runtime = CapturingRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\trefs/heads/main\n")
        val executor = executor(runtime = runtime)

        val result = executor.execute(request("ftp://github.com/org/repo.git"))

        assertTrue(
            result.isFailure,
            "the checkout accepted a transport it does not support",
        )
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(
            message.contains("allowed transports"),
            "the refusal must name the rule it applied; it said: $message",
        )
        assertTrue(
            runtime.argvs.isEmpty(),
            "the checkout spawned a process before refusing the transport: ${runtime.argvs}",
        )
    }

    @Test
    fun `a remote that carries a credential is refused before the checkout announces itself`() {
        val runtime = CapturingRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\trefs/heads/main\n")
        val executor = executor(runtime = runtime)
        val events = mutableListOf<DomainEvent>()

        val result = executor.execute(
            request("https://user:s3cr3t@github.com/org/repo.git", events = events),
        )

        assertTrue(
            result.isFailure,
            "the checkout accepted a remote that carries a credential in its argv",
        )
        val message = result.exceptionOrNull()!!.message!!
        assertFalse(
            message.contains("s3cr3t"),
            "the refusal repeated the credential it refused to publish; it said: $message",
        )
        assertTrue(
            runtime.argvs.isEmpty(),
            "the checkout spawned a process with a credential in its argv: ${runtime.argvs}",
        )
        assertTrue(
            events.none { it is GitCheckoutStarted },
            "the checkout announced a checkout it then refused to perform: ${events.map { it.kind }}",
        )
    }

    @Test
    fun `the per-host configuration is one git accepts`() {
        val applier = applier()
        applier.apply(USER, PASSWORD.toByteArray(), "https://github.com/org/repo.git")

        val config = Path.of(applier.gitConfigFilePath())
        val text = Files.readString(config)
        assertTrue(
            text.contains("[http \"https://github.com\"]"),
            "the section header needs its brackets: git refuses a line that starts with a " +
                "section name and no brackets, and it refuses the WHOLE file; the file says: $text",
        )
        assertFalse(
            text.contains("helper=store"),
            "the store helper keeps the credential in the plaintext ~/.git-credentials, " +
                "which is a second copy of the secret in a place nobody asked for; the file says: $text",
        )

        val accepted = runGit("config", "--file", config.toString(), "--list")
        assertEquals(
            0,
            accepted.exitCode,
            "git does not accept the configuration the checkout wrote: ${accepted.output}",
        )
    }

    @Test
    fun `the ssh connection refuses a host whose key changed`() {
        val knownHosts = temp.resolve("known_hosts")
        val applier = applier(knownHosts = knownHosts)
        applier.applySsh(SSH_KEY.toByteArray(), null, "ssh://git@github.com/org/repo.git")

        val command = requireNotNull(applier.buildEnv()["GIT_SSH_COMMAND"]) {
            "the ssh transport must put GIT_SSH_COMMAND in the environment it hands over"
        }

        assertTrue(
            command.contains("StrictHostKeyChecking=accept-new"),
            "a host we have not met is accepted and recorded, and a host whose key CHANGED is " +
                "refused; the command was: $command",
        )
        assertFalse(
            command.contains("StrictHostKeyChecking=no"),
            "`no` accepts every key every time, which is the same as trusting whoever answers; " +
                "the command was: $command",
        )
        assertFalse(
            command.contains("UserKnownHostsFile=/dev/null"),
            "a known hosts file that is /dev/null records nothing, so `accept-new` cannot tell " +
                "a first contact from a changed key; the command was: $command",
        )
        assertTrue(
            command.contains("UserKnownHostsFile=\"$knownHosts\""),
            "the known hosts file must be the one the operator named, and quoted; the command " +
                "was: $command",
        )
        val keyPath = applierRoot(applier).resolve(".git-ssh-key")
        assertTrue(
            command.contains("-i \"$keyPath\""),
            "the key path must be quoted: a path with a space in it would otherwise split into " +
                "two arguments; the command was: $command",
        )
    }

    @Test
    fun `the ssh connection records the host keys where the operator's own decisions already live`() {
        val applier = applier()
        applier.applySsh(SSH_KEY.toByteArray(), null, "ssh://git@github.com/org/repo.git")

        val command = requireNotNull(applier.buildEnv()["GIT_SSH_COMMAND"]) {
            "the ssh transport must put GIT_SSH_COMMAND in the environment it hands over"
        }

        // The file ssh records the keys in has to OUTLIVE the run: `accept-new` can
        // only tell a first contact from a changed key if the file still holds the
        // keys it has already seen. A file inside the checkout's own temporary
        // directory starts empty every time, and a file that starts empty every
        // time accepts every key every time.
        val operatorKnownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts")
        assertTrue(
            command.contains("UserKnownHostsFile=\"$operatorKnownHosts\""),
            "with no known hosts file of its own, the checkout records the host keys in the " +
                "operator's own file, which is the one that persists between runs; the command " +
                "was: $command",
        )
    }

    @Test
    fun `closing the checkout removes every temporary root it opened`() {
        val applierRoot = Files.createTempDirectory("scm-git-step")
        val pollRoot = Files.createTempDirectory("git-poll-tasks")
        val changelogRoot = Files.createTempDirectory("git-changelog-tasks")
        val controlRoot = Files.createTempDirectory("git-tasks")
        val executor = GitCheckoutExecutor(
            poll = GitPollExecutor(controlRoot = pollRoot),
            changelog = GitChangelogWriter(controlRoot = changelogRoot),
            credentialsApplier = GitCredentialsApplier(applierRoot),
            gitControlRoot = controlRoot,
        )

        executor.close()

        listOf(applierRoot to "the credential directory", pollRoot to "the poll control root",
            changelogRoot to "the changelog control root", controlRoot to "the executor control root")
            .forEach { (root, what) ->
                assertFalse(
                    Files.exists(root),
                    "$what is still on disk after the checkout ended, and it holds the SSH key " +
                        "and the token",
                )
            }
    }

    private fun applier(knownHosts: Path? = null): GitCredentialsApplier {
        val root = Files.createDirectories(temp.resolve("applier-${appliers++}"))
        return GitCredentialsApplier(
            tempDir = root,
            knownHostsFile = knownHosts,
        )
    }

    /** The directory an applier of this test owns, so the test can look inside it. */
    private fun applierRoot(applier: GitCredentialsApplier): Path =
        Path.of(applier.answerFilePath()).parent

    private fun executor(runtime: CapturingRuntime): GitCheckoutExecutor = GitCheckoutExecutor(
        poll = GitPollExecutor(taskRuntime = runtime, controlRoot = temp.resolve("poll")),
        changelog = GitChangelogWriter(controlRoot = temp.resolve("changelog")),
        credentialsApplier = GitCredentialsApplier(
            tempDir = Files.createDirectories(temp.resolve("creds")),
        ),
        taskRuntime = runtime,
        gitControlRoot = temp.resolve("control"),
    )

    private fun request(
        url: String,
        branch: String = "main",
        events: MutableList<DomainEvent> = mutableListOf(),
    ) = GitCheckoutRequest(
        spec = CheckoutSpec(GitScm(url = url, branch = branch)),
        runId = "run-1",
        workspaceRoot = temp.resolve("workspace"),
        authorizedTarget = authorizedTarget(temp.resolve("workspace")),
        eventSink = object : EventSink {
            override fun append(event: DomainEvent) {
                events.add(event)
            }

            override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
        },
        clock = FIXED_CLOCK,
        secretStore = null,
        stepIndex = 0,
        previousRemoteSha = null,
    )

    /**
     * The authorised destination of a checkout inside [root].
     *
     * It is resolved by the same authority the Step uses, so a test cannot hand
     * the executor a path the workspace would have refused.
     */
    private fun authorizedTarget(root: Path, relativeTargetDir: String = "."): AuthorizedCheckoutTarget =
        AuthorizedCheckoutTarget.resolve(
            ExecutionLocation(WorkspaceLease.Attached(root), root),
            "scm-git.checkout",
            relativeTargetDir,
        )

    /**
     * A runtime that records the argv it was asked to run and answers with a fixed
     * output, so the test can look at the command line the checkout WOULD have run.
     */
    private class CapturingRuntime(private val stdout: String = "") : DurableTaskRuntime {
        val argvs: MutableList<List<String>> = mutableListOf()

        override suspend fun execute(
            request: TaskExecutionRequest,
            outputSink: dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink,
        ): TaskExecutionResult {
            (request.task as? TaskSpec.ExecTask)?.let { argvs.add(it.argv) }
            outputSink.append(
                dev.rubentxu.pipeline.v2.domain.durable.OutputChunk(
                    stream = dev.rubentxu.pipeline.v2.domain.durable.TaskStream.STDOUT,
                    data = stdout.toByteArray(),
                ),
            )
            return TaskExecutionResult(
                exitCode = 0,
                startedAtEpochMs = 0,
                endedAtEpochMs = 0,
            )
        }
    }

    private data class GitResult(val exitCode: Int, val output: String)

    /**
     * Runs the real git, because the question "does git accept this file?" has no
     * answer without git.
     */
    private fun runGit(vararg args: String): GitResult {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return GitResult(process.exitValue(), output)
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

        /** A user name that is not a user name: it never leaves this machine. */
        const val USER = "ci-user"

        /** A password that is not a password: it never leaves this machine. */
        const val PASSWORD = "ci-password"

        /** A key that is not a key: the ssh transport only carries its PATH. */
        const val SSH_KEY = "ci-private-key"
    }
}
