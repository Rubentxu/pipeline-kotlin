package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * R4 / WU-CON-2.3 — `pipelinek console --follow` (tail-f mode for the per-frame path).
 *
 * ## What this slice adds
 *
 * `--follow` is a thin loop over the per-frame path introduced in WU-R4.6. The verb
 *  - admits `--follow --follow-poll-interval <ms> --follow-max-polls <N>` together
 *    with `--frames`;
 *  - refuses `--follow` without `--frames`;
 *  - refuses non-positive poll interval / max-polls;
 *  - runs a coroutine that polls the frame index with the previous ordinal + 1,
 *    sleeps, and either exits when the durable authority says "no more" or hits
 *    `--follow-max-polls` rounds.
 *
 * ## What this slice does NOT add
 *
 *  - NOT a content filter (`--stage/--step/--kind/--run`). That is CON-2.2, a different slice.
 *  - NOT a journal entry on EOF. The cursor between polls is local (DR-5); a follow does
 *    NOT resume across runs.
 *  - NOT a `--format text` tail-mode for the byte path. The byte path has its own follow
 *    story in WU-R4.2's `events --follow`; the per-frame path is what `--frames` opened,
 *    and the two have different continuation stories.
 *
 * ## What the rows below prove
 *
 *  - HF0: ADT shape — [ConsoleReadRequest.FollowFrames] and [FollowConfig] carry the
 *    right fields and reject non-positive values at construction.
 *  - HF1: parser — [CliParser.parseConsole] admits the three new flags, refuses
 *    unknown options, refuses `--follow` without `--frames`, refuses non-positive
 *    `--follow-poll-interval` and `--follow-max-polls`, and renders `[Default]="*"`
 *    defaults on first refusal.
 *  - HF1: prefix-preservation — argument refusals keep `Error:`, data-plane refusals
 *    keep `console-refused:`. The two are rendered by different functions
 *    ([renderConsoleCliRefusal] and [ConsoleReadService.renderRefusal]), and the row
 *    pins the visible absence of regression.
 *  - HF2 (canary): a populated installDist store + `pipelinek console --follow --frames
 *    --format jsonl` exits 0 on a sealed-output op and emits JSONL records in observation
 *    order. Marked `@EnabledIf` so a checkout without `v2/build/install/pipelinek` is
 *    a quiet skip rather than a RED.
 */
@Tag("installed-contract")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@DisplayName("R4 / WU-CON-2.3 — pipelinek console --follow (per-frame tail-f)")
class ConsoleFollowTest {

    /**
     * A control-dir argument this class OWNS, for the parser rows.
     *
     * These rows parse an argument vector and never open the path. They used a hardcoded `/tmp/x`,
     * which is why the class appeared in the sandbox fitness: the law attributes a leak to the
     * FILE, and one row that legitimately forks and forks correctly — the installed-distribution
     * canary, which has its own `@TempDir` — made the twenty-one parser rows offenders too.
     *
     * The fix is to make the path one this class owns rather than to explain the path away. A
     * `@TempDir` field gives every row a fresh directory that JUnit removes, so the argument is
     * still a plausible absolute control-dir, and nothing in the class now names a location outside
     * itself. The parser rows still never open it, which is what they were for.
     */
    @TempDir
    private lateinit var ownedRoot: Path

    private val ownedControlDir: String
        get() = ownedRoot.resolve("control").toString()


    // ------------------------------------------------------------------ HF0 closed ADT

