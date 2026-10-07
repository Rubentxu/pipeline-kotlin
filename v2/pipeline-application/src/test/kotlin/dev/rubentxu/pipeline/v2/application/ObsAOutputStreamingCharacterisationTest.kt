package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
     * Drains [stream] into [sink] on its own thread, accumulating as bytes arrive.
     *
     * Incremental on purpose. The rows sample the console while the step is still blocked, so a
     * reader that only appended at EOF would report an empty console for a run whose output was
     * plainly on screen — a harness that manufactures the very absence it is looking for.
     */
    private fun accumulate(stream: java.io.InputStream, sink: StringBuilder) = thread {
        stream.bufferedReader().use { r ->
            val chunk = CharArray(4096)
            while (true) {
                val n = r.read(chunk)
                if (n < 0) break
                synchronized(sink) { sink.appendRange(chunk, 0, n) }
            }
        }
    }

    /**
     * What the forked CLI produced, in enough detail to diagnose a failure from the assertion alone.
     *
     * A typed case rather than a `Triple` plus a stderr that is read only sometimes: this harness
     * used to `DISCARD` stderr, so a script that failed to COMPILE arrived as a bare `exit 1` that
     * read exactly like "the output did not arrive". That is the harness making its own subject look
     * broken, and it cost a diagnosis — the channel the CLI complains on is part of the observation,
     * not an afterthought.
     */
    private data class CliObservation(
        val exit: Int,
        val duringBlock: String,
        val finalStdout: String,
        val stderr: String,
    )

    /**
     * Writes a script whose `sh` step blocks until the test releases it.
     *
     * The step prints, touches `BARRIER_REACHED`, then spins until `RELEASED` exists — so the
     * window in which the step is provably alive is a file's existence, not a sleep.
     */
    private fun writeBlockingScript(): Path {
        val script = tempDir.resolve("blocking.pipeline.kts")
        // ONE LINE, joined with "; ". This body is interpolated into a Kotlin string literal in the
        // generated `.pipeline.kts`, so a newline would END the literal and the script would not
        // compile at all. That is not a style preference — it is the difference between a fixture
        // that measures the product and one that measures whether Kotlin accepts a multi-line
        // literal, and because this harness used to discard stderr the compile error surfaced as a
        // bare `exit 1` and read as "the output did not arrive".
        //
        // `printf` rather than `echo`, and a marker FOLLOWED BY MORE BYTES. The redactor withholds
        // up to `maxLiteralByteLength` bytes of lookahead so it can rule out a secret spanning the
        // tail of what it has seen; a marker sitting at the very end of the transcript is therefore
        // not emittable until EOF, by construction and for good reason. Emitting PAST it is what
        // makes this row measure latency instead of redaction.
        //
        // This payload is deliberately sized BETWEEN the two floors the product can have. With no
        // secret registered the lookahead is MIN_SECRET_WINDOW (7 bytes) and 32 bytes clears it. If
        // anything registers a longer secret — as the redaction canaries did, claiming 56 bytes
        // through their hex form — this payload falls below the floor and the row goes RED. That is
        // intended: a test canary that silently makes live output invisible is a product defect, and
        // this row is where it gets caught rather than documented as normal.
        val body = listOf(
            "printf 'outra-coisa-visivel\\n'",
            "printf 'xxxxxxxxxxx\\n'",
            "touch BARRIER_REACHED",
            "while [ ! -s RELEASED ]; do sleep 0.05; done",
            "printf 'fin-del-paso\\n'",
        ).joinToString("; ")
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
    ): CliObservation {
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
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
        processes += process

        val received = StringBuilder()
        val reader = accumulate(process.inputStream, received)
        val errors = StringBuilder()
        val errorReader = accumulate(process.errorStream, errors)

        awaitBarrier()
        val duringBlock = synchronized(received) { received.toString() }
        Files.writeString(tempDir.resolve("RELEASED"), "go")

        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI did not finish")
        reader.join()
        errorReader.join()
        val finalOut = synchronized(received) { received.toString() }
        return CliObservation(
            exit = process.exitValue(),
            duringBlock = duringBlock,
            finalStdout = finalOut,
            stderr = synchronized(errors) { errors.toString() },
        )
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
     * NON-REGRESSION (was CHARACTERISATION) — sh output reaches the live console while the step runs.
     *
     * ## The transition this row records
     *
     * It asserted that `sh` output reached NEITHER the live console nor the final document, and
     * named its closers: OBS-B put the bytes in the Output Plane, OBS-E4 put a reader on it. Both
     * have landed, so the row is INVERTED rather than deleted — it still forks the real `MainKt`,
     * still blocks the step on a sentinel file, and still asserts on discrete observations. What
     * changed is which way the assertion points.
     *
     * The mechanism that makes it true is not a faster renderer. `sh` output is not on the event
     * spine and never was, so no change to how events are mirrored could ever have produced this.
     * `startLiveOutputObserver` follows the Output Plane on its own thread while the run is in
     * flight, and the observation is of DURABLE committed bytes.
     */
    @Test
    fun `sh output reaches the live console while the step is still running`() {
        val script = writeBlockingScript()
        val run = runSamplingDuringBlock(script)

        assertEquals(0, run.exit, "CLI failed. stderr was:\n${run.stderr}")
        assertTrue(
            Files.exists(tempDir.resolve("BARRIER_REACHED")),
            "the step never reached its barrier, so nothing observed here means nothing. " +
                "stderr was:\n${run.stderr}",
        )
        assertTrue(
            run.duringBlock.contains(MARKER),
            "the bytes a step prints must be visible WHILE the step is provably still blocked. " +
                "The step printed before touching the barrier, so its bytes were committed before " +
                "this snapshot was taken and the only thing that can withhold them is the " +
                "reader. stdout so far was:\n${run.duringBlock}\nstderr was:\n${run.stderr}",
        )
        assertTrue(
            run.finalStdout.contains(MARKER),
            "and they must still be there once the run ends, because the observer emits up to the " +
                "instant it is told to stop. stdout was:\n${run.finalStdout}",
        )
        assertTrue(
            run.finalStdout.contains("fin-del-paso"),
            "the bytes written AFTER the barrier arrive too, which is what separates an observer " +
                "that follows the run from one that snapshots it once. stdout was:\n${run.finalStdout}",
        )
        assertTrue(
            run.finalStdout.contains("[PipelineK] [stage: build]"),
            "sanity: PipelineK own blocks still render, so the gap that used to be here was " +
                "PROCESS OUTPUT specifically and not rendering. stdout was:\n${run.finalStdout}",
        )
    }

    /**
     * NON-REGRESSION (was CHARACTERISATION) — the machine format carries process output, live.
     *
     * ## The transition this row records
     *
     * The original row named two defects as one, because they were one defect seen twice: the
     * buffered writer withheld bytes, AND jsonl was the event spine while process output was not on
     * it at all. It asserted that the marker appeared in NO CLI format, live or after the fact, and
     * that was true — `pipeline console` was not a public verb, so the bytes were durable and
     * unreachable.
     *
     * OBS-B put the bytes in the Output Plane and OBS-E4 put a reader on it. The row is INVERTED,
     * not deleted: same fork, same barrier, same discrete observations.
     *
     * ## What it does NOT assert, and why that matters
     *
     * It does not assert an INTERLEAVING of events and output, and it must not. There is no total
     * order between an event sequence and an output ordinal in this repository — `ObservationView.FULL`
     * stays refused for exactly that reason — so a merged document claiming one would fabricate a
     * causality the storage never recorded. What this row pins is that BOTH planes arrive on the
     * protocol stream, that output arrives while the step is alive, and that every line parses.
     * Their relative order is the consumer's own observation order, not a claim about causality.
     */
    @Test
    fun `jsonl carries process output and every line stays parseable`() {
        val script = writeBlockingScript()
        val run = runSamplingDuringBlock(script, "--format", "jsonl")

        assertEquals(0, run.exit, "CLI failed. stderr was:\n${run.stderr}")

        // Reassembled by ORDINAL, not by searching the raw stream.
        //
        // The first poll of the drain asks for `max(1, available())`, and at that instant nothing has
        // been buffered yet, so the first frame is legitimately ONE byte and the marker arrives split
        // across frames. That is the documented shape of a live byte stream, not a defect: each
        // record carries `from`/`to`, so a consumer's job is to concatenate by offset. Asserting on
        // the raw text would be asserting that the plane happens to chunk on line boundaries, which
        // it never promised — and a row that pins that would go RED the first time a pump emitted a
        // different-sized chunk.
        val outputRecords = run.finalStdout.lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "output" }
            .sortedBy { it["ordinal"]?.jsonPrimitive?.long }

        assertTrue(
            outputRecords.isNotEmpty(),
            "the machine format must carry process output records at all. stdout was:\n${run.finalStdout}",
        )
        assertTrue(
            outputRecords.joinToString("") { it["text"]!!.jsonPrimitive.content }.contains(MARKER),
            "the marker's BYTES must reach the protocol stream, whichever frames they arrived in. " +
                "stdout was:\n${run.finalStdout}",
        )

        val duringRecords = run.duringBlock.lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "output" }
        assertTrue(
            duringRecords.isNotEmpty(),
            "the machine format is the one meant for pipes and agents, so it is the one that must " +
                "not wait for the run to end. The step printed before touching the barrier, so " +
                "those bytes were committed before this snapshot. stdout so far was:\n" +
                "${run.duringBlock}\nstderr was:\n${run.stderr}",
        )

        assertTrue(
            run.finalStdout.contains("\"channel\""),
            "and the record must carry its channel, because that is what makes stdout and stderr " +
                "selectable downstream instead of one undifferentiated transcript. " +
                "stdout was:\n${run.finalStdout}",
        )

        // The protocol is only worth having if a consumer can parse it while the run is still going,
        // so this checks EVERY line rather than the ones it expects: one diagnostic line on stdout
        // would break a pipe reader that had been working a second earlier.
        val unparseable = run.finalStdout.lines().filter { it.isNotBlank() }.filterNot { line ->
            runCatching { Json.parseToJsonElement(line) }.isSuccess
        }
        assertTrue(
            unparseable.isEmpty(),
            "jsonl reserves stdout for the protocol, so every line must parse. Non-JSON on this " +
                "stream breaks a consumer that had been working a second earlier. Offenders: $unparseable",
        )

        assertTrue(
            run.finalStdout.contains("RunStarted") || run.finalStdout.contains("StageStarted"),
            "the event spine must still be delivered — jsonl is not an output-only format. " +
                "stdout was:\n${run.finalStdout}",
        )
    }

    private companion object {
        /** The marker the blocking step prints before it reaches its barrier. */
        const val MARKER = "outra-coisa-visivel"
    }
}
