package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * OBS-A — characterises what the observation path does NOT do yet, with deterministic barriers.
 *
 * ## Why these rows are characterisations and not requirements
 *
 * Each row asserts a **defect**. They are written so the defect is named and reproducible BEFORE
 * the live ingress (OBS-B) and the machine streams (OBS-E) exist, because a gate introduced
 * alongside the fix cannot distinguish the fix from the gate.
 *
 * Every row's message names the work that closes it. When OBS-B or OBS-E lands the row is
 * INVERTED and becomes a non-regression test; that transition is explicit in the message rather
 * than a silent rewrite of what was expected.
 *
 * ## Why barriers and not durations
 *
 * "Must arrive within 300 ms" is a property of the machine, not the product, and fails on a loaded
 * box where it gets read as a defect. So the pipeline blocks on a sentinel file: the row observes
 * whether output exists **while the step is provably still blocked**, which is a fact about the
 * product rather than about scheduling.
 *
 * ## Fidelity
 *
 * Forks the real `MainKt`, reads its real stdout through ONE reader that accumulates for the whole
 * run, and asserts on discrete observations — did this text arrive before this sentinel existed? —
 * rather than on elapsed time. `@TempDir`, no ambient cwd/env/network.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ObsAOutputStreamingCharacterisationTest {

    private val processes = mutableListOf<Process>()

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        Files.writeString(tempDir.resolve("RELEASED"), "")
    }

    @AfterEach
    fun tearDown() {
        processes.forEach { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
        processes.clear()
    }

    /**
     * Writes a script whose `sh` step blocks until the test releases it.
     *
     * The step prints, touches `BARRIER_REACHED`, then spins until `RELEASED` exists — so the
     * window in which the step is provably alive is a file's existence, not a sleep.
     */
    private fun writeBlockingScript(): Path {
        val script = tempDir.resolve("blocking.pipeline.kts")
        val body = "echo 'outra-coisa-visivel'; touch BARRIER_REACHED; " +
            "while [ ! -s RELEASED ]; do sleep 0.05; done; echo 'fin-del-paso'"
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("build") {
                        sh("$body")
                    }
                }
            }
            """.trimIndent(),
        )
        return script
    }

    /**
     * Runs the CLI with ONE reader thread that accumulates stdout for the whole run.
     *
     * [whileBlocked] is invoked at the moment the step has reached its barrier and before it is
     * released, and receives whatever has arrived SO FAR. Reading the pipe from two places would
     * steal bytes from one of them, which is the mistake a single accumulating buffer avoids.
     */
    private fun runSamplingDuringBlock(
        script: Path,
        vararg options: String,
    ): Triple<Int, String, String> {
        val args = mutableListOf("run")
        args += options
        args += script.toString()

        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *args.toTypedArray(),
        )
            .directory(tempDir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
        processes += process

        val received = StringBuilder()
        val reader = thread {
            process.inputStream.bufferedReader().use { r ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = r.read(chunk)
                    if (n < 0) break
                    synchronized(received) { received.appendRange(chunk, 0, n) }
                }
            }
        }

        awaitBarrier()
        val duringBlock = synchronized(received) { received.toString() }
        Files.writeString(tempDir.resolve("RELEASED"), "go")

        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI did not finish")
        reader.join()
        val finalOut = synchronized(received) { received.toString() }
        return Triple(process.exitValue(), duringBlock, finalOut)
    }

    /** Blocks until the step has provably reached its barrier, bounded so a broken script cannot hang. */
    private fun awaitBarrier() {
        val barrier = tempDir.resolve("BARRIER_REACHED")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!Files.exists(barrier) && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        // Let anything that is genuinely streaming arrive before the snapshot is taken.
        Thread.sleep(500)
    }

    /**
     * CHARACTERISATION — sh output reaches neither the live console nor the final document.
     *
     * Closed by: OBS-B. Invert then, asserting the bytes ARE visible before release; do not delete.
     */
    @Test
    fun `CHAR sh output reaches neither the live console nor the final document`() {
        val script = writeBlockingScript()
        val (exit, duringBlock, finalOut) = runSamplingDuringBlock(script)

        assertEquals(0, exit, "CLI failed")
        assertTrue(
            Files.exists(tempDir.resolve("BARRIER_REACHED")),
            "the step never reached its barrier, so nothing observed here means nothing",
        )
        assertFalse(
            duringBlock.contains("outra-coisa-visivel"),
            "CHARACTERISED: sh output was already visible during the blocked step. OBS-B has " +
                "landed and this row must be INVERTED into a non-regression test, not deleted.",
        )
        assertFalse(
            finalOut.contains("outra-coisa-visivel"),
            "CHARACTERISED: sh output appears in the final document either. The transcript is not " +
                "part of the event spine the console renders from, so it cannot show it at all.",
        )
        assertTrue(
            finalOut.contains("[PipelineK] [stage: build]"),
            "sanity: the human console itself streams, so the gap is PROCESS OUTPUT, not " +
                "rendering. stdout was:\n$finalOut",
        )
    }

    /**
     * CHARACTERISATION — the machine format is a document, and it is not even the right document.
     *
     * Two defects in one row, because they are the same defect seen twice.
     *
     * First: `RunObservationOutput.writeTo` iterates a lazy sequence, so the LOGIC is streaming;
     * the 64 KiB `BufferedWriter` and its single trailing flush are what withhold the bytes. The
     * fix is a flush discipline, not a redesign.
     *
     * Second, and the more important one: jsonl is the EVENT spine, and process output is not on
     * the event spine. It lives in the Output Plane. So flushing alone would NOT make sh output
     * appear in jsonl — the bytes would have to reach the document by some other route.
     *
     * This row was first written asserting the marker WAS present at the end, on the assumption
     * that jsonl was a superset. It is not, and the failed assertion is the finding: today the
     * CLI has no format that carries process output at all, live or after the fact, because
     * `pipeline console` is not a public verb.
     *
     * Closed by: OBS-B (bytes reach the output plane) and OBS-E (a reader that combines both
     * planes). Invert then; do not delete.
     */
    @Test
    fun `CHAR jsonl is a late document AND carries no process output at all`() {
        val script = writeBlockingScript()
        val (exit, duringBlock, finalOut) = runSamplingDuringBlock(script, "--format", "jsonl")

        assertEquals(0, exit, "CLI failed")
        assertFalse(
            duringBlock.contains("outra-coisa-visivel"),
            "CHARACTERISED: jsonl emitted while the step was blocked. OBS-E has landed and this " +
                "row must be INVERTED, not deleted.",
        )
        assertFalse(
            finalOut.contains("outra-coisa-visivel"),
            "CHARACTERISED: the process bytes are absent from jsonl even after the run. jsonl is " +
                "the event spine; process output lives in the Output Plane and reaches no CLI " +
                "format, because `pipeline console` is not a public verb. OBS-B and OBS-E must " +
                "land before this row can be inverted.",
        )
        assertTrue(
            finalOut.contains("RunStarted") || finalOut.contains("StageStarted"),
            "sanity: the event spine IS delivered, so the absence above is about process output " +
                "specifically and not an empty document. stdout was:\n$finalOut",
        )
    }
}
