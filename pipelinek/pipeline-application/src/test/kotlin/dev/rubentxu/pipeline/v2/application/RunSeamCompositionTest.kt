package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.runtime.PluginComposition
import dev.rubentxu.pipeline.v2.runtime.RuntimeWorkspaceTransport
import dev.rubentxu.pipeline.v2.runtime.observation.WakeupSignal
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The order in which a run composes its seams.
 *
 * ## The guarantee
 *
 * A run asks its credential seam first, admits its plugins second, decides where
 * its steps work third and starts watching the live output last. The order
 * decides WHICH refusal an operator sees when two things are wrong at once, and
 * the two branches that run this product have to answer that question the same
 * way: a run is one run, and the operator is told one story about it.
 *
 * Two refusals can meet at the admission. The credential seam can refuse a run
 * whose store cannot be opened, and the admission can refuse an artifact the
 * operator may not load. Whoever is asked first answers, and the other refusal
 * is never reached. The credential seam is asked first, so both branches answer
 * the same code for the same situation.
 *
 * A refused admission ends the composition: the seams after it never run, and in
 * particular the live output observer is never started, because a run that
 * refuses has nothing to observe and the observer owns a thread.
 *
 * ## Why this instrument is this cheap
 *
 * The four seams are handed to the composition as suppliers, so this instrument
 * supplies four doubles and observes what the composition does with them: the
 * order it asks them in, the exit a refusal carries, and what the composition
 * finally holds. Nothing is booted, no process is started, no directory is
 * created, and the clock never moves: the decision under test is the ORDER, and
 * the order is a fact about the calls.
 */
class RunSeamCompositionTest {

    /** The order in which the composition asked its seams. */
    private val asked: MutableList<String> = mutableListOf()

    private val credentialSeam = CredentialSeam(provider = null, executor = null)

    private val composition = PluginComposition.resolve(null)

    private val transport = RuntimeWorkspaceTransport(
        base = Path.of("/workspace/base"),
        ownership = WorkspaceOwnership.USER,
    )

    private val observer = LiveOutputObserverHandle(wakeups = WakeupSignal(), onClose = {})

    @Test
    fun `the seams are composed in the order every run uses`() {
        composeRunSeams(
            credentials = { asked += "credentials"; credentialSeam },
            admission = { asked += "admission"; PluginAdmission.Admitted(composition) },
            workspaceTransport = { asked += "workspace"; transport },
            outputObserver = { asked += "observer"; observer },
        )

        assertEquals(listOf("credentials", "admission", "workspace", "observer"), asked)
    }

    @Test
    fun `a refused admission ends the composition with its own exit`() {
        val refusal = CliExit.InvalidArgument(message = "the artifact may not be admitted")

        val thrown = assertThrows(CommandRefused::class.java) {
            composeRunSeams(
                credentials = { asked += "credentials"; credentialSeam },
                admission = { asked += "admission"; PluginAdmission.Refused(refusal) },
                workspaceTransport = { asked += "workspace"; transport },
                outputObserver = { asked += "observer"; observer },
            )
        }

        assertSame(refusal, thrown.exit)
        assertEquals(
            listOf("credentials", "admission"),
            asked,
            "a refused admission reached a seam it must not reach",
        )
    }

    @Test
    fun `a credential refusal precedes the admission`() {
        val refusal = CliExit.IncompatibleVersion(message = "the credential store could not be opened")

        val thrown = assertThrows(CommandRefused::class.java) {
            composeRunSeams(
                credentials = { asked += "credentials"; throw CommandRefused(refusal) },
                admission = { asked += "admission"; PluginAdmission.Admitted(composition) },
                workspaceTransport = { asked += "workspace"; transport },
                outputObserver = { asked += "observer"; observer },
            )
        }

        assertSame(refusal, thrown.exit)
        assertEquals(
            listOf("credentials"),
            asked,
            "the admission was asked after the credential seam had already refused",
        )
    }

    @Test
    fun `the composition carries what each seam produced`() {
        val seams = composeRunSeams(
            credentials = { credentialSeam },
            admission = { PluginAdmission.Admitted(composition) },
            workspaceTransport = { transport },
            outputObserver = { observer },
        )

        assertSame(credentialSeam, seams.credentials)
        assertSame(composition, seams.composition)
        assertSame(transport, seams.workspaceTransport)
        assertSame(observer, seams.outputObserver)
    }
}
