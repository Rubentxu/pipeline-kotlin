package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.ConsoleReadService
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * OBS-F — a consumer that is slow, that goes away, and that comes back in another process.
 *
 * ## What this file certifies, and what it deliberately does not
 *
 * OBS-F asks for three things this repository had no evidence for at all:
 *
 * ```text
 * a slow Fabric subscriber
 * disconnect
 * reconnect, with no gaps and no duplicates
 * ```
 *
 * The audit that looked for them found characterisation instead: two rows named a slow consumer,
 * and neither crossed the read side or a live run — one of them asserted a 120-second wall-clock
 * threshold, which HARNESS FIDELITY §3 forbids, in a file whose own KDoc said it gated nothing.
 *
 * The law under all three rows is one sentence: **a consumer reads from the durable authority, so
 * it cannot reach the execution.** Its observable consequences differ per row, and each is asserted
 * as a discrete fact:
 *
 * ```text
 * SLOW-1       the producer commits MORE while the consumer is parked, and the run still finishes
 * DISCONNECT-1 a consumer abandoned mid-run leaves the run untouched and every byte survives
 * RECONNECT-1  a SECOND OS PROCESS resumes the output line from the first one's cursor token
 * ```
 *
 * ## Why barriers, and why no millisecond is asserted anywhere in this file
 *
 * The step is held on sentinel files and the consumer is held on latches. Where a row has to wait
 * for the producer, it waits for a **durable fact** — the committed extent reaching a known value —
 * under a generous bound, which is the same shape as every other sentinel wait in this corpus. A
 * duration would be a property of this machine, and the day it fired on a loaded box it would be
 * read as a product defect.
 *
 * One subtlety worth naming, because it is where this row would otherwise be a fiction: the shell
 * touches its sentinel the instant the bytes leave the pipe, while the pump has not necessarily
 * committed them yet. Sampling the extent straight after the sentinel would therefore measure the
 * pipe rather than the plane, and could observe a producer that looked *behind* its reader. So
 * every extent here is taken after waiting for it to reach the value the script guarantees.
 *
 * ## Harness fidelity
 *
 * SLOW-1 and DISCONNECT-1 are **HF2**: a real child process through the real
 * `ShExecution.invokeShell`, the real redaction pump, the real `SegmentOutputStore` on disk, and
 * the read side entered through `ConsoleReadService` — the port a Fabric-shaped consumer uses.
 *
 * RECONNECT-1 is **HF3**: two forked processes of the installed distribution. The second process
 * learns its position from nothing but the token the first one printed, which is what makes it a
 * resume rather than a re-read.
 */
@Timeout(600)
class ObsFConsumerContinuityUatTest {

    /** Bytes per burst. Four windows at the shipped 64 KiB, so the store really commits in pieces. */
    private val burstBytes = 256 * 1024

    /** The read window. Deliberately smaller than a burst so paging is forced, not hoped for. */
    private val pageBytes = 64 * 1024

    /**
     * What a burst is COMMITTED to while the producer is still alive.
     *
     * Not the burst size, and the difference is the whole redaction contract: the redactor
     * withholds up to [SecretPatternRegistry.MIN_SECRET_WINDOW] trailing bytes because one of them
     * could still turn out to begin a secret. Measured, not assumed — the first run of this file
     * waited for 262144 and saw 262138, six bytes short, with the step sitting in its barrier doing
     * exactly what it should.
     *
     * So while the step is alive the target is `burst - floor`, and only after it finishes does the
     * extent reach the full burst. An assertion that ignored this would have "proved" a defect.
     */
    private val committedWhileRunning = burstBytes - SecretPatternRegistry.MIN_SECRET_WINDOW

    // ───────────────────────────────────────────────────────── SLOW-1

