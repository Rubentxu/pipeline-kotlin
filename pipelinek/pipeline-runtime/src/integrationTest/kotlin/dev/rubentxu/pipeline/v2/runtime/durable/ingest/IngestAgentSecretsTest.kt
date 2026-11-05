package dev.rubentxu.pipeline.v2.runtime.durable.ingest

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.runtime.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.AgentRendezvous
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShellProtocolLayout
import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The two guarantees of the ingest agent's secrets path.
 *
 * **The guarantees.** A rendezvous that is not a named pipe is refused, and the bytes of that
 * path never reach the Output Plane. What the agent commits to the Output Plane is redacted: the
 * secret the child was given is in neither the store nor the disk.
 *
 * **The observable capacity it serves.** A step whose child printed a credential leaves a
 * transcript that the operator can read, and leaves nothing behind that holds the credential
 * itself. Both halves are one promise about the same run, and the half that fails is the half that
 * cannot be taken back: a byte written to the disk outlives the run that wrote it.
 *
 * **Why this instrument and not another.** Both guarantees are about a boundary the agent does not
 * own. The first is about the TYPE of a path on the filesystem, and a unit test may not touch the
 * filesystem. The second is about what lands in the Output Plane, which is a real store over a real
 * directory. The unit suite of this module holds the pure decisions of the runtime; the redactor's
 * own algorithm is certified where it lives, in the credentials module. What had no witness was
 * the AGENT, which is where the two halves meet.
 *
 * **What is faked, and what is not.** The world outside the modules is faked: the child's side of
 * the rendezvous is a channel this test owns, and the Output Plane lives in a temporary directory
 * this test owns. Nothing that belongs to the agent, to the redactor, to the ingress or to the
 * store is faked: the bytes that reach the store are the bytes the agent wrote, and the oracle
 * reads them back from the store and from the disk rather than from the code that wrote them.
 *
 * **What this does not freeze.** The wording of the refusal, and the number of probe lines the
 * agent prints. What is frozen is the refusal itself, and what the store holds.
 *
 * **Why there is no end-to-end scenario.** Draining a real named pipe written by a real child
 * needs a second process, and this repository has no layer for that. What these two checks hold is
 * the agent at both of its edges: where its bytes come from, and what it commits.
 */
class IngestAgentSecretsTest {

    @TempDir
    lateinit var root: Path

    private val runId = "run-1"

    private val opId = "op-1"

    /** The secret the child is given, and the one that must not survive the drain. */
    private val secret = "GHS_DBG_SECRET"

    @AfterEach
    fun forgetThePlanes() {
        OutputPlaneProvider.forgetAll()
    }

    /**
     * The control directory of the operation: the root the agent's Output Plane lives under.
     */
    private fun controlRoot(): Path = root.resolve("control").also { Files.createDirectories(it) }

    /**
     * The rendezvous of the operation, over the given FIFO directory.
     */
    private fun rendezvous(fifoDir: Path): AgentRendezvous.Args = AgentRendezvous.Args(
        controlDirRoot = controlRoot(),
        runId = runId,
        opId = opId,
        fifoDir = fifoDir,
        readyMarker = controlRoot().resolve("agent.ready"),
    )

    /**
     * The environment the agent reads its secret from.
     *
     * The environment is where the secret travels because a command-line argument is readable by
     * every user on the host; the test hands the agent the same map the process would carry.
     */
    private fun environmentWithTheSecret(): Map<String, String> =
        mapOf(AgentRendezvous.AGENT_SECRET_ENV to secret)

    /**
     * The child's side of the rendezvous: one channel per channel, holding the child's bytes and
     * then the end of them.
     *
     * The two channels carry DIFFERENT text, so a reader can tell which channel's bytes landed in
     * which stream: attribution is part of what the agent promises, and a test that gave both
     * channels the same bytes could not see it break.
     */
    private fun channelsOf(stdout: String, stderr: String): Map<ProcessOutputChannel, ReadableByteChannel> =
        mapOf(
            ProcessOutputChannel.STDOUT to channelOf(stdout),
            ProcessOutputChannel.STDERR to channelOf(stderr),
        )

