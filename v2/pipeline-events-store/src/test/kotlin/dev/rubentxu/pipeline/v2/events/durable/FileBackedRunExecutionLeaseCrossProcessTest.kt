package dev.rubentxu.pipeline.v2.events.durable

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Cross-process proof for run ownership.
 *
 * A lease that only arbitrates between two stores inside one JVM is decoration:
 * its entire purpose is to stop two OS processes from publishing events for the
 * same run. These tests therefore use real child processes, real advisory
 * locks, and real kills, which is the only environment where the kernel — not
 * the JVM's per-process lock table — decides who wins.
 *
 * Each child is a separate `java` process launched from the same classpath, so
 * the code under test is the shipped code and not a test double.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FileBackedRunExecutionLeaseCrossProcessTest {

    private fun childClasspath(): String =
        System.getProperty("java.class.path")

    private fun javaBinary(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /**
     * A child that acquires the lease, prints the acquisition, then either
     * holds the lease until killed or releases it after a delay. The protocol
     * is deliberately line-based so the parent can assert on real cross-process
     * outcomes without reading log files after the fact.
     */
    private fun spawnLeaseChild(
        dir: Path,
        runId: String,
        owner: String,
        holdMillis: Long,
    ): Process {
        val cmd = listOf(
            javaBinary(),
            "-cp", childClasspath(),
            LeaseChild::class.java.name,
            dir.toAbsolutePath().toString(), runId, owner, holdMillis.toString(),
        )
        return ProcessBuilder(cmd).redirectErrorStream(true).start()
    }

    private fun readUntilMarker(p: Process, marker: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        val reader = p.inputStream.bufferedReader()
        while (System.currentTimeMillis() < deadline) {
            if (!reader.ready()) { Thread.sleep(20); continue }
            val line = reader.readLine() ?: break
            sb.appendLine(line)
            if (line.startsWith(marker)) return sb.toString()
        }
        return sb.toString()
    }

    @Test
    fun `a second process is refused while the first process holds the lease`() {
        val dir = Files.createTempDirectory("lease-xproc-refuse-")
        try {
            val first = spawnLeaseChild(dir, "run-xproc", "owner-1", holdMillis = 30_000)
            val firstOut = readUntilMarker(first, "ACQUIRED", 30_000)
            assertTrue(firstOut.contains("ACQUIRED"),
                "the first process must win the lease, got: $firstOut")

            val second = spawnLeaseChild(dir, "run-xproc", "owner-2", holdMillis = 0)
            val secondOut = readUntilMarker(second, "DONE", 30_000)
            second.waitFor(30, TimeUnit.SECONDS)

            assertTrue(secondOut.contains("ALREADY_OWNED"),
                "a live cross-process owner must refuse the second process, got: $secondOut")
            assertTrue(!secondOut.contains("TAKEN_OVER"),
                "the second process must not silently steal a live lease, got: $secondOut")

            first.destroyForcibly()
            first.waitFor(30, TimeUnit.SECONDS)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a killed owner's lease is recoverable and the token advances`() {
        val dir = Files.createTempDirectory("lease-xproc-takeover-")
        try {
            val first = spawnLeaseChild(dir, "run-kill", "owner-1", holdMillis = 300_000)
            val firstOut = readUntilMarker(first, "ACQUIRED", 30_000)
            assertTrue(firstOut.contains("ACQUIRED"),
                "the first process must win the lease, got: $firstOut")
            val firstToken = Regex("fencingToken=(\\d+)").find(firstOut)?.groupValues?.get(1)
            assertNotNull(firstToken, "the first process must report a token, got: $firstOut")

            // SIGKILL: the owner dies without releasing, which is exactly the
            // case a cooperative release path would hide.
            first.destroyForcibly()
            first.waitFor(30, TimeUnit.SECONDS)
            assertTrue(!first.isAlive, "the first process must be dead before takeover")

            val second = spawnLeaseChild(dir, "run-kill", "owner-2", holdMillis = 0)
            val secondOut = readUntilMarker(second, "DONE", 30_000)
            second.waitFor(30, TimeUnit.SECONDS)

            assertTrue(secondOut.contains("TAKEN_OVER"),
                "an orphaned lease must be recoverable by takeover, got: $secondOut")
            val secondToken = Regex("fencingToken=(\\d+)").find(secondOut)?.groupValues?.get(1)
            assertNotNull(secondToken, "the new owner must report a token, got: $secondOut")
            assertEquals(
                (firstToken!!.toInt() + 1).toString(), secondToken,
                "a takeover must fence the previous owner by advancing the token, " +
                    "first=$firstToken second=$secondToken",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the fenced owner's stale token can no longer publish`() {
        val dir = Files.createTempDirectory("lease-xproc-fence-")
        try {
            val first = spawnLeaseChild(dir, "run-fence", "owner-1", holdMillis = 300_000)
            val firstOut = readUntilMarker(first, "ACQUIRED", 30_000)
            val firstToken = Regex("fencingToken=(\\d+)").find(firstOut)?.groupValues?.get(1)
            assertNotNull(firstToken, "the first process must report a token, got: $firstOut")

            first.destroyForcibly()
            first.waitFor(30, TimeUnit.SECONDS)

            val second = spawnLeaseChild(dir, "run-fence", "owner-2", holdMillis = 0)
            val secondOut = readUntilMarker(second, "DONE", 30_000)
            second.waitFor(30, TimeUnit.SECONDS)
            val secondToken = Regex("fencingToken=(\\d+)").find(secondOut)?.groupValues?.get(1)
            assertNotNull(secondToken, "the new owner must report a token, got: $secondOut")

            // A fresh store in the parent now stands in for the dead owner and
            // asks whether its OLD token still grants publishing authority. It
            // must not: that is the whole point of fencing.
            val store = FileBackedRunExecutionLeaseStore(dir)
            val stale = store.authorise("run-fence", FencingToken.of(firstToken!!.toLong()))
            assertTrue(
                stale is PublishAuthority.Superseded,
                "the fenced owner's stale token must be refused, got $stale",
            )
            val current = store.authorise("run-fence", FencingToken.of(secondToken!!.toLong()))
            assertTrue(
                current is PublishAuthority.Authorised,
                "the current owner's token must publish, got $current",
            )
            store.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Child entry point. Kept as a real class (not a lambda) so the child JVM
     * can load it by name from the test classpath.
     */
    object LeaseChild {
        @JvmStatic
        fun main(args: Array<String>) {
            val dir = Path.of(args[0])
            val runId = args[1]
            val ownerName = args[2]
            val holdMillis = args[3].toLong()
            val owner = RunOwnerId.of(ownerName)!!
            val store = FileBackedRunExecutionLeaseStore(dir)
            val result = store.acquire(LeaseRequest(runId, owner))
            when (result) {
                is LeaseAcquisition.Acquired -> {
                    println("ACQUIRED owner=$ownerName fencingToken=${result.fencingToken.value}")
                    System.out.flush()
                    if (holdMillis > 0) Thread.sleep(holdMillis)
                    store.release(owner)
                }
                is LeaseAcquisition.TakenOver -> {
                    println("TAKEN_OVER owner=$ownerName fencingToken=${result.fencingToken.value}")
                    System.out.flush()
                    store.close()
                }
                is LeaseAcquisition.AlreadyOwned -> {
                    println("ALREADY_OWNED owner=$ownerName heldBy=${result.heldBy.value}")
                    System.out.flush()
                    store.close()
                }
                is LeaseAcquisition.Reentered -> {
                    println("REENTERED owner=$ownerName fencingToken=${result.fencingToken.value}")
                    System.out.flush()
                    store.release(owner)
                }
                is LeaseAcquisition.Unverifiable -> {
                    println("UNVERIFIABLE owner=$ownerName reason=${result.reason}")
                    System.out.flush()
                    store.close()
                }
            }
            println("DONE owner=$ownerName")
            System.out.flush()
        }
    }
}