    /**
     * LAW: a consumer parked mid-drain does not stop the producer.
     *
     * The step emits one burst, blocks on a sentinel, and only emits a second burst when the test
     * releases it. The consumer reads one page and then parks on a latch it does not control. The
     * claim is checked as a comparison of two committed extents taken at two instants with the
     * consumer provably parked in between: the producer advanced, and the run finished.
     *
     * A consumer that backpressured execution could not produce that second extent, because the
     * step would be waiting on the reader rather than the other way round.
     */
    @Test
    fun `SLOW-1 a consumer parked mid-drain does not stop the producer`(@TempDir root: Path) {
        linuxOnly()
        val runId = "r-obsf-slow"
        val control = Files.createDirectories(root.resolve("control"))
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val firstBurstDone = workspace.resolve("BURST1")
        val emitSecond = workspace.resolve("EMIT_SECOND")
        val secondBurstDone = workspace.resolve("BURST2")
        val release = workspace.resolve("RELEASE")

        val step = startBlockedStep(
            runId = runId,
            control = control,
            workspace = workspace,
            script = """
                head -c $burstBytes /dev/zero | tr '\0' 'a'
                touch ${firstBurstDone.toAbsolutePath()}
                while [ ! -s ${emitSecond.toAbsolutePath()} ]; do sleep 0.05; done
                head -c $burstBytes /dev/zero | tr '\0' 'b'
                touch ${secondBurstDone.toAbsolutePath()}
                while [ ! -s ${release.toAbsolutePath()} ]; do sleep 0.05; done
            """.trimIndent(),
        )

        // The consumer reads exactly one page and then parks.
        //
        // It may not start before the first burst is durable, and the second run of this row is why:
        // the consumer read at t=0, the plane had committed nothing yet, so it got a legal page of
        // ZERO bytes with no continuation — and a drain loop that honours `next` correctly walks
        // zero pages and returns zero bytes. Nothing was wrong with the store; the reader had
        // simply asked too early, and an empty early page is a valid answer, not an error.
        val mayReadFirst = CountDownLatch(1)
        val parked = CountDownLatch(1)
        val mayResume = CountDownLatch(1)
        var pageOne = ByteArray(0)
        var pageOneEnd = 0L
        var drained = ByteArray(0)
        val consumer = thread(name = "slow-consumer") {
            mayReadFirst.await()
            val first = readPage(control, runId, after = null, maxBytes = pageBytes)
            pageOne = first.bytes
            pageOneEnd = first.nextOffset
            parked.countDown()
            mayResume.await()
            // Drain until the page reports no continuation. A single resumed read is NOT enough and
            // the first run of this row proved it: it came back 6 bytes short because the redactor
            // was still withholding each burst's tail, so the producer was not finished. "Resume"
            // means resume until done, and only the continuation says when done is.
            val rest = java.io.ByteArrayOutputStream()
            var cursor = first.next
            while (cursor != null) {
                val page = readPage(control, runId, after = cursor, maxBytes = burstBytes * 4)
                rest.write(page.bytes)
                cursor = page.next
            }
            drained = rest.toByteArray()
        }

        awaitSentinel(firstBurstDone, "the step must emit its first burst")
        awaitCommittedAtLeast(
            control, runId, committedWhileRunning.toLong(), "the first burst must be durable",
        )
        mayReadFirst.countDown()
        assertTrue(parked.await(180, TimeUnit.SECONDS), "the consumer must reach its one page")

        // Sampled WITH the consumer parked, which is what makes the comparison below mean anything.
        val extentWhileParked = committedBytes(control, runId)

        // ── THE CLAIM ── the producer is already ahead of a consumer that stopped reading.
        assertTrue(
            extentWhileParked > pageOneEnd,
            "with the consumer parked after $pageOneEnd bytes the producer had committed " +
                "$extentWhileParked. A producer that waited for its reader could not be ahead.",
        )

        // The second burst happens entirely while the consumer is still parked.
        Files.writeString(emitSecond, "go")
        awaitSentinel(secondBurstDone, "the step must emit its second burst")
        val extentAfter = awaitCommittedAtLeast(
            control, runId, (burstBytes * 2 - SecretPatternRegistry.MIN_SECRET_WINDOW).toLong(),
            "the second burst must be durable",
        )
        assertTrue(
            extentAfter > extentWhileParked,
            "the consumer read nothing again between the two measurements, yet the committed " +
                "extent went from $extentWhileParked to $extentAfter",
        )

        Files.writeString(release, "go")
        // The producer finishes BEFORE the consumer resumes, and that ordering is the point this
        // row had to learn twice.
        //
        // A reader that stops at `next == null` has reached the committed end *right now* — which
        // is not the same claim as "no more bytes will arrive", and the two were conflated for
        // three runs. With the step still alive the redactor holds each burst's tail: two bursts,
        // six bytes withheld, 524282 instead of 524288. The transcript was never truncated; it was
        // not finished. That distinction is precisely what `OutputTailState.Open` and `.Sealed`
        // exist to name, and the honest way to wait for it is the seal, not another read.
        val terminal = step.await()
        mayResume.countDown()
        consumer.join(180_000)

        assertEquals(
            ShellInvocationResult.UnitValue,
            terminal,
            "the run must finish unaffected by a consumer that stopped reading",
        )
        // The exact-extent claim belongs HERE and not at the sampling point above. While the step
        // is alive the plane legitimately holds less than the step produced — the redactor's tail is
        // still buffered — so asserting equality there was asserting that redaction did not happen.
        assertEquals(
            (burstBytes * 2).toLong(),
            committedBytes(control, runId),
            "once the producer is done every byte it wrote is durable, redacted tail included",
        )
        assertArrayEquals(expectedTranscript(), pageOne + drained, "the transcript changed across the resume")

    }

