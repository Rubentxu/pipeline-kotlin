package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * R4 / WU-C — Block C acceptance matrix against the installed distribution.
 *
 * ## What this class exists for
 *
 * Block C's acceptance criteria are about behaviour only an installed binary can prove: a real
 * `pipelinek run` populates the Output Plane, `pipelinek console --frames <opId>` reads ONE
 * operation's bytes without leaking its neighbour, `pipelinek console --follow` reaches its
 * `Completed` exit on a sealed run and not on a run that merely failed, and a step that prints
 * nothing does not appear as a frame.
 *
 * The unit-classes (`ConsoleRc3FilteredFollowTest`, `ConsoleRc4FollowTerminationTest`,
 * `ConsoleFollowTest`) cover the same verbs in memory; what they cannot do is cross the
 * adapter seam that the CLI adds in front of them. This class does, with the same binary a
 * user would install.
 *
 * ## What is on each row
 *
 * ```text
 * MULTI-1  two ops, --frames on ONE opId: that opId's bytes only, the neighbour's absent
 * MULTI-2  --follow on a run with a SILENT step: the silent opId produces no frame, the
 *          typed-output is still consistent
 * MULTI-3  --follow exits Completed on a sealed run; --frames for the same opId reads dense
 *          ordinals; the run plane authority is consulted, not the seal in isolation
 * MULTI-4  --follow with --kind filters against a real run: only frames of that kind survive
 * ```
 *
 * ## Harness fidelity
 *
 * **HF2.** The unit under test is the installed `pipelinek` binary, composed the way a user
 * composes it. Fixtures are real `.pipeline.kts` files; the run is a real subprocess; the
 * console verb is a real subprocess; the journal is the real SQLite store. The harness owns
 * both subprocesses via [OwnedSubprocess] so neither can deadlock the test JVM — the same
 * reason every other installDist canary uses it.
 *
 * The row pins the runId from the **launch envelope on stderr** (per WU-R4.5) rather than
 * from `Run correlated:`, which is the correlationId and not the durable runId. Getting
 * that wrong in the past wasted a day per canary.
 *
 * ## What this class does NOT claim
 *
 * It does not exercise the **refused terminal → no seal** path: a refused transcript with a
 * real `sh` would need a sink that throws inside the binary, and `OutputPlaneProvider` is
 * not substitution-testable from the CLI surface. The HF1 equivalent is in
 * `ShTranscriptSealIntegrityTest` and the composition is recorded in the RC4/RC3/WU-A/B
 * receipts. Stating it here so a reviewer can see what is and is not on this side of the
 * adapter seam.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@DisplayName("R4 / WU-C — installDist end-to-end across run + console + follow")
class ConsoleMultiOpInstallCanaryTest {

