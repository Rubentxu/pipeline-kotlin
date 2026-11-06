package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.OUTPUT_PERSISTENCE_REFUSED
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * The git transcript's guarantee: what a git command printed reaches the host's destination,
 * with the run's secrets removed BEFORE the bytes leave the plugin, and a destination that
 * refuses ends the command instead of leaving a short, plausible transcript behind.
 *
 * ## Why these rows and not others
 * The guarantee a reader relies on is "the answer git gave is recoverable from the run's Output
 * Plane". It breaks in three distinguishable ways, and each way gets its own row:
 *  - the bytes never arrive (the publication is missing) -> R1
 *  - the bytes arrive but the secret travelled with them (the redaction is missing) -> R4
 *  - the bytes arrive but the transcript is SHORT (the destination refused) -> R1
 * R2 protects the OTHER half of the same fact: publishing does not REPLACE the capture that the
 * plugin's own diagnostics read. R3 protects the third half: the executor still ANSWERS with
 * what it read.
 *
 * ## Why R1 holds two assertions and R4 holds two as well
 * Both are pairs that the SAME mechanism moves. A refusal can only appear once the destination
 * has been handed bytes, so "the bytes arrive" and "a refusal ends the command" are two faces of
 * one mechanism: a mutation of the publication moves both. And "the bytes arrive" and "the
 * transcript says exactly what the process said" are two faces of the same arrival. A row that
 * asserts two of them fails for two indistinguishable reasons — and, worse, one mutation would
 * light two rows and the focal mutation would stop proving anything. So each pair lives in ONE
 * row: the pair is measured together, and every other row stays independent of the publication.
 *
 * ## Why the fakes are in memory
 * The guarantee is about bytes and their destination, not about git, a filesystem or a clock. The
 * runtime is therefore a fake that emits chunks, the destination is a sink that keeps bytes, and
 * the secret is a literal registered in a REAL [SecretPatternRegistry] — so the oracle of the
 * redaction row is the SHELL'S OWN redactor, not a second implementation of it that could agree
 * with a broken one.
 */
class GitTranscriptTest {

