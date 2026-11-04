package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseLiveness
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a run's lease is named after the run, and a run nobody can
 * name never reaches the machine.
 *
 * ## What this test is for
 *
 * The lease of a run is the authority that publishes it. Where that authority
 * lives on disk is decided by the NAME of the run, and a name that were a
 * transformation of the identity could give two runs the same file. Then one
 * run is refused as "already owned" and the other takes the lease over,
 * advancing the fencing token and fencing out a publisher that had done
 * nothing wrong.
 *
 * So two things have to hold, and this file holds both:
 *
 *  - the name of the lease IS the identity, verbatim;
 *  - an identity that cannot carry a name is refused BEFORE a path is built,
 *    so the durable plane never derives a location from it.
 *
 * ## Why the double and not real files
 *
 * The instrument is the paths the store asks the machine about. A real
 * filesystem would answer "the file exists" and say nothing about WHICH file
 * was named, and it could not answer "was the machine touched at all". The
 * double records the paths, so both questions have an answer — with no disk,
 * no process and no clock.
 */
class LeaseNamingTest {

    /**
     * The identities that cannot carry a name. The first one is the pair a
     * folding rule confuses with 'a_b'; the second would escape the lease
     * directory if the name were built without checking.
     */
    private val notNameable = listOf("a/b", "a b", "../escape")

    @Test
    fun `una identidad que no puede nombrar es rechazada sin tocar la maquina`() {
        for (id in notNameable) {
            val files = FakeLeaseFileSystem()
            val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

            val verdict = store.acquire(request(id))

            assertTrue(
                verdict is LeaseAcquisition.Unverifiable,
                "'$id' must not be a lease, but the verdict was $verdict",
            )
            assertEquals(
                0,
                files.directoriesEnsured,
                "'$id' must not even create the lease directory",
            )
            assertEquals(
                emptyList<Path>(),
                files.pathsAsked,
                "'$id' must not reach the machine: no path may be derived from it",
            )
        }
    }

    @Test
    fun `el lease se nombra con la identidad`() {
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        val verdict = store.acquire(request("run-42"))

        assertTrue(verdict is LeaseAcquisition.Acquired, "the verdict was $verdict")
        assertTrue(
            files.pathsAsked.contains(LEASE_DIR.resolve("lease-run-42.lock")),
            "the lock must be named after the run, but the paths were ${files.pathsAsked}",
        )
        assertTrue(
            files.pathsAsked.contains(LEASE_DIR.resolve("lease-run-42.record")),
            "the record must be named after the run, but the paths were ${files.pathsAsked}",
        )
    }

    @Test
    fun `dos identidades que una regla de plegado confundia no nombran el mismo lease`() {
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        val refused = store.acquire(request("a/b"))
        val acquired = store.acquire(request("a_b"))

        assertTrue(refused is LeaseAcquisition.Unverifiable, "'a/b' must not be a lease")
        assertTrue(acquired is LeaseAcquisition.Acquired, "'a_b' must be a lease")
        assertEquals(
            1,
            files.pathsAsked.count { it == LEASE_DIR.resolve("lease-a_b.lock") },
            "'a_b' must own exactly one lock file: 'a/b' must not have reached it",
        )
    }

    @Test
    fun `una identidad que no puede nombrar no tiene registro`() {
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        assertNull(store.observe(RunId("a/b")), "an unnameable run has no record")
        assertFalse(store.isKnown(RunId("a/b")), "an unnameable run is not known")
        assertTrue(
            store.liveness(RunId("a/b")) is LeaseLiveness.Unverifiable,
            "the liveness of an unnameable run cannot be established",
        )
        assertEquals(
            emptyList<Path>(),
            files.pathsAsked,
            "reading about an unnameable run must not derive a path from it",
        )
    }

    private fun request(runId: String): LeaseRequest =
        LeaseRequest(runId = RunId(runId), ownerId = OWNER)

    private companion object {
        val LEASE_DIR: Path = Path.of("leases")
        val OWNER: RunOwnerId = RunOwnerId.of("owner-a")!!
    }
}
