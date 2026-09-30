package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S2-R0 — the cross-process ownership canary, driven through the REAL adapter.
 *
 * The unit tests prove the decider's logic. These prove the parts only a real
 * filesystem can show:
 *
 *  - the OS lock is actually exclusive between two *processes*, not just two
 *    instances in one JVM (the gap `DbLock` cannot close);
 *  - a SIGKILLed owner's run is recoverable, because the kernel releases the
 *    lock even though the owner never got to run a release handler;
 *  - the fencing token actually advances across that takeover, so the dead
 *    owner's authority is detectably stale.
 *
 * The last one is the point. A lock that is simply released on process death
 * prevents two writers but says nothing about an owner that was *paused*: the
 * token is what distinguishes "I am the authority" from "I once was".
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FileBackedRunExecutionLeaseStoreTest {

    private fun owner(raw: String): RunOwnerId =
        requireNotNull(RunOwnerId.of(raw)) { "invalid test owner id: $raw" }

    @Test
    @DisplayName("a second store in the same JVM is refused while the first holds the lease")
    fun second_store_same_jvm_is_refused(@org.junit.jupiter.api.io.TempDir dir: Path) {
        val runId = "run-same-jvm"
        val first = FileBackedRunExecutionLeaseStore(dir)
        val second = FileBackedRunExecutionLeaseStore(dir)
        try {
            val acquired = first.acquire(LeaseRequest(runId, owner("owner-a")))
            assertTrue(acquired is LeaseAcquisition.Acquired, "first acquire must win, got $acquired")

            val denied = second.acquire(LeaseRequest(runId, owner("owner-b")))
            assertTrue(
                denied is LeaseAcquisition.AlreadyOwned,
                "the second store must be refused while the first holds the lease, got $denied",
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    @DisplayName("after release the next owner takes over and the token ADVANCES")
    fun release_then_takeover_advances_token(@org.junit.jupiter.api.io.TempDir dir: Path) {
        val runId = "run-takeover"
        val first = FileBackedRunExecutionLeaseStore(dir)
        val firstDecision = first.acquire(LeaseRequest(runId, owner("owner-a")))
        assertTrue(firstDecision is LeaseAcquisition.Acquired, "got $firstDecision")
        val firstToken = (firstDecision as LeaseAcquisition.Acquired).fencingToken
        first.release(owner("owner-a"))
        first.close()

        val second = FileBackedRunExecutionLeaseStore(dir)
        try {
            val takeover = second.acquire(LeaseRequest(runId, owner("owner-b")))
            assertTrue(takeover is LeaseAcquisition.TakenOver, "expected a takeover, got $takeover")
            val secondToken = (takeover as LeaseAcquisition.TakenOver).fencingToken
            assertNotEquals(
                firstToken, secondToken,
                "a takeover MUST advance the fencing token, otherwise the previous " +
                    "owner is indistinguishable from the current one",
            )

            // The decisive fencing property: the first owner's token is now
            // stale and must be refused.
            val stale = second.authorise(runId, firstToken)
            assertTrue(
                stale is PublishAuthority.Superseded,
                "the superseded owner's token must be fenced out, got $stale",
            )
            val current = second.authorise(runId, secondToken)
            assertTrue(current is PublishAuthority.Authorised, "the live owner may publish, got $current")
        } finally {
            second.close()
        }
    }

    @Test
    @DisplayName("a SIGKILLed owner's run is recoverable by the next process")
    fun killed_owner_run_is_recoverable(@org.junit.jupiter.api.io.TempDir dir: Path) {
        val runId = "run-killed"
        val store = FileBackedRunExecutionLeaseStore(dir)
        val decision = store.acquire(LeaseRequest(runId, owner("owner-doomed")))
        assertTrue(decision is LeaseAcquisition.Acquired, "got $decision")
        val doomedToken = (decision as LeaseAcquisition.Acquired).fencingToken
        // Simulate SIGKILL: close the channel WITHOUT the release handler
        // running. The kernel still drops the lock; the durable record still
        // names a dead owner.
        store.close()

        val successor = FileBackedRunExecutionLeaseStore(dir)
        try {
            val takeover = successor.acquire(LeaseRequest(runId, owner("owner-heir")))
            assertTrue(
                takeover is LeaseAcquisition.TakenOver,
                "a run whose owner died without releasing must be takeable, got $takeover",
            )
            val heirToken = (takeover as LeaseAcquisition.TakenOver).fencingToken
            assertNotEquals(doomedToken, heirToken, "the heir must hold a newer token")

            val stale = successor.authorise(runId, doomedToken)
            assertTrue(
                stale is PublishAuthority.Superseded,
                "the dead owner's token must be fenced out after takeover, got $stale",
            )
        } finally {
            successor.close()
        }
    }

    @Test
    @DisplayName("two stores racing for one run produce exactly one owner")
    fun concurrent_stores_yield_exactly_one_owner(@org.junit.jupiter.api.io.TempDir dir: Path) {
        val runId = "run-race"
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<LeaseAcquisition>())

        fun racer(name: String) = Thread {
            val store = FileBackedRunExecutionLeaseStore(dir)
            try {
                ready.countDown()
                go.await(30, TimeUnit.SECONDS)
                outcomes += store.acquire(LeaseRequest(runId, owner(name)))
                // Hold briefly so the loser genuinely contends rather than
                // racing against an already-finished winner.
                Thread.sleep(300)
            } finally {
                store.close()
            }
        }

        val a = racer("racer-a")
        val b = racer("racer-b")
        a.start(); b.start()
        ready.await(30, TimeUnit.SECONDS)
        go.countDown()
        a.join(30_000); b.join(30_000)

        assertEquals(2, outcomes.size, "both racers must report a verdict")
        val winners = outcomes.filter { it is LeaseAcquisition.Acquired }
        assertEquals(
            1, winners.size,
            "exactly one racer may own the run; got ${outcomes.map { it.render() }}",
        )
        val loser = outcomes.first { it !is LeaseAcquisition.Acquired }
        assertTrue(
            loser is LeaseAcquisition.AlreadyOwned,
            "the losing racer must be refused with a typed denial, got $loser",
        )
    }

    @Test
    @DisplayName("the durable record is operator-readable and survives a store reopen")
    fun durable_record_is_readable_and_survives_reopen(@org.junit.jupiter.api.io.TempDir dir: Path) {
        val runId = "run-durable"
        val store = FileBackedRunExecutionLeaseStore(dir)
        val decision = store.acquire(LeaseRequest(runId, owner("owner-visible")))
        assertTrue(decision is LeaseAcquisition.Acquired, "got $decision")
        val token = (decision as LeaseAcquisition.Acquired).fencingToken
        store.close()

        // A fresh store must read the SAME durable record — the token is
        // durable authority, not process-local state.
        val reopened = FileBackedRunExecutionLeaseStore(dir)
        try {
            val authority = reopened.authorise(runId, token)
            assertTrue(
                authority is PublishAuthority.Authorised,
                "a token must stay valid across store instances, got $authority",
            )
            val lockFile = Files.list(dir).use { s ->
                s.filter { it.fileName.toString().endsWith(".record") }.findFirst()
            }
            assertTrue(lockFile.isPresent, "a lease record must exist for the run")
            val body = Files.readString(lockFile.get())
            assertTrue(body.contains("run_id=$runId"), "the record must name the run: $body")
            assertTrue(body.contains("owner_id=owner-visible"), "the record must name the owner: $body")
        } finally {
            reopened.close()
        }
    }
}
