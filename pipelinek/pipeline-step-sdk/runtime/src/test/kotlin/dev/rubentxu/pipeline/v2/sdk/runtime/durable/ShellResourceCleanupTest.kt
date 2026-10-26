package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.nio.file.Path

/**
 * The rules that say what a durable shell execution leaves behind are decisions over facts, and the
 * decisions are where this can be wrong without anybody noticing.
 *
 * **The guarantee.** A failed execution keeps its control directory WHOLE, because the exit code,
 * the timeout marker and the heartbeat inside it are the evidence the reconciler reads to tell an
 * expired execution from a lost one; a successful execution gives the directory up, and the console
 * transcript survives that disposal only when the caller asked for it, because the production
 * consumer streams it after the executor returns. The console transcript survives because it is
 * THE transcript of THIS control directory, not because it carries a name; a file with the same
 * name deeper in the tree is not it. The descriptors the launch opened are all released, and one
 * that refuses to close does not keep the others held.
 *
 * **The observable capacity it serves.** A run that ended in a failure can still be reconciled
 * afterwards instead of looking lost, and a run that ended in a success leaves no control directory
 * behind while its transcript is still there for the consumer to stream. Before this, the whole of
 * that rested on inline branches inside one executor method: the only way to observe it was to
 * launch a child, fail it in the right way and look at the files it left behind, and that
 * instrument says THAT the cleanup broke, never WHICH of its rules broke.
 *
 * **Why this instrument and not another.** The rules are pure functions of facts — an exit code,
 * the caller's request, a path — and the release of the descriptors is observable through an
 * in-memory fake, so the cheapest instrument that can fail is a call to each of them. A test that
 * created a real control directory and looked at what survived it would touch the filesystem,
 * which a unit test may not do, and would say the same thing at a higher cost. What is checked
 * here is the verdict those facts feed; the walk that carries it out is observed where it runs,
 * in the scenarios that reconcile a failed run and that stream a successful run's transcript.
 *
 * **What this does not freeze.** That the walk deletes what the rules say it deletes, that the
 * filesystem agrees, and that the ingest agent sees the EOF it waits for: those are the imperative
 * half, and they are observed where they happen, not here. What is checked here is the rule the
 * imperative half applies, which is the part that can be wrong while every observation succeeds.
 */
class ShellResourceCleanupTest {

    private val controlDir: Path = Path.of("/tmp/pipelinek-shell-resource-cleanup/op-42")

    private fun cleanup() = ShellResourceCleanup(controlDir)

    /**
     * A descriptor that records whether it was closed, and refuses to close on demand.
     */
    private class FakeDescriptor(
        /** Whether this descriptor throws instead of closing. */
        private val refusesToClose: Boolean = false,
    ) : Closeable {
        /** How many times this descriptor was asked to close. */
        var closeAttempts: Int = 0
            private set

        override fun close() {
            closeAttempts++
            if (refusesToClose) {
                error("the descriptor refuses to close")
            }
        }
    }

    private fun launch(descriptors: List<Closeable>) = LaunchedShell(
        handle = ProcessHandle.current(),
        transcriptPumps = emptyList(),
        transcriptSinks = emptyList(),
        agentWarmup = descriptors,
    )

    @Test
    fun `a failed execution keeps its control directory`() {
        val fate = ShellResourceCleanup.fateOf(exitCode = 1, keepTranscriptLog = false)

        assertInstanceOf(
            ShellResourceCleanup.ControlDirFate.Retained::class.java,
            fate,
            "a failed execution did not keep its control directory",
        )
    }

    @Test
    fun `an expired execution keeps its control directory`() {
        // The watch publishes -1 for an expired execution, and the expired execution's directory
        // holds the timeout marker the reconciler reads. Reading that marker as a success would
        // delete the proof of the expiry.
        val fate = ShellResourceCleanup.fateOf(exitCode = -1, keepTranscriptLog = false)

        assertInstanceOf(
            ShellResourceCleanup.ControlDirFate.Retained::class.java,
            fate,
            "an expired execution did not keep its control directory",
        )
    }

    @Test
    fun `a failed execution keeps the transcript with the directory`() {
        // The request to keep the transcript decides what happens when the directory is disposed.
        // A failed execution keeps the WHOLE directory, so the request changes nothing.
        val fate = ShellResourceCleanup.fateOf(exitCode = 1, keepTranscriptLog = true)

        assertInstanceOf(
            ShellResourceCleanup.ControlDirFate.Retained::class.java,
            fate,
            "a failed execution did not keep its control directory",
        )
    }

    @Test
    fun `a successful execution disposes its control directory`() {
        val fate = ShellResourceCleanup.fateOf(exitCode = 0, keepTranscriptLog = false)

        val disposed = assertInstanceOf(
            ShellResourceCleanup.ControlDirFate.Disposed::class.java,
            fate,
            "a successful execution did not dispose its control directory",
        )
        assertFalse(
            disposed.consoleLogSurvives,
            "a successful execution kept a transcript nobody reads",
        )
    }

    @Test
    fun `a successful execution keeps the transcript when the caller asks for it`() {
        val fate = ShellResourceCleanup.fateOf(exitCode = 0, keepTranscriptLog = true)

        val disposed = assertInstanceOf(
            ShellResourceCleanup.ControlDirFate.Disposed::class.java,
            fate,
            "a successful execution did not dispose its control directory",
        )
        assertTrue(
            disposed.consoleLogSurvives,
            "a successful execution lost the transcript the consumer streams",
        )
    }

    @Test
    fun `the console transcript of this control directory survives`() {
        assertTrue(
            cleanup().survivesDisposal(ShellProtocolLayout(controlDir).consoleLog),
            "the console transcript of this control directory did not survive",
        )
    }

    @Test
    fun `a file with the same name deeper in the tree does not survive`() {
        // The transcript survives because it is THE transcript of THIS control directory. A file
        // that happens to carry the same name elsewhere is not it.
        val nested = controlDir.resolve("nested").resolve(DurableShellFiles.CONSOLE_LOG)

        assertFalse(
            cleanup().survivesDisposal(nested),
            "a file with the transcript's name deeper in the tree survived the disposal",
        )
    }

    @Test
    fun `another file of the control directory does not survive`() {
        assertFalse(
            cleanup().survivesDisposal(ShellProtocolLayout(controlDir).resultFile),
            "the exit code of the execution survived the disposal of its directory",
        )
    }

    @Test
    fun `every descriptor of the launch is released`() {
        val first = FakeDescriptor()
        val second = FakeDescriptor()

        cleanup().release(launch(listOf(first, second)))

        assertEquals(1, first.closeAttempts, "the first descriptor was not released")
        assertEquals(1, second.closeAttempts, "the second descriptor was not released")
    }

    @Test
    fun `a descriptor that refuses to close does not keep the others`() {
        val refusing = FakeDescriptor(refusesToClose = true)
        val following = FakeDescriptor()

        cleanup().release(launch(listOf(refusing, following)))

        assertEquals(1, refusing.closeAttempts, "the refusing descriptor was not asked to close")
        assertEquals(1, following.closeAttempts, "the descriptor after the refusing one was not released")
    }

    @Test
    fun `a launch that never happened releases nothing`() {
        // Nothing was opened, so there is nothing to release. The observation is that the call
        // RETURNS — a release that dereferenced the absent launch would fail on this line — and
        // that the descriptors of a launch that did happen are still released afterwards.
        val descriptor = FakeDescriptor()

        cleanup().release(null)
        cleanup().release(launch(listOf(descriptor)))

        assertEquals(1, descriptor.closeAttempts, "the descriptor of the launch that did happen was not released")
    }
}