    private fun channelOf(text: String): ReadableByteChannel =
        Channels.newChannel(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    /**
     * The files the Output Plane holds, read without asking the store.
     *
     * The store's own reader is the product's way of seeing the bytes; this is the other way, and
     * it is the one that answers "is the secret ON THE DISK", which is the question the guarantee
     * is really about.
     */
    private fun outputPlaneFiles(): List<Path> {
        val plane = controlRoot().resolve(OutputPlaneProvider.OUTPUT_DIR)
        if (!Files.isDirectory(plane)) return emptyList()
        return Files.walk(plane).use { paths ->
            paths.iterator().asSequence().filter { Files.isRegularFile(it) }.toList()
        }
    }

    private fun assertTheSecretIsNotOnDisk() {
        val needle = secret.toByteArray(Charsets.UTF_8)
        outputPlaneFiles().forEach { file ->
            assertFalse(
                holds(Files.readAllBytes(file), needle),
                "the secret is on the disk, in $file",
            )
        }
    }

    /**
     * Whether [needle] appears in [haystack], byte by byte.
     */
    private fun holds(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (start in haystack.indices) {
            if (start + needle.size > haystack.size) return false
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun `a rendezvous that is not a named pipe is refused, and its bytes never reach the Output Plane`() {
        val fifoDir = controlRoot().resolve("fifos")
        Files.createDirectories(fifoDir)
        // The spool the child would have written to: a REGULAR FILE at the FIFO's path, holding
        // the child's own bytes. Draining it would put those bytes on the disk, unredacted.
        Files.writeString(fifoDir.resolve(ShellProtocolLayout.STDOUT_FIFO), "child said $secret\n")

        val refused = assertThrows(IllegalArgumentException::class.java) {
            ObsPc2IngestAgent.ingest(
                rendezvous = rendezvous(fifoDir),
                environment = environmentWithTheSecret(),
                openChannels = ObsPc2IngestAgent::openChannels,
            )
        }

        assertTrue(
            refused.message.orEmpty().contains(ShellProtocolLayout.STDOUT_FIFO),
            "the refusal names the path it refused: ${refused.message}",
        )

        // The Output Plane holds no trace of the refused operation: its stream was never declared,
        // so not one byte of it was ever committed.
        val store = OutputPlaneProvider.storeForReading(controlRoot())
        val stdout = OutputPlaneProvider.streamsOf(runId, opId).stdout.stream
        assertNull(store.committedExtent(stdout), "the refused operation committed bytes")
        assertTrue(
            store.frameIndex().streamsOfRun(runId).isEmpty(),
            "the refused operation declared a stream",
        )

        // And the bytes of the spool are nowhere: neither in the store nor on the disk.
        assertTheSecretIsNotOnDisk()
    }

    @Test
    fun `what the agent commits to the Output Plane is redacted, and the secret is not on the disk`() {
        val fifoDir = controlRoot().resolve("fifos")
        Files.createDirectories(fifoDir)

        ObsPc2IngestAgent.ingest(
            rendezvous = rendezvous(fifoDir),
            environment = environmentWithTheSecret(),
            openChannels = { _ ->
                channelsOf(
                    stdout = "the child said hello, and the secret was $secret\n",
                    stderr = "the child complained, and the secret was $secret\n",
                )
            },
        )

        val store = OutputPlaneProvider.storeForReading(controlRoot())
        val streams = OutputPlaneProvider.streamsOf(runId, opId)

        val stdoutExtent = store.committedExtent(streams.stdout.stream)
            ?: fail("the agent committed nothing to ${streams.stdout.stream}")
        val stdoutPage = assertInstanceOf(
            OutputReadResult.Page::class.java,
            store.readRange(streams.stdout.stream, 0L, stdoutExtent),
        )
        val stdoutText = String(stdoutPage.page.bytes, Charsets.UTF_8)

        val stderrExtent = store.committedExtent(streams.stderr.stream)
            ?: fail("the agent committed nothing to ${streams.stderr.stream}")
        val stderrPage = assertInstanceOf(
            OutputReadResult.Page::class.java,
            store.readRange(streams.stderr.stream, 0L, stderrExtent),
        )
        val stderrText = String(stderrPage.page.bytes, Charsets.UTF_8)

        // The transcript is there: the child's own words, on the channel they arrived on.
        assertTrue(stdoutText.contains("the child said hello"), "the stdout transcript is not there: $stdoutText")
        assertTrue(stderrText.contains("the child complained"), "the stderr transcript is not there: $stderrText")

        // And the secret is not, on either channel: what crossed the rendezvous was redacted.
        assertFalse(stdoutText.contains(secret), "the secret reached the stdout stream: $stdoutText")
        assertFalse(stderrText.contains(secret), "the secret reached the stderr stream: $stderrText")
        assertTrue(stdoutText.contains(SecretPatternRegistry.SCRUB_MARKER), "the secret was not scrubbed: $stdoutText")
        assertTrue(stderrText.contains(SecretPatternRegistry.SCRUB_MARKER), "the secret was not scrubbed: $stderrText")

        // And it is not on the disk either, which is the half no reader of the transcript sees.
        assertTheSecretIsNotOnDisk()
    }
}
