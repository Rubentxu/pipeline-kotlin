package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * A git that does not exist.
 *
 * It records the argv of every command and models the tree it is asked about: the
 * materialisation leaves the tree at the commit it was asked for, the status
 * answers with the state the tree was told to be in, and the tree can be told to
 * end up somewhere else to model a divergence.
 *
 * **It answers PER COMMAND.** A double that answers every question with the same
 * answer is a constant with the shape of a response: the day the product asks a
 * new question, the old answer is false for it, and the failure shows up where the
 * VALUE is published rather than where the command was run. The `ls-remote`, the
 * `rev-parse` and the `status` each answer what they were asked.
 */
internal class FakeGit(
    private val remote: String? = null,
    head: String? = null,
    private val checkoutLandsOn: String? = null,
    private val statusOutput: String? = null,
    private val statusFails: Boolean = false,
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
            "status" -> {
                if (statusFails) {
                    exit(128)
                } else {
                    outputSink.append(OutputChunk.stdout(statusOutput.orEmpty().toByteArray()))
                    exit(0)
                }
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

/**
 * The fixtures the checkout tests share: the workspace, the executor, and the
 * request that asks for a checkout.
 *
 * **Why one way to ask.** A checkout that is asked two different ways is a
 * checkout two tests are about, and the two drift apart the first time the
 * request changes. The request lives here once, and the tests say what they
 * change: the branch they declare, the commit the remote announces, the state of
 * the tree.
 */
internal object GitCheckoutFixtures {

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

    val FIXED_CLOCK: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

    /** A checkout that already exists at [workspace]: the `.git` directory is there. */
    fun existingCheckout(workspace: Path) {
        Files.createDirectories(workspace.resolve(".git"))
    }

    /** The executor the tests ask, wired to the double [git]. */
    fun executor(git: DurableTaskRuntime, temp: Path): GitCheckoutExecutor = GitCheckoutExecutor(
        poll = GitPollExecutor(taskRuntime = git, controlRoot = temp.resolve("poll")),
        changelog = GitChangelogWriter(taskRuntime = git, controlRoot = temp.resolve("changelog")),
        credentialsApplier = null,
        clock = FIXED_CLOCK,
        taskRuntime = git,
        gitControlRoot = temp.resolve("control"),
    )

    /** The request the tests ask with: the checkout of the declared branch of [URL]. */
    fun request(
        workspace: Path,
        poll: Boolean,
        previousRemoteSha: String? = null,
    ): GitCheckoutRequest = GitCheckoutRequest(
        spec = CheckoutSpec(GitScm(url = URL, branch = "main", poll = poll)),
        runId = "run-1",
        workspaceRoot = workspace,
        authorizedTarget = AuthorizedCheckoutTarget.resolve(
            ExecutionLocation(WorkspaceLease.Attached(workspace), workspace),
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
        previousRemoteSha = previousRemoteSha,
    )
}

/**
 * The two capabilities the Step reads, and nothing else.
 *
 * The Step declares them and the handler resolves them; a test that offers
 * anything else would be testing a different admission.
 */
internal class GitCheckoutTestCapabilities(private val location: ExecutionLocation) : StepCapabilityAccess {

    override fun available(): Set<StepCapability> =
        setOf(EXECUTION_LOCATION_CAPABILITY, SCM_GIT_TRANSCRIPT_CAPABILITY)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: StepCapability, type: KClass<T>): T = when (key) {
        EXECUTION_LOCATION_CAPABILITY -> location as T
        SCM_GIT_TRANSCRIPT_CAPABILITY -> GitCheckoutQuietTranscript as T
        else -> throw IllegalArgumentException("unexpected capability: $key")
    }
}

/**
 * A transcript that keeps the bytes and refuses nothing: the refusal is not what
 * these tests are about.
 */
internal object GitCheckoutQuietTranscript : GitTranscript {

    override val secretPatterns: SecretPatternRegistry? = null

    override fun sink(channel: ProcessOutputChannel): ProcessOutputSink =
        object : ProcessOutputSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

            override fun refusal(): ProcessOutputRefusal? = null

            override fun close() = Unit
        }
}