    /**
     * R1 — the publication: the bytes arrive, and a refusal ends the command.
     *
     * The row goes through [GitPollExecutor] because that is the real path: the poll is the
     * first thing a checkout runs, and it is the one whose output carries the answer ("which
     * SHA is on that branch?") and the authentication error that ends the checkout. A test that
     * called the publication helper directly would pass even if the executor forgot to pass the
     * destination down — and that is exactly the seam this row exists to hold.
     *
     * The first assertion is the one AC#6 names: the destination holds what the command printed.
     * The second is the other face of the same mechanism: when the destination REFUSES, the bytes
     * left the pipe and this command's transcript is short, ordered and plausible — the hardest
     * kind of wrong. The command's own answer is still true, but the record of WHY is gone, so
     * the run must fail instead of claiming a transcript nobody can read.
     */
    @Test
    @DisplayName("what git printed reaches the host's destination, and a refusing destination ends the command")
    fun `the publication delivers the transcript and turns a refusal into a failure`() {
        val transcript = RecordingTranscript()
        val runtime = FakeTaskRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\tHEAD\n")

        poll(runtime, transcript)

        assertEquals(
            "dddddddddddddddddddddddddddddddddddddddd\tHEAD\n",
            transcript.text(ProcessOutputChannel.STDOUT),
            "what the command printed must be readable in the host's destination",
        )

        val refused = poll(FakeTaskRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\tHEAD\n"), RefusingTranscript())

        val cause = refused.exceptionOrNull()
        assertTrue(
            cause is GitTranscriptRefused,
            "a destination that refuses must end the command, but it ended with: $cause",
        )
        assertTrue(
            cause!!.message!!.contains(OUTPUT_PERSISTENCE_REFUSED),
            "the failure must name the persistence refusal: ${cause.message}",
        )
    }

    /**
     * R2 — publishing does not consume the capture.
     *
     * The plugin reads the same bytes for its own diagnostics (which SHA, which error). A
     * publication that took the capture away would leave the plugin unable to answer, and the
     * guarantee would hold for the reader at the cost of the writer.
     */
    @Test
    @DisplayName("the captured text the diagnostic reads survives the publication")
    fun `the capture still holds the stderr`() {
        val transcript = RecordingTranscript()
        val runtime = FakeTaskRuntime(stderr = "fatal: could not read Username\n")

        val captured = runBlocking { runtime.runCapturedTo(request(), transcript) }

        assertEquals(
            "fatal: could not read Username\n",
            captured.stderr,
            "the capture must still carry the whole stderr the diagnostic reads",
        )
    }

    /**
     * R3 — the poll still answers.
     *
     * The publication is an ADDITION to the capture, never a replacement: the poll reads the
     * SHA out of the captured text, so a publication that consumed the capture would leave the
     * checkout without an answer. This row is the regression guard on the executor's own
     * behaviour after the destination was added to it.
     */
    @Test
    @DisplayName("the poll still answers with the SHA it read")
    fun `the poll still answers with the sha it read`() {
        val transcript = RecordingTranscript()
        val runtime = FakeTaskRuntime(stdout = "dddddddddddddddddddddddddddddddddddddddd\tHEAD\n")

        val result = poll(runtime, transcript)

        assertTrue(result.isSuccess, "the poll must succeed: $result")
        assertEquals("dddddddddddddddddddddddddddddddddddddddd", result.getOrNull())
    }

    /**
     * R4 — the transcript says what the process said, minus the secrets.
     *
     * Two parts, because the guarantee has two halves that fail for unrelated reasons:
     *  - the MASKING is exact. The oracle is [StreamingRedactor.wrap], the very path the shell's
     *    own transcript pump uses: both paths share one state machine, so equality here means
     *    "the git transcript masks exactly what the shell masks" — not "the git transcript masks
     *    what this test believes should be masked". A redactor that merely removed the secret
     *    would leave a transcript the process never wrote, and that is a wrongness this part is
     *    able to see.
     *  - the PATH carries the registry. The transcript the executor publishes to knows the run's
     *    secrets; a host that handed git a transcript without one would publish the secret in
     *    clear and the writer would have nothing to mask with.
     *
     * The exactness is measured ON THE WRITER, fed directly, and not through the path. Measured
     * through the path it would also be asserting that the bytes ARRIVED, and then the single
     * mutation of the publication would light this row as well — the focal mutation would stop
     * being one row. The writer's own contract is the exactness; the arrival is R1's.
     */
    @Test
    @DisplayName("the destination carries the same bytes the shell's redactor would produce")
    fun `the transcript is masked exactly like the shell masks`() {
        val registry = SecretPatternRegistry().apply { addSecretValue(SECRET) }
        val raw = "remote: rejected token=$SECRET for this repository\n"
        val bytes = raw.toByteArray(Charsets.UTF_8)

        val transcript = RecordingTranscript(secretPatterns = registry)
        val writer = GitTranscriptWriter(transcript)
        runBlocking { writer.append(OutputChunk.stderr(bytes)) }
        writer.finish()

        val expected = StreamingRedactor(registry)
            .wrap(ByteArrayInputStream(bytes))
            .readBytes()
        assertArrayEquals(
            expected,
            transcript.bytes(ProcessOutputChannel.STDERR),
            "the destination must carry the same bytes the shell's own redactor would produce",
        )

        val published = RecordingTranscript(secretPatterns = registry)
        runBlocking {
            FakeTaskRuntime(stderr = raw).runCapturedTo(request(), published)
        }
        assertFalse(
            published.text(ProcessOutputChannel.STDERR).contains(SECRET),
            "the secret must not appear in clear in the destination",
        )
    }

    private companion object {

        /** Long enough to be registrable: short literals are deliberately NOT masked. */
        const val SECRET: String = "ghp_AAAABBBBCCCCDDDDEEEEFFFF0123456789"

        fun request(): TaskExecutionRequest = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv = listOf("git", "ls-remote", "file:///nowhere/repo.git")),
            runId = RunId("git-transcript-test"),
            opId = "git-transcript-test-op",
        )

