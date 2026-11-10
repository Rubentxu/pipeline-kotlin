package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The store of leases holds ONE lease at a time.
 *
 * ## The guarantee under trial
 *
 * That acquiring a second lease does not COST the first one. The store keeps
 * the lock it took and the record it wrote in ONE place, because they are the
 * two halves of the same ownership and a release has to give both back
 * together. A second acquisition of another run would have to overwrite them,
 * and overwriting is what loses them.
 *
 * ## The defect this instrument pursues
 *
 * The overwritten lock stays OPEN in this JVM —the kernel keeps the
 * exclusivity until the file is closed— while the record that says who owns
 * the run is replaced by the record of the second run. The first run is then
 * in a state nobody can get out of: a lock that nobody can take and a record
 * that names a live owner. A run in that state can never be closed nor
 * recovered, and that is worse than a run that failed loudly.
 *
 * ## Why the refusal, and not a second slot
 *
 * A store that held several leases would have to give them back one by one,
 * and the release it offers names an OWNER and not a run: with two leases of
 * the same owner, "release" would have to mean two things. The refusal keeps
 * the store's shape —one lease, one record, one release— and moves the
 * decision to the caller, which is the only one that knows whether the second
 * run may wait.
 *
 * ## Why this instrument is a UNIT test
 *
 * Because the guarantee is about the store's own bookkeeping, and the double
 * can see it: the machine's lock table is one entry, and the store's refusal
 * happens BEFORE the machine is touched. A real filesystem would prove that
 * two runs can hold two locks at once —which is true, and is why the store
 * has to refuse one of them.
 */
class LeaseSingleOwnershipTest {

    @Test
    fun `a second lease of another run is refused and the first one is untouched`() {
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        val first = store.acquire(request("run-1"))
        assertInstanceOf(
            LeaseAcquisition.Acquired::class.java,
            first,
            "the first lease is taken, or the row below proves nothing: $first",
        )

        val second = store.acquire(request("run-2"))

        val refused = assertInstanceOf(
            LeaseAcquisition.Unverifiable::class.java,
            second,
            "the store holds one lease at a time, and the second run must be refused: $second",
        )
        assertTrue(
            refused.reason.contains("one at a time"),
            "the refusal says why: ${refused.reason}",
        )
        assertTrue(store.isHolding, "the store must still own the lease it was granted")
        assertTrue(files.lockHeld, "the kernel must still see this process as the holder")
        assertEquals(0, files.locksGivenBack, "the first lease must not be given back")
        assertEquals(1, files.writeCount, "the record of the first run must not be overwritten")
        assertEquals(OWNER, files.record?.ownerId, "the record must still name the first owner")
    }

    @Test
    fun `the run whose lease the store holds can be released afterwards`() {
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)
        store.acquire(request("run-1"))

        store.acquire(request("run-2"))

        store.release(OWNER)

        assertFalse(store.isHolding, "a released store holds nothing")
        assertFalse(files.lockHeld, "the kernel must not still see this process as the holder")
        assertEquals(1, files.locksGivenBack, "the lock must be given back exactly once")
    }

    private fun request(runId: String): LeaseRequest =
        LeaseRequest(runId = RunId(runId), ownerId = OWNER)

    private companion object {
        val LEASE_DIR: Path = Path.of("leases")
        val OWNER: RunOwnerId = RunOwnerId.of("owner-a")!!
    }
}
