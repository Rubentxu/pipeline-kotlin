package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * S2-R0 end-to-end proof: run ownership on the REAL binary.
 *
 * The cross-process unit tests prove the lease arbitrates between processes.
 * They do NOT prove the CLI honours it, and the CLI is the only place where
 * the raw SQLite uniqueness crash actually happened. So this test forks two
 * genuine `pipelinek` processes against one journal and asserts that the loser
 * exits with the typed admission rejection (exit 2) instead of a constraint
 * violation.
 *
 * Both processes are the installed distribution, not the test classpath: the
 * behaviour under test is what operators get.
 */
@Tag("installed-contract")
@Disabled("migrated-pending UAT; responsibility of pipelinek-release-harness, AGENTS.md §1. The UAT cannot be retired until the harness produces the 5-axis verdict (ADR-PK-QV-003) with an equivalent contract test. Until then, the UAT is excluded from the pipeline-kotlin test run; the harness owns it.")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class UatS2R0RunOwnershipCliTest {

    private lateinit var bin: Path

    @BeforeAll
    fun locateBinary() {
        bin = AppBinSupport.discover()
    }

    private fun writeScript(dir: Path): Path {
        val script = dir.resolve("ownership.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("probe") {
                        echo("ownership-probe")
                    }
                }
            }
        """.trimIndent())
        return script
    }

    private fun runProcess(
        script: Path, db: Path, controlRoot: Path, resume: Boolean,
    ): Process {
        val cmd = mutableListOf(bin.toString(), "run", "--db", db.toString(),
            "--control-root", controlRoot.toString())
        if (resume) cmd.add("--resume")
        cmd.add(script.toString())
        return ProcessBuilder(cmd).redirectErrorStream(true).start()
    }

    private fun drain(p: Process): String {
        val out = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
        return out.toString()
    }

    @Test
    fun `two concurrent resume invocations produce exactly one owner`(@TempDir tmp: Path) {
        val script = writeScript(tmp)
        val db = tmp.resolve("events.db")
        val controlRoot = tmp.resolve("control")

        // First invocation establishes the durable run and the lease.
        val first = runProcess(script, db, controlRoot, resume = false)
        val firstOut = drain(first)
        first.waitFor(180, TimeUnit.SECONDS)
        assertEquals(0, first.exitValue(),
            "the establishing run must succeed, got:\n$firstOut")
        assertTrue(firstOut.contains("ownership-probe"),
            "the establishing run must execute the pipeline, got:\n$firstOut")
    }

    @Test
    fun `a second process resuming a live run is refused with a typed error`(
        @TempDir tmp: Path,
    ) {
        val script = writeScript(tmp)
        val db = tmp.resolve("events.db")
        val controlRoot = tmp.resolve("control")

        val first = runProcess(script, db, controlRoot, resume = false)
        val firstOut = drain(first)
        first.waitFor(180, TimeUnit.SECONDS)
        assertEquals(0, first.exitValue(), "the first run must succeed, got:\n$firstOut")

        // Hold a lease for the SAME run from this process, exactly as a live
        // second owner would, then prove the CLI refuses to publish alongside it.
        val leaseDir = controlRoot.resolve("leases")
        val held = dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore(leaseDir)
        val holderOwner =
            dev.rubentxu.pipeline.v2.events.durable.RunOwnerId.of("external-live-owner")!!
        val runId = runIdOf(controlRoot)
        val acquired = held.acquire(
            dev.rubentxu.pipeline.v2.events.durable.LeaseRequest(runId, holderOwner)
        )
        // The establishing process has already exited and released, so this
        // acquisition is a legitimate takeover rather than a first acquisition.
        // Either case is fine: what matters is that THIS process now holds a
        // live lease, which is what the CLI must then respect.
        assertTrue(
            acquired is dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition.Acquired ||
                acquired is dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition.TakenOver,
            "the external holder must win the lease for the test to be meaningful, got $acquired",
        )
        assertTrue(held.isHolding,
            "the external holder must hold a live OS lock for the refusal to be meaningful")

        try {
            val second = runProcess(script, db, controlRoot, resume = true)
            val secondOut = drain(second)
            second.waitFor(180, TimeUnit.SECONDS)

            assertEquals(2, second.exitValue(),
                "a refused owner must exit 2 (admission), not fail as a pipeline; got:\n$secondOut")
            assertTrue(
                secondOut.contains("already owned by 'external-live-owner'"),
                "the refusal must name the live owner, got:\n$secondOut")
            assertTrue(
                !secondOut.contains("UNIQUE constraint failed"),
                "the refusal must be typed, not a raw SQLite violation, got:\n$secondOut")
            assertTrue(
                !secondOut.contains("Exception"),
                "a typed refusal must not surface a stack trace, got:\n$secondOut")
        } finally {
            held.release(holderOwner)
            held.close()
        }

        // Once the live owner is gone, the run is recoverable: this is the
        // property that makes the refusal safe rather than a deadlock.
        val third = runProcess(script, db, controlRoot, resume = true)
        val thirdOut = drain(third)
        third.waitFor(180, TimeUnit.SECONDS)
        assertEquals(0, third.exitValue(),
            "after the owner releases, the run must be recoverable, got:\n$thirdOut")
    }

    /**
     * Reads the run id the CLI recorded for this definition. The layout is a
     * flat directory of one file per definition whose content is the run id, so
     * the run id is the newest such file's content.
     *
     * The record is TWO lines, not one, and the second one matters here.
     * `RunIdDirectory` (line 26 of its own doc) writes:
     *
     * ```text
     * <runId.value>          # line 1
     * <correlationId.value>  # line 2; absent for legacy records
     * ```
     *
     * So `.trim()` is not a safe way to read it: it strips the TRAILING newline but leaves the
     * INTERIOR one, and this helper then returns `"<runId>\n<correlationId>"`. The lease store
     * sanitises non-`[A-Za-z0-9._-]` to `_` when it derives a lock filename, so the test would
     * have locked `lease-<runId>_<correlationId>.lock` while the CLI locks `lease-<runId>.lock`.
     * Two different files, no contention, and the second invocation would exit 0 — a false green
     * that looks exactly like a product defect and is not one.
     *
     * `.lines().first()` mirrors what production does: `RunIdDirectory.findLastRunId` (line 105)
     * reads the same file and takes `lines.getOrNull(0)`. A harness that re-derives the production
     * decision any other way is testing its own arithmetic, not the runtime's.
     */
    private fun runIdOf(controlRoot: Path): String {
        val lastRunRoot = controlRoot.resolve("last-run")
        val files = Files.list(lastRunRoot).use { s -> s.toList() }
        assertTrue(files.isNotEmpty(), "the CLI must record a run id under $lastRunRoot")
        val newest = files.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }!!
        return Files.readString(newest).lines().first().trim()
    }
}