        /**
         * Runs the REAL poll executor over the fake runtime, with the host's destination
         * attached. The control root is given explicitly so the executor's default — a real
         * temporary directory — is never created: a unit test that leaves a directory behind
         * is not a unit test.
         */
        fun poll(runtime: DurableTaskRuntime, transcript: GitTranscript): Result<String> =
            GitPollExecutor(
                taskRuntime = runtime,
                controlRoot = Path.of("/nonexistent/git-poll-control-root"),
            ).execute(
                selection = GitRemoteSelection(url = "file:///nowhere/repo.git", branch = "main"),
                credentialsApplier = null,
                transcript = transcript,
            )
    }
}

/**
 * A runtime that emits the given text and exits. No process, no filesystem, no clock.
 */
private class FakeTaskRuntime(
    private val stdout: String = "",
    private val stderr: String = "",
    private val exitCode: Int = 0,
) : DurableTaskRuntime {
    override suspend fun execute(
        request: TaskExecutionRequest,
        outputSink: ExecutionOutputSink,
    ): TaskExecutionResult {
        if (stdout.isNotEmpty()) {
            outputSink.append(OutputChunk.stdout(stdout.toByteArray(Charsets.UTF_8)))
        }
        if (stderr.isNotEmpty()) {
            outputSink.append(OutputChunk.stderr(stderr.toByteArray(Charsets.UTF_8)))
        }
        return TaskExecutionResult(
            exitCode = exitCode,
            startedAtEpochMs = 0,
            endedAtEpochMs = 0,
        )
    }
}

/**
 * A destination that keeps the bytes it is handed, so a test can read what the plugin
 * published. One sink per channel, kept — the same discipline the real destinations follow,
 * because the refusal is read from the very sink that refused the write.
 */
private class RecordingTranscript(
    override val secretPatterns: SecretPatternRegistry? = null,
) : GitTranscript {

    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()
    private val sinks = mutableMapOf<ProcessOutputChannel, ProcessOutputSink>()

    override fun sink(channel: ProcessOutputChannel): ProcessOutputSink =
        sinks.getOrPut(channel) { CollectingSink(channel) }

    fun bytes(channel: ProcessOutputChannel): ByteArray = when (channel) {
        ProcessOutputChannel.STDOUT -> stdout.toByteArray()
        ProcessOutputChannel.STDERR -> stderr.toByteArray()
    }

    fun text(channel: ProcessOutputChannel): String =
        String(bytes(channel), Charsets.UTF_8)

    private inner class CollectingSink(
        private val channel: ProcessOutputChannel,
    ) : ProcessOutputSink {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            when (channel) {
                ProcessOutputChannel.STDOUT -> stdout.write(bytes, offset, length)
                ProcessOutputChannel.STDERR -> stderr.write(bytes, offset, length)
            }
        }

        override fun refusal(): ProcessOutputRefusal? = null

        override fun close() = Unit
    }
}

/**
 * A destination that refuses every write: the bytes are gone from the pipe and nothing can
 * recover them. The refusal is reported, not thrown, because that is how the real sinks
 * report it — the sink accepted responsibility for the bytes and could not make them durable.
 */
private class RefusingTranscript : GitTranscript {

    override val secretPatterns: SecretPatternRegistry? = null

    private val sinks = mutableMapOf<ProcessOutputChannel, ProcessOutputSink>()

    override fun sink(channel: ProcessOutputChannel): ProcessOutputSink =
        sinks.getOrPut(channel) { RefusingSink() }

    private class RefusingSink : ProcessOutputSink {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

        override fun refusal(): ProcessOutputRefusal = ProcessOutputRefusal.WriteFailed(
            detail = "the test destination refused the write",
            cause = IllegalStateException("refused by the test destination"),
        )

        override fun close() = Unit
    }
}