    // ──────────────────────────────────────────────────── DISCONNECT-1

    /**
     * LAW: a consumer that goes away mid-run costs the RUN nothing.
     *
     * The consumer reads one page and is then abandoned — no drain, no close, no exception, just a
     * reader that stopped asking. The step is released and finishes. What is asserted: the run
     * reached its normal terminal, the Output Plane still holds every byte, the tail is `Sealed`,
     * and a FRESH reader resumes from exactly where the abandoned one stopped.
     *
     * That last one is what makes "disconnect" a consumer-side event rather than data loss: the
     * disconnect cost the consumer its place in the stream and the run nothing at all.
     */
    @Test
    fun `DISCONNECT-1 a consumer abandoned mid-run leaves the run untouched`(@TempDir root: Path) {
        linuxOnly()
        val runId = "r-obsf-disconnect"
        val control = Files.createDirectories(root.resolve("control"))
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val blocked = workspace.resolve("BLOCKED")
        val release = workspace.resolve("RELEASE")

        val step = startBlockedStep(
            runId = runId,
            control = control,
            workspace = workspace,
            script = """
                head -c $burstBytes /dev/zero | tr '\0' 'a'
                touch ${blocked.toAbsolutePath()}
                while [ ! -s ${release.toAbsolutePath()} ]; do sleep 0.05; done
                head -c $burstBytes /dev/zero | tr '\0' 'b'
            """.trimIndent(),
        )

        awaitSentinel(blocked, "the step must emit its first burst and block")
        awaitCommittedAtLeast(
            control, runId, committedWhileRunning.toLong(),
            "the first burst must be durable",
        )

        // One page, then the consumer is simply gone.
        val page = readPage(control, runId, after = null, maxBytes = pageBytes)
        assertEquals(pageBytes, page.bytes.size, "the first page is exactly one read window")
        val abandonedAt = page.next
        assertNotNull(abandonedAt, "a partial console must leave a resume point")

        // Nothing between here and the release touches the consumer.
        Files.writeString(release, "go")
        assertEquals(
            ShellInvocationResult.UnitValue,
            step.await(),
            "the run must finish unaffected by a consumer that went away",
        )
        awaitCommittedAtLeast(control, runId, (burstBytes * 2).toLong(), "the second burst must be durable")
        awaitSealedAt(control, runId, (burstBytes * 2).toLong(), "the producer must seal the stream")

        assertEquals(
            OutputTailState.Sealed((burstBytes * 2).toLong()),
            tailStateOf(control, runId),
            "the tail is sealed at its final extent once the producer is done",
        )

        // A fresh reader picks up exactly where the abandoned one stopped.
        val rest = readPage(control, runId, after = abandonedAt, maxBytes = burstBytes * 4)
        assertArrayEquals(
            expectedTranscript(),
            page.bytes + rest.bytes,
            "the bytes after the disconnect continue without a gap and without a repeat",
        )
        assertNull(rest.next, "the whole transcript has now been read, so nothing follows it")
    }

    // ───────────────────────────────────────────────────── RECONNECT-1

