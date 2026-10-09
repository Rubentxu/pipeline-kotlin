package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryReport
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * OBS-1 — the rest of the read/recovery boundary, under `ADR-OBS-002`.
 *
 * `ObsGReaderRecoveryInterferenceUatTest` covers OBS-PC-101 and OBS-PC-103. These are the five that were
 * still open, and each one is a way the ownership design could be wrong without 101 noticing:
 *
 * - **OBS-PC-102** two runs share a control root and recovering one must not touch the other. A
 *   plane-wide lock would make this impossible to state, which is why ownership is per stream.
 * - **OBS-PC-104** recovery is idempotent, including its report. The second pass must not invent work.
 * - **OBS-PC-105** a cursor taken before a crash still resumes afterwards, and no confirmed range is
 *   served twice. This is the property that makes `OutputTailState` and reattach agree.
 * - **OBS-PC-106** a plane holding a crashed writer's uncommitted bytes must NOT be reported to a reader
 *   as an empty successful page. Before ADR-OBS-002 a reader recovered, truncated to zero, and answered
 *   "there is nothing here" — a clean finish for a stream that had 4096 acknowledged bytes in it.
 * - **OBS-PC-107** a reader that dies changes nothing: not the run, not the bytes another reader can get.
 *
 * ## Fidelity
 *
 * HF3. Every row that needs concurrency forks real OS processes. `launchWriter` runs the genuine store
 * API in its own JVM; `recoverFromFreshJvm` is a store opened exactly as `OutputPlaneProvider` opens a
 * writing store. Barriers are files the writer creates after `write` and before `commit`, so "parked in
 * the window" is an event and not a sleep. `@TempDir`, no pipes, no wall-clock assertions.
 *
 * ## The mutations that must kill this — MEASURED, not asserted
 *
 * The first draft of this block attributed the mutations by reasoning. Measurement corrected two of the
 * three, and the corrections are kept here rather than quietly rewritten:
 *
 * - **M-OWN-1** — `tryWithStreamOwnership` reconciles even when the lock is held, so recovery truncates a
 *   live writer's stream. REDS **OBS-PC-102** alone. (Originally claimed to also RED OBS-PC-106; it does
 *   not, because that row kills its writer first, so the kernel has already released the lock and there
 *   is nothing for this mutation to change. The claim was about a row that never exercises the guard.)
 * - **M-OWN-2b** — `reconcile` truncates to zero instead of to the committed offset: the OBS-G defect
 *   itself. REDS **OBS-PC-102**, **104**, **105**, **106**. It leaves **OBS-PC-107** green, correctly:
 *   that row has no crashed writer, so no unacknowledged bytes exist to drop.
 *   A first attempt at this mutation wrote `truncateTo(file, onDisk)`, which truncates to the file's
 *   *current* length and therefore changes nothing on disk. It still killed three rows, through
 *   `bytesReleased` alone — a reminder that a mutation can be load-bearing for the report while saying
 *   nothing about the bytes.
 * - **M-OWN-3** — `read` treats `next` as the start of the stream, so a short read re-serves its page.
 *   REDS **OBS-PC-102**, **105**, **106**, **107** — every row that reads bytes — and leaves **104** green,
 *   which only inspects the report.
 *
 * What the pattern says: OBS-PC-102 is the only row that dies from *ownership*, 105 from the *cursor*, and
 * no single mutation kills all five. The block is load-bearing because the three mechanisms are
 * independent, not because any one guard is deep.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@DisplayName("OBS-1 — frontera segura entre lectura y recuperación")
class ObsPcReadRecoveryOwnershipUatTest {

    @TempDir
    lateinit var root: Path