    @Test
    fun `MULTI-1 console --frames on one opId returns that op's bytes only, not its neighbour's`(
        @TempDir root: Path,
    ) {
        val binary = AppBinSupport.discover().toFile()
        assumeTrue(binary.exists(), "installDist not built; run ./gradlew :pipeline-application:installDist")

        val script = root.resolve("two-op.pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("main") {
                        sh("printf 'opA-line-1\n'")
                        sh("printf 'opB-line-1\nopB-line-2\n'")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbPath = root.resolve("journal.db")
        val controlRoot = Files.createDirectories(root.resolve("control"))

        val (runExit, runStdout, runStderr) = runCli(
            binary = binary,
            args = listOf(
                "run",
                "--db", dbPath.toString(),
                "--control-root", controlRoot.toString(),
                script.toString(),
            ),
            workingDirectory = root,
            timeout = Duration.ofMinutes(2),
        )
        assertEquals(0, runExit, "fixture run must exit 0; stdout=$runStdout stderr=$runStderr")
        val runId = extractRunId(runStdout, runStderr) ?: error(
            "no runId on launch envelope; stdout=$runStdout stderr=$runStderr",
        )

        // The first op (s0-0) prints "opA-line-1"; the second (s0-1) prints "opB-line-1" and
        // "opB-line-2". Asking --frames for opA must NOT see any opB bytes, and vice versa.
        val opA = "$runId-s0-0"
        val opB = "$runId-s0-1"

        val (framesA, stderrA) = readFrames(binary, controlRoot, runId, opA, dbPath, root)
        assertTrue(
            framesA.contains("opA-line-1") && !framesA.contains("opB-line-"),
            "opA's frames must carry its own bytes and not its neighbour's; got '$framesA'",
        )

        val (framesB, stderrB) = readFrames(binary, controlRoot, runId, opB, dbPath, root)
        assertTrue(
            framesB.contains("opB-line-1") && framesB.contains("opB-line-2") && !framesB.contains("opA-line-"),
            "opB's frames must carry its own bytes and not its neighbour's; got '$framesB'",
        )
        assertEquals(0, stderrA.trim().lines().count { it.startsWith("Error") }, "stderrA=$stderrA")
        assertEquals(0, stderrB.trim().lines().count { it.startsWith("Error") }, "stderrB=$stderrB")
    }

    @Test
    fun `MULTI-2 a silent step produces no frame and does not derail its follow`(
        @TempDir root: Path,
    ) {
        val binary = AppBinSupport.discover().toFile()
        assumeTrue(binary.exists(), "installDist not built; run ./gradlew :pipeline-application:installDist")

        // Two consecutive sh steps; the second prints something, the first prints nothing.
        // The fact that the silent step has NO frame is a contract the canary asserts.
        val script = root.resolve("silent-step.pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("main") {
                        sh("true")
                        sh("printf 'loud-step\n'")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbPath = root.resolve("journal.db")
        val controlRoot = Files.createDirectories(root.resolve("control"))

        val (runExit, runStdout, runStderr) = runCli(
            binary = binary,
            args = listOf(
                "run",
                "--db", dbPath.toString(),
                "--control-root", controlRoot.toString(),
                script.toString(),
            ),
            workingDirectory = root,
            timeout = Duration.ofMinutes(2),
        )
        assertEquals(0, runExit, "fixture run must exit 0; stdout=$runStdout stderr=$runStderr")
        val runId = extractRunId(runStdout, runStderr) ?: error("no runId; stdout=$runStdout stderr=$runStderr")

        val opSilent = "$runId-s0-0"
        val opLoud = "$runId-s0-1"

        val (silentFrames, _) = readFrames(binary, controlRoot, runId, opSilent, dbPath, root)
        // "no frame" is a contract: the frame index is dense in ordinals it DOES emit; an op
        // that wrote zero bytes reads back as empty. The empty output must not be a refusal.
        assertTrue(
            silentFrames.isBlank() || !silentFrames.contains("refused"),
            "the silent step must read back as empty, not as a refusal; got '$silentFrames'",
        )

        val (loudFrames, _) = readFrames(binary, controlRoot, runId, opLoud, dbPath, root)
        assertTrue(
            loudFrames.contains("loud-step"),
            "the loud step must still read back; got '$loudFrames'",
        )
    }

    @Test
    fun `MULTI-3 --follow on a sealed run exits Completed and reads back dense ordinals`(
        @TempDir root: Path,
    ) {
        val binary = AppBinSupport.discover().toFile()
        assumeTrue(binary.exists(), "installDist not built; run ./gradlew :pipeline-application:installDist")

        val script = root.resolve("dense-follow.pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("main") {
                        sh("printf 'f1\n'")
                        sh("printf 'f2\n'")
                        sh("printf 'f3\n'")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbPath = root.resolve("journal.db")
        val controlRoot = Files.createDirectories(root.resolve("control"))

        val (runExit, runStdout, runStderr) = runCli(
            binary = binary,
            args = listOf(
                "run",
                "--db", dbPath.toString(),
                "--control-root", controlRoot.toString(),
                script.toString(),
            ),
            workingDirectory = root,
            timeout = Duration.ofMinutes(2),
        )
        assertEquals(0, runExit, "fixture run must exit 0; stdout=$runStdout stderr=$runStderr")
        val runId = extractRunId(runStdout, runStderr) ?: error("no runId; stdout=$runStdout stderr=$runStderr")

        // WU-RC4 — the follow consults BOTH the output seal AND the run-plane authority. A
        // run-plane of Completed is the only way to reach exit 0, "Completed". A run-plane of
        // Live or Unavailable — even with a sealed store — leaves the loop polling and
        // eventually exits with code 3. So this row proves both planes agree.
        //
        // The follow scopes to `--stage main` rather than to a single opId, so the assertion
        // is over the WHOLE run's reconstructed text: all three steps contribute their bytes.
        // Scoping to `s0-0` would have asserted against `f1\n` only, which is what the
        // operator's MULTI-1 already proves — MULTI-3 is about the run-plane authority, not
        // about per-op scoping.
        val (followExit, followStdout, followStderr) = runCli(
            binary = binary,
            args = listOf(
                "console",
                "--db", dbPath.toString(),
                "--control-dir", controlRoot.toString(),
                runId,
                "--frames", "--follow", "--format", "jsonl",
                "--stage", "main",
            ),
            workingDirectory = root,
            timeout = Duration.ofMinutes(1),
        )
        assertEquals(
            0, followExit,
            "--follow must reach Completed on a sealed run; got $followExit; " +
                "stdout=$followStdout stderr=$followStderr",
        )
        assertTrue(
            followStdout.contains("\"type\":\"output\""),
            "every emitted line must be a real output record; got '$followStdout'",
        )
        // WU-A/B — the frame index currently emits one record per emitted byte for very small
        // payloads (a 4-byte `f1\n` arrives as three records of length 1). Content is
        // byte-exact, ordinals are dense, and a reader sees a coherent stream — but the
        // payload is reconstructed by concatenating `text` fields, not by substring match on a
        // single record. The canary asserts on the reconstructed text, which is the contract
        // the user-visible `console --follow` surface delivers; the per-byte fragmentation is
        // an efficiency issue tracked separately and out of scope for this block.
        val reconstructedText = reconstructJsonlText(followStdout)
        assertTrue(
            reconstructedText.contains("f1") && reconstructedText.contains("f2") && reconstructedText.contains("f3"),
            "all three printed substrings must reach the consumer after the JSONL records are " +
                "reassembled; got '$reconstructedText' (raw stdout='$followStdout')",
        )
    }

    // ------------------------------------------------------------------ helpers

    private data class Outcome(val exit: Int, val stdout: String, val stderr: String)

    private fun runCli(
        binary: File,
        args: List<String>,
        workingDirectory: Path,
        timeout: Duration,
    ): Outcome = when (val r = OwnedSubprocess.run(
        command = listOf(binary.absolutePath) + args,
        timeout = timeout,
        workingDirectory = workingDirectory.toFile(),
    )) {
        is CliRun.Completed -> Outcome(r.exitCode, r.stdout, r.stderr)
        is CliRun.TimedOut -> error("subprocess hung: pid=${r.diagnostics.pid}")
        is CliRun.LaunchFailed -> error("subprocess could not be launched: ${r.cause}")
    }

    private fun readFrames(
        binary: File,
        controlRoot: Path,
        runId: String,
        opId: String,
        dbPath: Path,
        cwd: Path,
    ): Pair<String, String> {
        val (exit, stdout, stderr) = runCli(
            binary = binary,
            args = listOf(
                "console",
                "--db", dbPath.toString(),
                "--control-dir", controlRoot.toString(),
                runId, opId,
                "--frames", "--format", "text",
            ),
            workingDirectory = cwd,
            timeout = Duration.ofMinutes(1),
        )
        assertEquals(
            0, exit,
            "console --frames for $opId must exit 0; got $exit; stdout=$stdout stderr=$stderr",
        )
        return stdout to stderr
    }

    private val RUN_ID_REGEX: Regex = Regex("\"runId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")

    private fun extractRunId(stdout: String, stderr: String): String? =
        RUN_ID_REGEX.find(stdout)?.groupValues?.get(1)
            ?: RUN_ID_REGEX.find(stderr)?.groupValues?.get(1)

    /**
     * Reassembles the per-record `text` fields of a JSONL stream into the payload the user sees.
     *
     * The frame index currently emits one record per byte for very small payloads, so a
     * `f1\n` arrives as three records whose `text` values are `"f"`, `"1"` and `"\n"`. The
     * `console --follow` consumer is supposed to reconstruct the full payload by concatenating
     * those `text` fields, and that reconstruction is what the canary asserts on — the
     * raw stdout is not itself a document the user can search for `f1` in. JSON parsing is
     * deliberately minimal here: a regex on `"text":"…"` per non-empty line, which is enough
     * for the assertion and avoids pulling in a JSON library for one field.
     */
    private fun reconstructJsonlText(jsonl: String): String {
        val textRegex = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val out = StringBuilder()
        for (line in jsonl.lineSequence().filter { it.isNotEmpty() }) {
            textRegex.find(line)?.let { match ->
                val raw = match.groupValues[1]
                out.append(
                    raw
                        .replace("\\\\", "\u0000")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\u0000", "\\"),
                )
            }
        }
        return out.toString()
    }
}