    /**
     * LAW: the output line survives a process, not just a connection.
     *
     * This is the row that cannot be faked in-process. It runs the INSTALLED distribution, reads
     * the first page from process A, and has process B resume from the token A printed — a token
     * and nothing else. The two page streams are then compared against one expected transcript.
     *
     * The token is the whole contract. If B could reconstruct its position any other way — from a
     * file, from a shared cursor object, from the run's own metadata — the row would still pass
     * while proving nothing about a foreign consumer. So B is handed exactly one string.
     */
    @Test
    fun `RECONNECT-1 a second OS process resumes the output line from the first one's token`(
        @TempDir dir: Path,
    ) {
        linuxOnly()
        val binary = assumeInstalledDistribution()

        val control = Files.createDirectories(dir.resolve("control"))
        val script = Files.writeString(
            dir.resolve("reconnect.pipeline.kts"),
            """
            pipeline {
                stages {
                    stage("s") {
                        sh("head -c $burstBytes /dev/zero | tr '\\0' 'x'")
                    }
                }
            }
            """.trimIndent(),
        )

        val run = Cli(
            binary, "run", "--format", "json", "--control-root", control.toString(), script.toString(),
        )
        assertEquals(0, run.exitCode, "the run must succeed; stderr:\n${run.stderr.takeLast(400)}")
        val runId = Regex("\"runId\":\"([^\"]+)\"").find(run.stdout)?.groupValues?.get(1)
        assertNotNull(runId, "the run must report its id; stdout:\n${run.stdout.takeLast(400)}")

        val opId = "$runId-s0-0"

        // Process A: one page, and a token on stderr. Then it is gone.
        val first = Cli(
            binary, "console", "--control-dir", control.toString(), runId!!, opId,
            "--max-bytes", pageBytes.toString(),
        )
        assertEquals(0, first.exitCode, "process A must succeed; stderr:\n${first.stderr.takeLast(400)}")
        assertEquals(
            pageBytes,
            first.stdoutBytes.size,
            "process A was asked for one window of a much larger transcript",
        )
        val token = first.stderr.trim()
        assertTrue(token.isNotEmpty(), "process A must leave a resume token on stderr")

        // Process B: a fresh JVM that knows nothing but the token.
        val second = Cli(
            binary, "console", "--control-dir", control.toString(), runId, opId,
            "--max-bytes", (burstBytes * 4).toString(),
            "--after-cursor", token,
        )
        assertEquals(0, second.exitCode, "process B must succeed; stderr:\n${second.stderr.takeLast(400)}")

        assertArrayEquals(
            expectedBytes('x'),
            first.stdoutBytes + second.stdoutBytes,
            "the two processes together must reconstruct the transcript with no gap and no repeat",
        )
        assertTrue(
            second.stderr.isBlank(),
            "the second page ends the transcript, so it must leave no further token; got " +
                "'${second.stderr.take(200)}'",
        )
    }

    // ────────────────────────────────────────────────────── the fixture