    private lateinit var workspace: Path
    private lateinit var controlRoot: Path
    private val writers = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the forked-JVM harness requires a POSIX host",
        )
        workspace = Files.createDirectories(root.resolve("workspace"))
        controlRoot = Files.createDirectories(root.resolve("control"))
    }

    @AfterEach
    fun tearDown() {
        writers.forEach { if (it.isAlive) it.destroyForcibly() }
        writers.clear()
        Files.deleteIfExists(root.resolve("RELEASE"))
    }

    // ------------------------------------------------------------------ harness

    private fun markers(name: String): Triple<Path, Path, Path> = Triple(
        workspace.resolve("$name.PARKED"),
        workspace.resolve("$name.RELEASE"),
        workspace.resolve("$name.COMMITTED"),
    )

    private fun launchWriter(name: String, runId: String, opId: String, committed: Int, parked: Int): Process {
        val (parkedMarker, releaseMarker, committedMarker) = markers(name)
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsGInterferenceProducer",
            *ObsGInterferenceProducer.argv(
                controlRoot = controlRoot,
                runId = runId,
                opId = opId,
                committedBytes = committed,
                parkedBytes = parked,
                parkedMarker = parkedMarker,
                releaseMarker = releaseMarker,
                committedMarker = committedMarker,
            ),
        )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(workspace.resolve("$name.log").toFile()))
            .start()
        writers += process
        return process
    }

    /** The writer's own barrier: it exists only after `write`, and only before `commit`. */
    private fun awaitParked(name: String): String {
        val (parkedMarker, _, _) = markers(name)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            if (Files.exists(parkedMarker)) return Files.readString(parkedMarker)
            Thread.sleep(25)
        }
        error("writer $name never reported being parked between write and commit")
    }

    private fun release(name: String) {
        val (_, releaseMarker, _) = markers(name)
        Files.writeString(releaseMarker, "go")
        val (_, _, committedMarker) = markers(name)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!Files.exists(committedMarker) && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue(Files.exists(committedMarker), "writer $name never reached its commit")
    }

    private fun kill(process: Process) {
        ProcessBuilder("kill", "-9", process.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
        process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(!process.isAlive, "the writer did not die from SIGKILL")
    }

    /** Bytes a READER sees, through the passive opening, for one run. */
    private fun readerSees(runId: String, opId: String, from: Long = 0L): ByteArray {
        OutputPlaneProvider.forgetAll()
        val store = OutputPlaneProvider.storeForReading(controlRoot)
        val stream = OutputPlaneProvider.streamsOf(runId, opId).stdout.stream
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor = OutputCursor.start(stream).let { start ->
            if (from == 0L) start else OutputCursor(stream, from)
        }
        while (true) {
            when (val page = store.read(stream, cursor, 4096)) {
                is OutputReadResult.Page -> {
                    out.write(page.page.bytes)
                    val next = page.page.next ?: break
                    cursor = next
                }
                is OutputReadResult.Refused -> break
            }
        }
        return out.toByteArray()
    }

    /** The committed extent a reader can see, used as a cursor for resuming. */
    private fun readerExtent(runId: String, opId: String): Long =
        OutputPlaneProvider.forgetAll().let {
            OutputPlaneProvider.storeForReading(controlRoot)
                .committedExtent(OutputPlaneProvider.streamsOf(runId, opId).stdout.stream) ?: 0L
        }

    /** Recovery exactly as `OutputPlaneProvider.storeForWriting` performs it, in this JVM. */
    private fun recoverFreshlyOpened(): OutputRecoveryReport =
        SegmentOutputStore(controlRoot.resolve(OutputPlaneProvider.OUTPUT_DIR)).recover()

    private fun filler(count: Int, fill: Char): ByteArray = ByteArray(count) { fill.code.toByte() }

    /**
     * The exact bytes a writer that ran to its commit leaves behind.
     *
     * NOT `ByteArray(committed + parked)`: `ObsGInterferenceProducer` fills its acknowledged phase with
     * `'A'` and its parked phase with `'B'`, so the full extent is two distinguishable runs. Writing the
     * expectation as a zero-filled array would compare 8192 zeros against 4096 A's and 4096 B's, which
     * fails for a reason that has nothing to do with the property under test.
     */
    private fun acknowledgedContent(committedBytes: Int, parkedBytes: Int): ByteArray =
        filler(committedBytes, 'A') + filler(parkedBytes, 'B')

    // ------------------------------------------------------------------ rows

    /**
     * OBS-PC-102 — two independent runs over ONE control root. Recovering one leaves the other alone.
     *
     * This is the row that decides ownership is per stream and not per plane. Under a plane-wide lock
     * the two writers would exclude each other and the scenario would not even be expressible.
     */
    @Test
    fun `OBS-PC-102 two runs share one control root without interfering`() {
        val committedBytes = 4096
        val parkedBytes = 4096

        val first = launchWriter("w1", "r-obs-pc-1", "op0", committedBytes, parkedBytes)
        val second = launchWriter("w2", "r-obs-pc-2", "op0", committedBytes, parkedBytes)
        awaitParked("w1")
        awaitParked("w2")
        assertTrue(first.isAlive && second.isAlive, "both writers must be inside their window")

        // Only ONE writer dies. This is what makes the row a cross-run claim rather than a "recovery
        // is a no-op while writers live" claim: a plane-wide lock would reconcile nothing here, and a
        // plane-wide lock is the wrong design that this ownership-per-stream decision exists to reject.
        kill(second)

        val report = recoverFreshlyOpened()

        assertEquals(1, report.streamsOwned, "the surviving writer's stream must be reported as owned and left alone")
        assertEquals(1, report.streamsReconciled, "and only the dead writer's stream may be reconciled")
        assertEquals(
            parkedBytes.toLong(),
            report.bytesReleased,
            "recovery must release exactly the dead run's debris, not the live run's",
        )

        // The live writer was never touched: it is still parked, still owns its stream, and its
        // acknowledged prefix is intact and readable while the OTHER run's recovery happened.
        assertTrue(first.isAlive, "reconciling a dead run must not take a live writer down")
        assertEquals(
            committedBytes.toLong(),
            readerExtent("r-obs-pc-1", "op0"),
            "the live run's acknowledged extent must be exactly what it was before recovery",
        )

        release("w1")

        assertEquals(
            acknowledgedContent(committedBytes, parkedBytes).toList(),
            readerSees("r-obs-pc-1", "op0").toList(),
            "run 1 must carry exactly its own committed bytes, byte for byte",
        )
        assertEquals(
            filler(committedBytes, 'A').toList(),
            readerSees("r-obs-pc-2", "op0").toList(),
            "run 2 lost only its own unacknowledged tail, and kept its own acknowledged prefix",
        )
    }

    /** OBS-PC-104 — recovery is idempotent, including the report and not only the durable state. */
    @Test
    fun `OBS-PC-104 a second recovery is idempotent`() {
        val writer = launchWriter("w1", "r-obs-pc-idem", "op0", committed = 4096, parked = 4096)
        awaitParked("w1")
        kill(writer)

        val first = recoverFreshlyOpened()
        val second = recoverFreshlyOpened()

        assertEquals(1, first.reservationsReleased, "the crashed writer's reservation must be released once")
        assertEquals(0, second.reservationsReleased, "a second pass has nothing to release and must say so")
        assertEquals(0L, second.bytesReleased, "nor may it re-release bytes")
        assertEquals(
            first.committedBytes,
            second.committedBytes,
            "committedBytes is the stable field, which is what makes idempotence checkable at all",
        )
        assertEquals(first.streamsReconciled, second.streamsReconciled, "and the stream count must settle")
        assertEquals(0L, second.bytesUnbacked, "a recovered store is not damaged")
    }

    /** OBS-PC-105 — a cursor from before the crash still resumes, and nothing is served twice. */
    @Test
    fun `OBS-PC-105 a cursor taken before a crash resumes after recovery without duplicating`() {
        val committedBytes = 4096
        val parkedBytes = 4096

        val writer = launchWriter("w1", "r-obs-pc-cursor", "op0", committedBytes, parkedBytes)
        // Take the cursor while the run is ALIVE, which is the case that matters: a follower that has
        // been watching must be able to continue after the writer dies.
        awaitParked("w1")
        val cursorBefore = readerExtent("r-obs-pc-cursor", "op0")
        val prefix = readerSees("r-obs-pc-cursor", "op0", from = 0L)

        assertEquals(committedBytes.toLong(), cursorBefore, "only acknowledged bytes are visible while parked")
        assertEquals(
            committedBytes,
            prefix.size,
            "and the reader sees exactly that prefix, never the uncommitted tail",
        )

        kill(writer)
        recoverFreshlyOpened()

        // The writer had written 4096 more bytes but died before acknowledging them, so recovery drops
        // them and the cursor must NOT move past what was ever acknowledged.
        val extentAfter = readerExtent("r-obs-pc-cursor", "op0")
        assertEquals(
            cursorBefore,
            extentAfter,
            "recovery dropped the unacknowledged tail, so the durable extent must be unchanged",
        )

        val resumed = readerSees("r-obs-pc-cursor", "op0", from = cursorBefore)
        assertEquals(0, resumed.size, "resuming from the last acknowledged position has nothing new to serve")
        assertEquals(prefix.toList(), readerSees("r-obs-pc-cursor", "op0").toList(), "and re-reading is stable")
    }

    /**
     * OBS-PC-106 — a plane holding a crashed writer's debris is NOT an empty successful read.
     *
     * Before ADR-OBS-002 the reader recovered, `reconcile` truncated the segment to zero, and the reader
     * answered "there is nothing here" for a stream that had 4096 acknowledged bytes in it. That is the
     * shape this row forbids: a hole that reads as a clean finish.
     */
    @Test
    fun `OBS-PC-106 a plane needing reconciliation is not reported as an empty successful page`() {
        val committedBytes = 4096
        val parkedBytes = 4096

        val writer = launchWriter("w1", "r-obs-pc-unrec", "op0", committedBytes, parkedBytes)
        awaitParked("w1")
        kill(writer)

        // BEFORE any recovery: the acknowledged prefix must already be readable, because a reader reads
        // committed bytes and never needed recovery to do it.
        val beforeRecovery = readerSees("r-obs-pc-unrec", "op0")
        assertEquals(
            committedBytes,
            beforeRecovery.size,
            "a reader must serve the acknowledged prefix without recovering anything; reporting zero here " +
                "is the empty-page failure this row exists to forbid",
        )
        assertEquals(filler(committedBytes, 'A').toList(), beforeRecovery.toList(), "byte for byte")

        val report = recoverFreshlyOpened()
        assertEquals(parkedBytes.toLong(), report.bytesReleased, "the crashed writer's uncommitted tail is released")
        assertEquals(0L, report.bytesUnbacked, "and releasing it does not leave a commit record ahead of the payload")

        val afterRecovery = readerSees("r-obs-pc-unrec", "op0")
        assertEquals(
            beforeRecovery.toList(),
            afterRecovery.toList(),
            "recovery must not change what a reader sees: dropping debris is not the same as losing history",
        )
    }

    /** OBS-PC-107 — a reader process that is gone alters neither the run nor another reader's bytes. */
    @Test
    fun `OBS-PC-107 a dead reader alters neither the run nor another reader's bytes`() {
        val committedBytes = 4096
        val parkedBytes = 4096

        val writer = launchWriter("w1", "r-obs-pc-deadreader", "op0", committedBytes, parkedBytes)
        awaitParked("w1")

        // A reader running the genuine `console` verb, in its own JVM, over the same control root,
        // while the writer sits parked inside its reservation window.
        //
        // The engagement is REQUIRED, not assumed. An earlier version of this row asserted
        // `waitFor(...) || isAlive`, which is true whether the reader ran, crashed on a bad classpath,
        // or was never a working program at all — so the row would have passed with no reader having
        // read anything. The marker below is what `ObsGConsoleObserver` prints on STDOUT after
        // `MainConsoleCli.main` returns, so its presence is evidence the real read path ran to
        // completion and then the process was gone.
        val log = workspace.resolve("doomed.log")
        val doomed = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsGConsoleObserver",
            "--control-dir", controlRoot.toString(),
            "r-obs-pc-deadreader", "op0",
        )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
            .start()

        assertTrue(
            doomed.waitFor(120, TimeUnit.SECONDS),
            "the reader never finished: a row about a reader that ended cannot hang",
        )
        assertTrue(
            Regex("obsg-console-observer-exit:-?\\d+").containsMatchIn(
                if (Files.exists(log)) Files.readString(log) else "",
            ),
            "the reader must have actually run the console verb; a reader that never opened the plane " +
                "proves nothing about whether a dead reader damages the run",
        )

        // The writer is untouched by the reader's death and completes its own acknowledgement.
        assertTrue(writer.isAlive, "a dead reader must not take the writer down with it")
        release("w1")

        val visible = readerSees("r-obs-pc-deadreader", "op0")
        assertEquals(
            acknowledgedContent(committedBytes, parkedBytes).toList(),
            visible.toList(),
            "every byte the writer acknowledged must still be there for a reader that arrives afterwards",
        )
    }
}