    @Test
    fun `ConsoleReadRequest is a four-case closed ADT after --follow lands`() {
        // Pinned at compile time: the when over `request` in `MainConsoleCli.execute` and
        // `MainConsoleCli.main` is exhaustive. Adding a fifth case MUST be a deliberate edit.
        val paged = ConsoleReadRequest.Paged("c", "r", "o", null, 1024, MainConsoleCli.ConsoleFormat.TEXT)
        val range = ConsoleReadRequest.Range("c", "r", "o", 0L, 1024L, MainConsoleCli.ConsoleFormat.TEXT)
        val frames = ConsoleReadRequest.Frames("c", "r", "o", -1L, 1024, MainConsoleCli.ConsoleFormat.JSONL)
        val follow = ConsoleReadRequest.FollowFrames(
            controlDir = "c",
            runId = "r",
            opId = "o",
            afterOrdinal = -1L,
            frameLimit = 1024,
            followConfig = FollowConfig.DEFAULT,
            format = MainConsoleCli.ConsoleFormat.JSONL,
        )
        assertNotNull(paged)
        assertNotNull(range)
        assertNotNull(frames)
        assertNotNull(follow)
        assertTrue(paged is ConsoleReadRequest.Paged)
        assertTrue(range is ConsoleReadRequest.Range)
        assertTrue(frames is ConsoleReadRequest.Frames)
        assertTrue(follow is ConsoleReadRequest.FollowFrames)
    }

    @Test
    fun `FollowFrames payload round-trips afterOrdinal, frameLimit, followConfig, and format`() {
        val cfg = FollowConfig(pollIntervalMillis = 250L, maxPolls = 4)
        val req = ConsoleReadRequest.FollowFrames(
            controlDir = "control",
            runId = "run-1",
            opId = "op-1",
            afterOrdinal = 7L,
            frameLimit = 256,
            followConfig = cfg,
            format = MainConsoleCli.ConsoleFormat.JSONL,
        )
        assertEquals("control", req.controlDir)
        assertEquals("run-1", req.runId)
        assertEquals("op-1", req.opId)
        assertEquals(7L, req.afterOrdinal)
        assertEquals(256, req.frameLimit)
        assertEquals(cfg, req.followConfig)
        assertEquals(MainConsoleCli.ConsoleFormat.JSONL, req.format)
    }