    /**
     * A child process held on sentinels, launched through the same production entry point the CLI
     * uses, with the redaction registry that activates the pump. Omitting the registry leaves
     * `transcriptRedactor` null and would characterise a substrate that does not ship.
     */
    private fun startBlockedStep(
        runId: String,
        control: Path,
        workspace: Path,
        script: String,
    ): BlockedStep {
        val terminal = arrayOfNulls<ShellInvocationResult>(1)
        val finished = CountDownLatch(1)
        thread(name = "sh-step-$runId") {
            terminal[0] = runBlocking {
                ShExecution.invokeShell(
                    command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                    opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0),
                    runId = runId,
                    stageIndex = 0,
                    stepIndex = 0,
                    shOptions = ShOptions(
                        workspaceRoot = workspace,
                        captureStdout = false,
                        timeoutMs = 300_000,
                        env = emptyMap(),
                        sandbox = SandboxConfig.NONE,
                    ),
                    controlDirRoot = control,
                    eventSink = InMemoryEventStore(),
                    secretPatternRegistry = SecretPatternRegistry(),
                )
            }
            finished.countDown()
        }
        return BlockedStep(finished, terminal)
    }

    private class BlockedStep(
        private val finished: CountDownLatch,
        private val terminal: Array<ShellInvocationResult?>,
    ) {
        fun await(): ShellInvocationResult {
            assertTrue(finished.await(300, TimeUnit.SECONDS), "the step must finish")
            return terminal[0] ?: error("the step produced a NULL terminal")
        }
    }

    private data class Page(
        val bytes: ByteArray,
        val next: OutputCursor?,
        /** One past the last byte of this page, in the merged console space. */
        val nextOffset: Long,
    )

    /**
     * One bounded read through the port a Fabric-shaped consumer uses.
     *
     * The page's offset is recomputed HERE rather than read from production: the claim SLOW-1
     * makes is "the producer is ahead of *this page*", and taking both sides of that comparison
     * from production would make it vacuous. Only the bytes come from the plane.
     */
    private fun readPage(control: Path, runId: String, after: OutputCursor?, maxBytes: Int): Page {
        val result = ConsoleReadService.read(control, runId, opIdFor(runId), after, maxBytes)
        val page = assertInstanceOf(ConsoleReadService.Result.Page::class.java, result).page
        val from = if (after != null && after.stream == page.stream) after.committedOffset else 0L
        return Page(page.bytes, page.next, from + page.bytes.size)
    }

    private fun committedBytes(control: Path, runId: String): Long {
        val store = OutputPlaneProvider.storeFor(control)
        return OutputPlaneProvider.streamsOf(runId, opIdFor(runId)).all
            .sumOf { store.committedExtent(it.stream) ?: 0L }
    }

    private fun tailStateOf(control: Path, runId: String): OutputTailState? {
        val store = OutputPlaneProvider.storeFor(control)
        return OutputPlaneProvider.streamsOf(runId, opIdFor(runId)).all
            .mapNotNull { store.tailState(it.stream) }
            .firstOrNull { it is OutputTailState.Sealed }
    }

    private fun opIdFor(runId: String): String = "$runId-s0-0"

    /** The whole transcript the two bursts produce: every `a`, then every `b`, and nothing else. */
    private fun expectedTranscript(): ByteArray = expectedBytes('a') + expectedBytes('b')

    private fun expectedBytes(c: Char): ByteArray = ByteArray(burstBytes) { c.code.toByte() }

    /**
     * Wait for a durable fact, under a generous bound.
     *
     * This is a barrier on state, not a timing claim: the value the script guarantees is known in
     * advance, and the loop fails loudly rather than asserting a millisecond budget.
     */
    private fun awaitCommittedAtLeast(
        control: Path,
        runId: String,
        target: Long,
        why: String,
    ): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        var observed = -1L
        while (System.nanoTime() < deadline) {
            observed = committedBytes(control, runId)
            if (observed >= target) return observed
            Thread.sleep(25)
        }
        throw AssertionError(
            "committed extent never reached $target (last saw $observed): $why",
        )
    }

    /**
     * Wait for the producer's seal, under a generous bound.
     *
     * The seal is recorded after the last commit, so a row that samples it the instant the extent
     * reaches its final value races the producer. Waiting on the fact rather than on a delay is what
     * keeps this from being an intermittent red on a loaded machine.
     */
    private fun awaitSealedAt(control: Path, runId: String, finalEnd: Long, why: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        var observed: OutputTailState? = null
        while (System.nanoTime() < deadline) {
            observed = tailStateOf(control, runId)
            if (observed is OutputTailState.Sealed && observed.finalEnd == finalEnd) return
            Thread.sleep(25)
        }
        throw AssertionError("the tail never sealed at $finalEnd (last saw $observed): $why")
    }

    /**
     * Wait for a sentinel the step creates with `touch`.
     *
     * The existence check is the whole thing and it is deliberately NOT a size check: `touch`
     * creates a ZERO-byte file, so requiring `size > 0` waits forever for a file that has arrived.
     * That bug cost this file its first two runs — both rows reported "timed out waiting for
     * BURST1/BLOCKED" against steps that had emitted their first burst and were sitting in the
     * barrier, and nothing in the message pointed at the check itself.
     */
    private fun awaitSentinel(sentinel: Path, why: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(sentinel)) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $sentinel: $why")
    }

    /**
     * RECONNECT-1 needs the distribution this source revision produces. It is not built by `test`,
     * so the row is skipped rather than faked when it is absent — a skipped HF3 row is honest, and
     * an in-process substitute wearing its name would not be.
     */
    private fun assumeInstalledDistribution(): Path {
        val root = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val moduleDir = if (root.fileName?.toString() == "pipeline-application") {
            root
        } else {
            root.resolve("v2").resolve("pipeline-application")
        }
        val installRoot = moduleDir.resolve("build").resolve("install")
        val bin = listOf("pipelinek", "pipeline-application")
            .map { installRoot.resolve(it).resolve("bin").resolve(it) }
            .firstOrNull { Files.isRegularFile(it) }
        assumeTrue(
            bin != null,
            "no installed distribution under $installRoot — run :pipeline-application:installDist",
        )
        return bin!!
    }

    /** One forked process. stdout is bytes because the transcript is not text-shaped. */
    private class Cli(val binary: Path, vararg args: String) {
        val exitCode: Int
        val stdoutBytes: ByteArray
        val stderr: String

        /**
         * stdout decoded as UTF-8, for the ONE verb whose output is a text document: `run --format
         * json`. The `console` verb is never read through this — its payload is a transcript, and
         * decoding that would be the "re-encode on the way out" bug the CLI's own KDoc names.
         */
        val stdout: String get() = stdoutBytes.toString(Charsets.UTF_8)

        init {
            val process = ProcessBuilder(listOf(binary.toString()) + args).start()
            stdoutBytes = process.inputStream.readBytes()
            stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
            exitCode = process.waitFor()
        }
    }

    private companion object {
        fun linuxOnly() = assumeTrue(!System.getProperty("os.name").contains("Windows"))
    }
}