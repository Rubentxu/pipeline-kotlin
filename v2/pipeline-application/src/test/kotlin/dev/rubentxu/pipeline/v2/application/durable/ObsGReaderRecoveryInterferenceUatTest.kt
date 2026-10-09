package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * OBS-G — what a READ-ONLY open of the Output Plane does to a writer that is still alive.
 *
 * ## The question, and the answer
 *
 * `OutputPlaneProvider.storeFor()` recovers the store, and `SegmentOutputStore.recover()` reconciles
 * every stream directory, including `reconcile()`'s
 *
 * ```kotlin
 * if (readableEnd > committed) truncateTo(layout.segmentFile, …)
 * ```
 *
 * `readableEnd` counts bytes the writer has written; `committed` counts bytes it has acknowledged.
 * Between a writer's `write` and its `commit` those two differ, and the recovery that a reader
 * triggers treats the difference as crash debris and truncates it.
 *
 * Both `observe` and `console` call `storeFor()` as the FIRST statement of their read path, before
 * the query has found anything. So the destructive step does not depend on the reader succeeding.
 *
 * **MEASURED: it does happen, and it costs the bytes.** A writer that committed 8192 bytes with no
 * error leaves only 4096 readable after a successful `console` query in a second process. The
 * killed-writer case is unaffected and still correct.
 *
 * ## What is asserted, and what is characterised
 *
 * - `RECOVER-2` asserts a **law**: a real `kill -9` keeps the acknowledged prefix byte for byte,
 *   releases the open reservation exactly once, and leaves the stream writable. Green, and it must
 *   stay green through any repair.
 * - `INTERFERE-1` asserts the behaviour as it is **today, which is broken**. It is a
 *   characterisation, not a law. Its assertion messages say so and name the inversion a fix
 *   requires, so closing the defect is a deliberate edit rather than a silent deletion.
 *
 * ## Fidelity
 *
 * HF3. Two real OS processes. The writer is a forked JVM holding a real open reservation on the real
 * store; the reader is a forked JVM running the genuine `MainConsoleCli` verb, which succeeds and
 * serves the acknowledged bytes. The barrier is a file the writer creates AFTER `write` and BEFORE
 * `commit`, so "parked in the window" is an event, not a sleep. No wall-clock assertion, no pipe,
 * `@TempDir` throughout.
 *
 * `MainConsoleCli` is driven directly rather than through `installDist`: this row is about the
 * library's read path, not about the installed image, and claiming HF2 here would be false.
 *
 * ## Two harness defects this row paid for, recorded because they nearly faked the result
 *
 * 1. The stream identity was computed two different ways — the writer used `op0`, the reader used
 *    `OpId(runId, 0, 0).format()`. The first run reported "only 0 bytes survived", which reads
 *    exactly like a total-loss defect and was nothing of the kind: the reader was looking at a
 *    different stream. The identity is now defined once.
 * 2. The recovery report was read from a SECOND `recover()`. `storeFor()` recovers internally, so
 *    the second pass had nothing left to release and reported a clean store. A genuine product fact
 *    was read as a harness defect.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ObsGReaderRecoveryInterferenceUatTest {

    private val runId = "r-obsg-interference"
    private val opId = "op0"

    @TempDir
    lateinit var root: Path

    private var writer: Process? = null
    private lateinit var workspace: Path
    private lateinit var controlRoot: Path

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
        writer?.let { if (it.isAlive) it.destroyForcibly() }
        writer = null
        Files.deleteIfExists(releaseMarker)
    }

    private val parkedMarker: Path get() = workspace.resolve("PARKED")
    private val releaseMarker: Path get() = workspace.resolve("RELEASE")
    private val committedMarker: Path get() = workspace.resolve("COMMITTED")

    private fun launchWriter(committedBytes: Int, parkedBytes: Int): Process {
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsGInterferenceProducer",
            *ObsGInterferenceProducer.argv(
                controlRoot = controlRoot,
                runId = runId,
                opId = opId,
                committedBytes = committedBytes,
                parkedBytes = parkedBytes,
                parkedMarker = parkedMarker,
                releaseMarker = releaseMarker,
                committedMarker = committedMarker,
            ),
        )
            // A file, never a pipe: this module deadlocks often enough when a test waitsFor()s
            // without draining, and a file cannot deadlock. The subject is cross-process interference.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(workspace.resolve("writer.log").toFile()))
            .start()
        writer = process
        return process
    }

    /** A second real process running the genuine `console` verb over the same control directory. */
    private fun runConsoleObserver(): Int {
        val out = workspace.resolve("observer.log")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsGConsoleObserver",
            "--control-dir", controlRoot.toString(),
            runId,
            opId,
        )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(out.toFile()))
            .start()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the observer process never exited")
        val log = if (Files.exists(out)) Files.readString(out) else ""
        return Regex("obsg-console-observer-exit:(-?\\d+)").find(log)?.groupValues?.get(1)?.toInt()
            ?: error("the observer produced no exit line; its stdout was:\n$log")
    }

    /** The writer's own barrier: it exists only after `write`, and only before `commit`. */
    private fun awaitParked(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            if (Files.exists(parkedMarker)) return Files.readString(parkedMarker)
            Thread.sleep(25)
        }
        error("the writer never reported being parked between write and commit")
    }

    /** Everything a brand-new process can read of the run's stdout stream. */
    private fun committedBytesAsSeenByAFreshReader(): ByteArray {
        OutputPlaneProvider.forgetAll()
        val store = OutputPlaneProvider.storeFor(controlRoot)
        val stream = OutputPlaneProvider.streamsOf(runId, opId).stdout.stream
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            when (val page = store.read(stream, cursor, 4096)) {
                is OutputReadResult.Page -> {
                    out.write(page.page.bytes)
                    cursor = page.page.next
                }
                is OutputReadResult.Refused -> return out.toByteArray()
            }
        }
        return out.toByteArray()
    }

    /**
     * INTERFERE-1 — CHARACTERISATION of a DEFECT OBSERVED, not a law.
     *
     * The writer parks in the window. A second process then runs `console`, which recovers on open,
     * succeeds, and serves the acknowledged bytes. The writer is released and its commit reports the
     * whole stream. The row records what a reader can then see.
     *
     * Today that is less than the writer committed. Fixing the recovery INVERTS these assertions; the
     * edit is meant to be deliberate, so each message names what must change.
     */
    @Test
    fun `CARACTERISATION reader opening the plane truncates a live writer's written bytes`() {
        val committedBytes = 4096
        val parkedBytes = 4096

        val process = launchWriter(committedBytes = committedBytes, parkedBytes = parkedBytes)
        awaitParked()
        assertTrue(process.isAlive, "the writer must still be alive: it has written and not committed")

        val observerExit = runConsoleObserver()
        assertEquals(
            0,
            observerExit,
            "the observer must SUCCEED. A successful, ordinary, read-only query is what makes this row " +
                "serious: the damage is not caused by a query that went wrong",
        )
        assertEquals(
            committedBytes,
            Files.readString(workspace.resolve("observer.log")).count { it == 'A' },
            "and it must have read the acknowledged bytes, so the verb really did open, recover and serve",
        )

        Files.writeString(releaseMarker, "go")
        val commitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!Files.exists(committedMarker) && System.nanoTime() < commitDeadline) Thread.sleep(25)
        assertTrue(Files.exists(committedMarker), "the writer never reached its commit after being released")

        // What the writer BELIEVED it achieved. Its commit returned without error, so this is the
        // writer's own account of the run, not the reader's.
        val claimedByWriter = Files.readString(committedMarker).trim().toLong()

        OutputPlaneProvider.forgetAll()
        val report = dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore(
            controlRoot.resolve(OutputPlaneProvider.OUTPUT_DIR),
        ).recover()
        val visible = committedBytesAsSeenByAFreshReader()

        assertEquals(
            (committedBytes + parkedBytes).toLong(),
            claimedByWriter,
            "premise: the writer's commit must have reported the whole stream, or this row would be " +
                "measuring a writer that gave up rather than a reader that took",
        )

        // DEFECT OBSERVED. These two assertions pin the behaviour as it is TODAY, which is broken.
        // They are characterisation, not a law: when the recovery stops reconciling a live writer's
        // range, both lines must be INVERTED, and the change has to be a deliberate edit to this
        // message rather than a quiet deletion.
        assertEquals(
            committedBytes,
            visible.size,
            "DEFECT OBSERVED, and this is the characterisation of it. The writer committed " +
                "${claimedByWriter} bytes with no error, and only $committedBytes survived: the " +
                "reader's recovery truncated the ${parkedBytes} bytes the writer had durably written " +
                "but not yet acknowledged, treating a live writer's open range as crash debris. " +
                "Invoking this on a fix INVERTS it to ${committedBytes + parkedBytes}.",
        )
        assertEquals(
            parkedBytes.toLong(),
            report.bytesUnbacked,
            "and the stream is left with a commit record that outruns its payload by exactly the bytes " +
                "the reader destroyed — which the store reports rather than reads around, so those " +
                "bytes are unrecoverable rather than merely late",
        )
        assertEquals(
            0,
            visible.count { it == 'B'.code.toByte() },
            "no byte of the range the reader truncated is observable afterwards, by any reader",
        )
    }

    /**
     * RECOVER-2 — a real writer death still releases the reservation and keeps what was committed.
     *
     * This is the guarantee the truncation exists to serve, and it must keep holding after any fix.
     * Asserting it here is what stops a repair from being "a reader never truncates anything".
     */
    @Test
    fun `a real writer death keeps committed bytes and releases the open reservation`() {
        val committedBytes = 4096
        val parkedBytes = 4096

        val process = launchWriter(committedBytes = committedBytes, parkedBytes = parkedBytes)
        awaitParked()
        assertTrue(process.isAlive, "the writer must be alive before the crash, or this is not a crash test")

        ProcessBuilder("kill", "-9", process.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
        process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(!process.isAlive, "the writer did not die from SIGKILL")

        OutputPlaneProvider.forgetAll()
        // The report must come from the FIRST recovery. `storeFor()` recovers internally, so asking
        // it for a report would ask a second pass that has nothing left to release — and this row's
        // first version did exactly that and read a genuine product fact (a clean store) as a
        // harness defect. This is `storeFor()`'s own body, spelled out.
        val report = dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore(
            controlRoot.resolve(OutputPlaneProvider.OUTPUT_DIR),
        ).recover()
        val store = OutputPlaneProvider.storeFor(controlRoot)

        assertEquals(
            0L,
            report.bytesUnbacked,
            "a crash between write and commit must leave a commit record that does not outrun its payload",
        )
        assertEquals(
            1,
            report.reservationsReleased,
            "the open reservation must be released exactly once, or the recovered order keeps a permanent hole",
        )

        val visible = committedBytesAsSeenByAFreshReader()
        assertEquals(
            ByteArray(committedBytes) { 'A'.code.toByte() }.toList(),
            visible.toList(),
            "the acknowledged prefix must survive the crash byte for byte, and the unacknowledged tail " +
                "must not appear",
        )

        // The store must remain usable: a released range that cannot be reserved again is a stream
        // that ends at its first crash.
        val stream = OutputPlaneProvider.streamsOf(runId, opId).stdout.stream
        val handle = store.open(stream)
        val afterCrash = handle.reserve(1024)
        afterCrash.write(ByteArray(1024) { 'C'.code.toByte() })
        assertEquals(
            committedBytes + 1024L,
            afterCrash.commit(),
            "a writer must be able to resume immediately after a crash, at the committed offset",
        )

        assertEquals(
            (ByteArray(committedBytes) { 'A'.code.toByte() } + ByteArray(1024) { 'C'.code.toByte() }).toList(),
            committedBytesAsSeenByAFreshReader().toList(),
            "and the stream a reader sees afterwards must be the concatenation, with no gap and no repeat",
        )
    }
}