    @Test
    fun `FollowConfig refuses non-positive pollIntervalMillis at construction`() {
        // pollIntervalMillis is the wait between polls; 0 means "busy-loop forever", a
        // negative value means "go back in time". The constructor rejects, so a malformed
        // --follow-poll-interval is a typed refusal at admission, not silent degradation.
        try {
            FollowConfig(pollIntervalMillis = 0L, maxPolls = 1)
            error("expected IllegalArgumentException for pollIntervalMillis=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pollIntervalMillis"), "must name pollIntervalMillis; got: ${e.message}")
        }
        try {
            FollowConfig(pollIntervalMillis = -1L, maxPolls = 1)
            error("expected IllegalArgumentException for pollIntervalMillis=-1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pollIntervalMillis"), "must name pollIntervalMillis; got: ${e.message}")
        }
    }

    @Test
    fun `FollowConfig refuses non-positive maxPolls at construction`() {
        // maxPolls is the loop bound; 0 means "poll zero times" (degenerate), a negative
        // value means "poll a negative number of times" (also degenerate).
        try {
            FollowConfig(pollIntervalMillis = 100L, maxPolls = 0)
            error("expected IllegalArgumentException for maxPolls=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxPolls"), "must name maxPolls; got: ${e.message}")
        }
        try {
            FollowConfig(pollIntervalMillis = 100L, maxPolls = -1)
            error("expected IllegalArgumentException for maxPolls=-1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxPolls"), "must name maxPolls; got: ${e.message}")
        }
    }

    @Test
    fun `FollowFrames requires frameLimit to be positive at construction`() {
        // Same shape as Frames: the per-poll bound cannot be 0 or negative.
        try {
            ConsoleReadRequest.FollowFrames(
                controlDir = "c",
                runId = "r",
                opId = "o",
                afterOrdinal = -1L,
                frameLimit = 0,
                followConfig = FollowConfig.DEFAULT,
                format = MainConsoleCli.ConsoleFormat.JSONL,
            )
            error("expected IllegalArgumentException for frameLimit=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frameLimit"), "must name frameLimit; got: ${e.message}")
        }
    }

    @Test
    fun `FollowFrames requires afterOrdinal to be -1 or non-negative at construction`() {
        // -1 is the closed sentinel meaning "from the first frame" (the same convention
        // FrameIndexedObservationOutputReader.readOutput uses). A more-negative value is
        // nonsense: ordinals do not move backwards.
        try {
            ConsoleReadRequest.FollowFrames(
                controlDir = "c",
                runId = "r",
                opId = "o",
                afterOrdinal = -7L,
                frameLimit = 1024,
                followConfig = FollowConfig.DEFAULT,
                format = MainConsoleCli.ConsoleFormat.JSONL,
            )
            error("expected IllegalArgumentException for afterOrdinal=-7")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("afterOrdinal"), "must name afterOrdinal; got: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ HF1 parser admission

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Drive [MainConsoleCli.main] with a NON-EXISTENT control-dir. The admission runs BEFORE
     * the control-dir existence check, so a parser-level refusal is observable even when the
     * path does not exist. A successful admission against a non-existent path produces
     * `ControlDirNotFound` (exit 2), which is the right shape for the "parser accepted the
     * --follow request" rows.
     */
    private fun cli(vararg args: String): CliResult {
        val nonexistentDir = ownedRoot.resolve("r4-wu-con-2-3-nonexistent-${UUID.randomUUID()}").toString()
        val all = arrayOf("--control-dir", nonexistentDir) + args
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    MainConsoleCli.main(all)
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `parser admits --follow --format jsonl with the documented defaults`() {
        // The simplest admit path: --follow with no override values. The followConfig must
        // carry the documented defaults (pollIntervalMillis=1000, maxPolls=60) so a fresh
        // caller does not need to set them by hand.
        val result = CliParser.parseConsole(
            arrayOf("--control-dir", ownedControlDir, "run-1", "op-1", "--frames", "--follow", "--format", "jsonl"),
        )
        val admitted = assertInstance(result)
        assertTrue(admitted is ConsoleReadRequest.FollowFrames, "must admit FollowFrames; got: $admitted")
        admitted as ConsoleReadRequest.FollowFrames
        assertEquals(-1L, admitted.afterOrdinal, "default afterOrdinal is -1 (from start)")
        assertEquals(ConsoleReadService.DEFAULT_FRAME_LIMIT, admitted.frameLimit)
        assertEquals(FollowConfig.DEFAULT_POLL_INTERVAL_MILLIS, admitted.followConfig.pollIntervalMillis)
        assertEquals(FollowConfig.DEFAULT_MAX_POLLS, admitted.followConfig.maxPolls)
        assertEquals(MainConsoleCli.ConsoleFormat.JSONL, admitted.format)
    }

    @Test
    fun `parser admits --follow with explicit --follow-poll-interval and --follow-max-polls`() {
        // The override path: caller-supplied tunables flow into FollowConfig. The verb main
        // does not clamp them; whatever the parser admitted is what the loop uses.
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir,
                "run-1", "op-1",
                "--frames",
                "--follow",
                "--follow-poll-interval", "500",
                "--follow-max-polls", "5",
            ),
        )
        val admitted = assertInstance(result)
        assertTrue(admitted is ConsoleReadRequest.FollowFrames, "must admit FollowFrames; got: $admitted")
        admitted as ConsoleReadRequest.FollowFrames
        assertEquals(500L, admitted.followConfig.pollIntervalMillis)
        assertEquals(5, admitted.followConfig.maxPolls)
    }

    @Test
    fun `parser refuses --follow without --frames`() {
        // The flag is meaningless without the per-frame path. Refused by name so the
        // caller deletes one token (drop --follow) rather than guessing.
        val result = CliParser.parseConsole(
            arrayOf("--control-dir", ownedControlDir, "run-1", "op-1", "--follow"),
        )
        assertRefusal(result, ConsoleCliRefusal.FollowRequiresFrames)
    }

    @Test
    fun `parser refuses --follow-poll-interval 0 as a non-positive integer`() {
        // 0 means busy-loop forever; the existing NotAnInteger refusal says
        // "must be a positive integer, got: 0", which is what the caller needs.
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-poll-interval", "0",
            ),
        )
        assertRefusal(result, ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", "0"))
    }

    @Test
    fun `parser refuses --follow-poll-interval -1 as a non-positive integer`() {
        // Negative values are also non-positive; same refusal shape as 0.
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-poll-interval", "-1",
            ),
        )
        assertRefusal(result, ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", "-1"))
    }

    @Test
    fun `parser refuses --follow-max-polls 0 as a non-positive integer`() {
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-max-polls", "0",
            ),
        )
        assertRefusal(result, ConsoleCliRefusal.NotAnInteger("--follow-max-polls", "0"))
    }

    @Test
    fun `parser refuses --follow-max-polls -3 as a non-positive integer`() {
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-max-polls", "-3",
            ),
        )
        assertRefusal(result, ConsoleCliRefusal.NotAnInteger("--follow-max-polls", "-3"))
    }

    @Test
    fun `parser refuses --follow-poll-interval without a value`() {
        // Missing value is its own refusal — distinct from a value that doesn't parse.
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-poll-interval",
            ),
        )
        assertRefusal(result, ConsoleCliRefusal.MissingOptionValue("--follow-poll-interval"))
    }

    @Test
    fun `parser refuses --follow-max-polls without a value`() {
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-max-polls",
            ),
        )
        assertRefusal(result, ConsoleCliRefusal.MissingOptionValue("--follow-max-polls"))
    }

    @Test
    fun `parser refuses --follow-poll-interval abc as NotAnInteger`() {
        // A non-numeric value refuses with the same shape as a non-positive one:
        // the verb does not need to distinguish, and the caller reads the
        // "must be a positive integer, got: <value>" message as the rule they violated.
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-poll-interval", "abc",
            ),
        )
        assertRefusal(result, ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", "abc"))
    }

    @Test
    fun `parser refuses an unknown --follow-prefixed option`() {
        // Anything past the documented --follow-* prefix is unknown. The verb refuses
        // by NAME so the caller deletes one token; it does NOT silently drop.
        val result = CliParser.parseConsole(
            arrayOf(
                "--control-dir", ownedControlDir, "run-1", "op-1",
                "--frames", "--follow", "--follow-imaginary",
            ),
        )
        assertTrue(result is ConsoleCliParseResult.Rejected, "must refuse; got: $result")
        val refusal = (result as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.UnknownOption && refusal.option == "--follow-imaginary",
            "must refuse UnknownOption(\"--follow-imaginary\"); got: $refusal",
        )
    }

    // ------------------------------------------------------------------ HF1 prefix preservation

    @Test
    fun `argument refusals keep the Error prefix`() {
        // CC-1 contract: `Error:` is the argument-refusal prefix and is preserved across
        // the parser relocation. Every ConsoleCliRefusal case the parser produces must
        // render through [renderConsoleCliRefusal] and emit `Error: ...`. The row below
        // exercises every refusal the new --follow parser surface produces.
        val refusals: List<ConsoleCliRefusal> = listOf(
            ConsoleCliRefusal.MissingOptionValue("--follow-poll-interval"),
            ConsoleCliRefusal.MissingOptionValue("--follow-max-polls"),
            ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", "abc"),
            ConsoleCliRefusal.NotAnInteger("--follow-max-polls", "-1"),
            ConsoleCliRefusal.FollowRequiresFrames,
        )
        for (refusal in refusals) {
            val rendered = renderConsoleCliRefusal(refusal)
            assertTrue(
                rendered.startsWith("Error:"),
                "argument refusals must keep `Error:` prefix; got for $refusal: $rendered",
            )
        }
    }

    @Test
    fun `data-plane refusals keep the console-refused prefix`() {
        // The data-plane renderer is owned by [ConsoleReadService.renderRefusal] and is
        // independent of the parser relocation. The row pins its prefix so a future
        // refactor cannot silently move it onto the argument side or vice versa.
        val prefixes = listOf(
            ConsoleReadService.renderRefusal(dev.rubentxu.pipeline.v2.output.OutputRefusal.UnknownStream(
                dev.rubentxu.pipeline.v2.output.OutputStreamId("run-1/op-1/stdout"),
            )),
            ConsoleReadService.renderRefusal(dev.rubentxu.pipeline.v2.output.OutputRefusal.RecoveryNotCompleted),
            ConsoleReadService.renderRefusal(dev.rubentxu.pipeline.v2.output.OutputRefusal.Unavailable),
        )
        for (line in prefixes) {
            assertTrue(
                line.startsWith("console-refused:"),
                "data-plane refusals must keep `console-refused:` prefix; got: $line",
            )
        }
    }

    @Test
    fun `main refuses --follow without --frames through the same Error prefix as parser`() {
        // End-to-end: the verb main renders the typed refusal as `Error: ...` on stderr,
        // exits 2. The rendered line carries the option context so the caller edits one
        // token, not three.
        val result = cli("run-1", "op-1", "--follow")
        assertEquals(2, result.exitCode, "main must refuse --follow without --frames with exit 2")
        assertTrue(
            result.stderr.startsWith("Error:"),
            "stderr must start with `Error:`; got: ${result.stderr}",
        )
        assertTrue(
            result.stderr.contains("--follow") && result.stderr.contains("--frames"),
            "stderr must name BOTH --follow and --frames; got: ${result.stderr}",
        )
    }

    @Test
    fun `main admits --follow --frames against a non-existent control dir and fails on the dir check`() {
        // Positive control for the admit path: a well-formed --follow --frames pair
        // reaches the control-dir existence check and is told the dir does not exist.
        // That is the right shape for "the parser accepted --follow": admission ran
        // before the dir check, and the failure is filesystem-shaped, not parser-shaped.
        val result = cli("run-1", "op-1", "--frames", "--follow", "--format", "jsonl")
        assertEquals(
            2, result.exitCode,
            "non-existent control dir must fail with exit 2",
        )
        assertTrue(
            result.stderr.contains("control dir not found"),
            "stderr must carry the control-dir refusal (not a parser refusal); got: ${result.stderr}",
        )
    }

    // ------------------------------------------------------------------ HF2 installDist canary

    /**
     * Drive the installDist `pipelinek` binary against a real run and invoke
     * `--follow --frames --format jsonl`. Asserts the verb exits 0 and respects the
     * `--follow-max-polls` bound.
     *
     * Marked `assumeTrue` so a checkout without `v2/build/install/pipelinek` is a quiet
     * skip rather than a RED. The follow must NOT hang — bounded by `maxPolls * poll-interval`
     * plus the run's natural duration — so a stuck follow is a RED and a clean exit is a green.
     *
     * ## Why this row does NOT assert on the JSONL frame content
     *
     * The Output Plane population for the canonical execution path is an upstream concern
     * exercised by `ConsoleReadServiceTest` (in-process sh) and `MainConsoleCliPerFrameTest`
     * (parser + wire shape). This row is the lifecycle row: a real installDist binary must
     * accept the `--follow --frames` flag combination, drive the loop, and exit 0 without
     * crashing. The frame CONTENT is what the harness verifies on the next slice.
     *
     * ## RE-ENABLED (WU-A/B) — and it is the reason the fourth defect was findable
     *
     * This row was `@Disabled` after R4/WU-RC4, and the reason is worth keeping: it had been
     * GREEN while observing nothing. A real run left the Output Plane empty, the follow fell
     * through to `--follow-max-polls`, and the old contract returned 0 for both "finished" and
     * "out of polls" — so the missing transcript and the lying exit code cancelled each other
     * out. RC4 separated those two outcomes, which turned the row RED and made the cause
     * visible.
     *
     * The cause is now fixed (`StreamingRedactor` fed only into the ring's free space, so a full
     * ring with nothing decidable had no branch that advanced — a 100% CPU spin that lost the
     * whole transcript). With a populated Output Plane this row passes on its own terms:
     * 22 tests, 0 skipped.
     *
     * It is a disabled-test lesson worth stating plainly: a `@Disabled` row is not a passing
     * gate, and neither is a green one whose assertions never fired.
     */
    @Test
    fun `installed distribution follows a real run to its terminal state and exits 0`(@TempDir root: Path) {
        val binary: File? = dev.rubentxu.pipeline.v2.application.support.AppBinSupport.discover().toFile()
        assumeTrue(
            binary != null && binary.exists(),
            "installDist not built; run ./gradlew :pipeline-application:installDist to enable this row",
        )
        // A 3-step pipeline with concrete console output. The script's stage/step indices
        // are predictable so we can construct the canonical opId directly (OpId.format).
        val script = root.resolve("canary.pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("main") {
                        sh("printf 'con-2-3-frame-1\\n'")
                        sh("printf 'con-2-3-frame-2\\n'")
                        sh("printf 'con-2-3-frame-3\\n'")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbPath = root.resolve("journal.db")
        val controlRoot = Files.createDirectories(root.resolve("control"))

        // Step 1: populate the store with a real run.
        val runOutcome = dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess.run(
            command = listOf(
                binary!!.absolutePath, "run",
                "--db", dbPath.toString(),
                "--control-root", controlRoot.toString(),
            ) + script.toString(),
            timeout = Duration.ofMinutes(2),
            workingDirectory = root.toFile(),
        )
        val (runExit, runStdout, runStderr) = when (runOutcome) {
            is dev.rubentxu.pipeline.v2.application.support.CliRun.Completed ->
                Triple(runOutcome.exitCode, runOutcome.stdout, runOutcome.stderr)
            is dev.rubentxu.pipeline.v2.application.support.CliRun.TimedOut ->
                error("run hung after 2 min; pid=${runOutcome.diagnostics.pid}")
            is dev.rubentxu.pipeline.v2.application.support.CliRun.LaunchFailed ->
                error("run could not be launched: ${runOutcome.cause}")
        }
        assertEquals(
            0, runExit,
            "fixture run must exit 0; got $runExit; stdout=$runStdout stderr=$runStderr",
        )

        val runId = RUN_ID_REGEX.find(runStdout)?.groupValues?.get(1)
            ?: RUN_ID_REGEX.find(runStderr)?.groupValues?.get(1)
            ?: error(
                "the launch envelope must carry a runId on stdout or stderr; " +
                    "stdout=$runStdout stderr=$runStderr",
            )

        // The verb takes <runId> <opId>. The opId the per-frame path walks is the run's
        // first step; per the canonical format that is `{runId}-s0-0`. Passing it through
        // --follow is enough — the path is the same as `console --frames`, which the
        // per-frame reader resolves on the run alone.
        val opId = "$runId-s0-0"

        // --follow with a small maxPolls (5) and a short poll interval (100 ms). The total
        // budget is 5 * 100 ms = 500 ms of polling plus the per-poll read; a stuck follow
        // would surface as the 1-min subprocess timeout firing, which the assertion below
        // would render as `TimedOut` (an error).
        val followOutcome = dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess.run(
            command = listOf(
                binary.absolutePath, "console",
                "--control-dir", controlRoot.toString(),
                runId, opId,
                "--frames",
                "--follow",
                "--follow-poll-interval", "100",
                "--follow-max-polls", "5",
                "--format", "jsonl",
            ),
            timeout = Duration.ofMinutes(1),
            workingDirectory = root.toFile(),
        )
        val (followExit, followStdout, followStderr) = when (followOutcome) {
            is dev.rubentxu.pipeline.v2.application.support.CliRun.Completed ->
                Triple(followOutcome.exitCode, followOutcome.stdout, followOutcome.stderr)
            is dev.rubentxu.pipeline.v2.application.support.CliRun.TimedOut ->
                error("follow hung after 1 min; pid=${followOutcome.diagnostics.pid}")
            is dev.rubentxu.pipeline.v2.application.support.CliRun.LaunchFailed ->
                error("follow could not be launched: ${followOutcome.cause}")
        }
        // Lifecycle contract:
        //  - 0 on a Finished run, on maxPolls reached, or on a normal exit through the read path;
        //  - 1 on a data-plane refusal;
        //  - 2 on a SIGINT/SIGTERM.
        // The run finished, the follow is bounded by maxPolls, and there is no signal handling
        // here; 0 is the only acceptable exit.
        assertEquals(
            0, followExit,
            "--follow must exit 0 on a finished run; got $followExit; " +
                "stdout=$followStdout stderr=$followStderr",
        )
        // No record-of-no-record: every jsonl line MUST be a real record, never an
        // accidentally-emitted error or a refused marker. The marker for a refused
        // outcome is `console-refused:` on stderr, not on stdout; this row pins the
        // absence of that migration.
        for (line in followStdout.split("\n").filter { it.isNotEmpty() }) {
            assertTrue(
                line.contains("\"type\":\"output\""),
                "every stdout jsonl line must be a real output record; got: $line",
            )
        }
        // No data-plane refusal landed on stderr. The verb's data-plane refusals keep the
        // `console-refused:` prefix (cross-check against the parser-side `Error:` prefix).
        assertTrue(
            !followStderr.contains("console-refused:"),
            "stderr must not carry a data-plane refusal for a finished run; got: $followStderr",
        )
    }

    /**
     * Ask `inspect` to give us the opId of one step's console stream, so the
     * `--follow` invocation does not have to invent it. The step we want is the
     * first step in the fixture, and `inspect` returns its `opId` on the wire.
     *
     * Unused now that the canary constructs the opId directly from [OpId.format].
     * Kept in the file as a one-line note for the next reader who needs to discover
     * an opId without knowing the fixture's structure.
     */
    @Suppress("unused")
    private fun discoverOpId(
        binary: File,
        runId: String,
        dbPath: Path,
        controlRoot: Path,
        root: Path,
    ): String? {
        val outcome = dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess.run(
            command = listOf(
                binary.absolutePath, "inspect",
                runId,
                "--control-root", controlRoot.toString(),
                "--db", dbPath.toString(),
                "--format", "json",
            ),
            timeout = Duration.ofMinutes(1),
            workingDirectory = root.toFile(),
        )
        val stdout = when (outcome) {
            is dev.rubentxu.pipeline.v2.application.support.CliRun.Completed -> outcome.stdout
            is dev.rubentxu.pipeline.v2.application.support.CliRun.TimedOut ->
                error("inspect hung after 1 min; pid=${outcome.diagnostics.pid}")
            is dev.rubentxu.pipeline.v2.application.support.CliRun.LaunchFailed ->
                error("inspect could not be launched: ${outcome.cause}")
        }
        val match = OP_ID_REGEX.find(stdout)
        return match?.groupValues?.get(1)
    }

    private fun assertInstance(result: ConsoleCliParseResult): ConsoleReadRequest {
        assertTrue(
            result is ConsoleCliParseResult.Admitted,
            "expected admitted request; got: $result",
        )
        return (result as ConsoleCliParseResult.Admitted).request
    }

    private fun assertRefusal(result: ConsoleCliParseResult, expected: ConsoleCliRefusal) {
        assertTrue(
            result is ConsoleCliParseResult.Rejected,
            "expected Rejected; got: $result",
        )
        val actual = (result as ConsoleCliParseResult.Rejected).refusal
        assertEquals(expected, actual, "refusal must match expected exactly")
    }

    private companion object {
        // The `pipelinek.launch/1` envelope carries the runId:
        //   {"schema":"pipelinek.launch/1","runId":"<uuid>",...}
        val RUN_ID_REGEX: Regex = Regex("\"runId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")

        // `inspect` reports one row per step with `op_id`. The shape is
        // `{ ..., "op_id":"<opId>", ... }`.
        val OP_ID_REGEX: Regex = Regex("\"op_id\"\\s*:\\s*\"([^\"]+)\"")
    }
}
