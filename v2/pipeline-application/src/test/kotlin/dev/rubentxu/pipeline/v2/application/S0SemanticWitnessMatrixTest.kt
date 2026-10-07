package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.ConsolePlaneProbe
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * S0-B: semantic witness matrix for every STABLE DSL surface (Semantic Honesty Gate).
 *
 * Law: compiles and exit-0 do NOT prove semantics. Each STABLE surface needs a witness
 * with ALL seven legs:
 *   CARRIER   - a minimal pipeline using ONLY the surface under test (plus echo/sh).
 *   POSITIVE  - the success shape executes and completes green.
 *   NEGATIVE  - the declared failure/narrowing path executes and is observable.
 *   OUTCOME   - RunFinished carries the expected outcome.
 *   EVENT     - the surface's AUTHORITY event kind appears with the expected payload.
 *   REPLAY    - the JSON event log re-decodes into the identical timeline.
 *   INSTALLED - the run goes through the installed distribution binary (CLI), not an
 *               in-process harness.
 *
 * One witness per surface; shared helper runs the CLI and decodes the timeline.
 * Fail-closed stubs and UNSUPPORTED_FAIL_CLOSED rows are witnessed by their dedicated
 * suites (RetryConditionsFailClosedTest, WhenConditionFailClosedTest, StepSpecRetryCapabilityTest,
 * CliNonCanonicalInMemoryExitsTwoTest, publishHTML keepAll adapter suite) and are NOT
 * duplicated here.
 */
@Timeout(300)
class S0SemanticWitnessMatrixTest {

    private val appBin: Path by lazy { AppBinSupport.discover() }

    private fun run(script: String): Triple<Int, List<DomainEvent>, Path> {
        val dir = Files.createTempDirectory("s0witness")
        // S4/M1: name the control dir instead of letting it follow the invocation CWD. The
        // witness that reads process output reads it from the Output Plane, and a reader that
        // had to guess where the plane landed would be asserting on a default, not on the run.
        val controlDir = dir.resolve("control")
        val scriptPath = dir.resolve("witness.pipeline.kts")
        Files.writeString(scriptPath, script.trimIndent())
        val stdoutFile = dir.resolve("events.json")
        // RP034-Ic: the child MUST NOT inherit the JVM working directory. Under the
        // local-first default the workspace IS the invocation directory, so an
        // inherited CWD pointed `sh` at the module source root and the W-stash
        // witness deposited `stashme.txt` into `v2/pipeline-application/`. The
        // scratch directory is the workspace for the whole run instead.
        // WAITFOR-3: stdout went to a file but stderr was still a PIPE read after the wait -- the
        // same deadlock one stream away. The harness drains it while the child runs.
        val cliRun = Subprocess.run(
            command = listOf(
                appBin.toString(),
                "run", "--format", "json",
                // Options must precede the script path: CliParser stops consuming flags at the first
                // non-flag argument, so a trailing `--control-root` would be dropped in silence and
                // the Output Plane would be read from the default location instead.
                "--control-root",
                controlDir.toAbsolutePath().toString(),
                scriptPath.toAbsolutePath().toString(),
            ),
            workingDirectory = dir,
            stdoutFile = stdoutFile,
        ).requireExited()
        val exitCode = cliRun.exitCode
        val stdout = cliRun.stdout.trim()
        val stderr = cliRun.stderr
        assertTrue(stdout.startsWith("[") && stdout.endsWith("]"), "event log must be a JSON array: $stdout$stderr")
        return Triple(exitCode, JsonEventLog.decode(stdout), controlDir)
    }

    /**
     * Process output for the run's `sh` steps, read from the Output Plane.
     *
     * Distinct from [echoContents] on purpose. `core.echo` is a semantic event and belongs in
     * the event log; `sh` is a process and its output belongs to the Output Plane (ADR-M1 D3).
     * Reading both through one accessor is how the two channels got conflated in the first
     * place.
     */
    private fun processOutput(events: List<DomainEvent>, controlDir: Path): String =
        ConsolePlaneProbe.transcriptsOfSteps(controlDir, events, stepType = "sh")

    /** The `core.echo` semantic events, joined. This one legitimately lives in the event log. */
    private fun echoContents(events: List<DomainEvent>): String =
        events.filterIsInstance<EchoOutputCaptured>().joinToString("\n") { it.content }

    private fun outcomeOf(events: List<DomainEvent>): String =
        (events.last() as RunFinished).outcome

    /** Runs the CLI and returns the raw event-log stdout. */
    private fun rawRun(scriptPath: Path): String {
        val stdoutFile = Files.createTempFile("s0replay", ".json")
        // RP034-Ic: same isolation as `run` — the script's own directory is the
        // workspace, never the inherited JVM CWD.
        // WAITFOR-3: as above -- stderr was a pipe read after an unbounded wait.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", scriptPath.toAbsolutePath().toString()),
            workingDirectory = scriptPath.parent,
            stdoutFile = stdoutFile,
        ).requireExited()
        assertEquals(0, cliRun.exitCode)
        return cliRun.stdout.trim()
    }

    /** Removes run-unique fields so two timelines compare structurally. */
    private fun stripRunIdentity(raw: String): String = raw
        .replace(Regex("\"runId\":\"[^\"]+\""), "\"runId\":\"<id>\"")
        .replace(Regex("\"eventId\":\"[^\"]+\""), "\"eventId\":\"<id>\"")
        .replace(Regex("\"occurredAt\":\"[^\"]+\""), "\"occurredAt\":\"<ts>\"")
        .replace(Regex("\"scriptPath\":\"[^\"]+\""), "\"scriptPath\":\"<path>\"")

    // ------------------------------------------------------------------
    // W-script: sh + echo (the atomic spine)
    // ------------------------------------------------------------------

    @Test
    fun `W-script sh echo - positive, authority events, replay`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("spine") {
                        echo("s0-spine-echo")
                        sh("echo s0-spine-sh")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit)
        assertEquals("success", outcomeOf(events))
        // Two authorities, two assertions, no overlap. `core.echo` is a semantic Step and
        // publishes its text as an event; `sh` launches a process whose output is the Output
        // Plane's. Reading the `sh` half out of EchoOutputCaptured is what this migration
        // removed — it went RED with an empty observation while the pipeline was perfectly
        // healthy, which is the worst way for a witness to be wrong.
        val echoes = events.filterIsInstance<EchoOutputCaptured>()
        assertTrue(echoes.any { it.content.contains("s0-spine-echo") }, "echo authority event missing: $events")
        assertFalse(
            echoes.any { it.content.contains("s0-spine-sh") },
            "process output must NOT be smuggled through the event plane; if this is set, a " +
                "second authority for stdout has been reintroduced: $events",
        )
        val shOut = processOutput(events, controlDir)
        assertTrue(shOut.contains("s0-spine-sh"), "sh stdout missing from the Output Plane: $events")
        assertFalse(
            shOut.contains("s0-spine-echo"),
            "the echo text must not be reconstructed into the process transcript either: $shOut",
        )
        // REPLAY: the persisted log is the authority; re-encoding the decoded timeline is
        // NOT lossless (escape normalisation), so replay is judged against the raw stdout
        // re-decode instead.
        val dir = Files.createTempDirectory("s0replay")
        val scriptPath = dir.resolve("replay.pipeline.kts")
        Files.writeString(
            scriptPath,
            """
            pipeline {
                stages {
                    stage("replay") {
                        echo("s0-spine-echo")
                    }
                }
            }
            """ .trimIndent(),
        )
        val log1 = rawRun(scriptPath)
        val log2 = rawRun(scriptPath)
        val norm1 = stripRunIdentity(log1)
        val norm2 = stripRunIdentity(log2)
        assertEquals(norm1, norm2, "two runs of the same source must replay structurally identical timelines")
    }

    @Test
    fun `W-script negative - failing sh fails the run with observable outcome`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("negative") {
                        sh("exit 3")
                    }
                }
            }
            """,
        )
        assertEquals(1, exit, "a failing step must fail the CLI run")
        assertEquals("failure", outcomeOf(events))
        assertTrue(events.any { it is StepFailed }, "StepFailed authority event missing: $events")
    }

    // ------------------------------------------------------------------
    // W-env: stage environment reaches the shell process
    // ------------------------------------------------------------------

    @Test
    fun `W-env stage environment propagates into the shell process`() {
        val marker = Files.createTempFile("s0env", ".txt").toAbsolutePath().toString()
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("envwitness") {
                        environment { env("S0_WITNESS_ENV", "carried-12345") }
                        sh("printenv S0_WITNESS_ENV > $marker")
                        sh("test \"\$(cat $marker)\" = \"carried-12345\"")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "env must reach the shell process verbatim")
        assertEquals("success", outcomeOf(events))
        assertTrue(Files.readString(Path.of(marker)).trim() == "carried-12345")
        assertEquals("success", (events.last() as RunFinished).outcome)
    }

    // ------------------------------------------------------------------
    // W-timeout: options.timeout schedules the timeout projection
    // ------------------------------------------------------------------

    @Test
    fun `W-timeout block schedules and triggers the timeout authority events`() {
        // The BLOCK timeout() owns the TimeoutScheduled/TimeoutTriggered authority events.
        // The options { timeout(n) } directive projects to the shell deadline instead
        // (StepFailed with failureKind=TIMEOUT) - witnessed separately below.
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("slow") {
                        timeout(1, "SECONDS") {
                            sh("sleep 5")
                            echo("never-reached")
                        }
                    }
                }
            }
            """,
        )
        assertEquals(1, exit, "a timed-out body must fail the run")
        assertEquals("failure", outcomeOf(events))
        assertTrue(events.any { it is TimeoutScheduled }, "TimeoutScheduled authority event missing: $events")
        assertTrue(events.any { it is TimeoutTriggered }, "TimeoutTriggered authority event missing: $events")
        assertFalse(echoContents(events).contains("never-reached"),
            "steps after the timeout must not run")
    }

    @Test
    fun `W-options timeout directive projects a shell deadline StepFailed-TIMEOUT`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("slow") {
                        options { timeout(1) }
                        sh("sleep 5")
                    }
                }
            }
            """,
        )
        assertEquals(1, exit, "the stage deadline must fail the run")
        assertEquals("failure", outcomeOf(events))
        val failed = events.filterIsInstance<StepFailed>().single()
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT,
            failed.failureKind,
            "options.timeout must project to the shell deadline",
        )
    }

    // ------------------------------------------------------------------
    // W-retry: retry(count) re-executes the body, only maxAttempts honoured
    // ------------------------------------------------------------------

    @Test
    fun `W-retry fail-then-succeed reexecutes body exactly maxAttempts times`() {
        val marker = Files.createTempFile("s0retry", ".marker").toAbsolutePath().toString()
        Files.deleteIfExists(Path.of(marker))
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("retrywitness") {
                        retry(3) {
                            sh("test -f $marker && exit 0 || { touch $marker; exit 1; }")
                        }
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "fail-then-succeed must end green within maxAttempts")
        assertEquals("success", outcomeOf(events))
        val started = events.filterIsInstance<RetryAttemptStarted>()
        val finished = events.filterIsInstance<RetryAttemptFinished>()
        assertEquals(2, started.size, "exactly 2 attempts (1 fail + 1 success): $started")
        assertEquals(2, finished.size)
        assertEquals("failed", finished.first { it.attemptNumber == 1 }.outcome)
        assertEquals("succeeded", finished.first { it.attemptNumber == 2 }.outcome)
    }

    @Test
    fun `W-retry negative - all attempts exhausted fails the run`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("alwaysfails") {
                        retry(2) {
                            sh("exit 7")
                        }
                    }
                }
            }
            """,
        )
        assertEquals(1, exit)
        assertEquals("failure", outcomeOf(events))
        val started = events.filterIsInstance<RetryAttemptStarted>()
        assertEquals(2, started.size, "exactly maxAttempts attempts: $started")
        assertTrue(events.any { it is StepFailed }, "exhausted retries must surface a StepFailed: $events")
    }

    // ------------------------------------------------------------------
    // W-dir / W-withEnv: scoped blocks project their authority events
    // ------------------------------------------------------------------

    @Test
    fun `W-dir dir block enters and exits the directory`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("dirwitness") {
                        dir("subdir-s0") {
                            sh("test -d . && echo in-subdir")
                        }
                    }
                }
            }
            """,
        )
        assertEquals(0, exit)
        assertEquals("success", outcomeOf(events))
        assertTrue(events.any { it is DirEntered && it.path.contains("subdir-s0") },
            "DirEntered authority event missing: $events")
        assertTrue(events.any { it is DirExited }, "DirExited authority event missing: $events")
    }

    @Test
    fun `W-withEnv withEnv block carries scoped env into children`() {
        val marker = Files.createTempFile("s0withenv", ".txt").toAbsolutePath().toString()
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("withenvwitness") {
                        withEnv(mapOf("S0_SCOPED" to "scoped-99")) {
                            sh("test \"\${'\$'}S0_SCOPED\" = \"scoped-99\"")
                        }
                        echo("done")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "scoped env must reach the child shell: $events")
        assertEquals("success", outcomeOf(events))
        assertTrue(echoContents(events).contains("done"))
    }

    // ------------------------------------------------------------------
    // W-catchError / W-warnError: workflow-control rewrites
    // ------------------------------------------------------------------

    @Test
    fun `W-catchError contained failure is caught and run stays green`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("catchwitness") {
                        catchError {
                            sh("exit 5")
                        }
                        echo("after-catch")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "a contained failure must not fail the build")
        assertEquals("unstable", outcomeOf(events), "catchError default projection is UNSTABLE (ADR-0054)")
        assertTrue(events.any { it is CatchErrorTriggered }, "CatchErrorTriggered authority event missing: $events")
        assertTrue(echoContents(events).contains("after-catch"),
            "steps after catchError must run")
    }

    @Test
    fun `W-warnError marks the stage unstable instead of failing`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("warnwitness") {
                        warnError("s0-unstable") {
                            sh("exit 4")
                        }
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "warnError must not fail the run (unstable projection)")
        assertEquals("unstable", outcomeOf(events))
        assertTrue(events.any { it is StageMarkedUnstable }, "StageMarkedUnstable authority event missing: $events")
    }

    // ------------------------------------------------------------------
    // W-git: exactly one checkout per git() call
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // W-git: git() lowers to core.checkout, REJECTED by the canonical bridge.
    //
    // S0-B FINDING: the DSL checkout/git builders lower to StepSpec.Checkout, whose
    // OpaqueStepNode carries pluginStepId core.checkout. core.checkout is NOT in the
    // canonical step IDs and the scm-git plugin registers scm-git.checkout - a DIFFERENT
    // key reached only through registryStep()/scmGit DSL of the plugin. So the familiar
    // Jenkins `git(url)` call never reaches an interpreter on the installed canonical
    // path: it fails closed with exit 2. This is the scmGit-duplicate shape the honesty
    // gate exists to catch. WITNESS: the rejection, not a clone.
    // ------------------------------------------------------------------

    @Test
    fun `W-git git call is rejected fail-closed by the canonical bridge - SURFACE FINDING`() {
        val dir = Files.createTempDirectory("s0git")
        val scriptPath = dir.resolve("git.pipeline.kts")
        Files.writeString(
            scriptPath,
            """
            pipeline {
                stages {
                    stage("gitwitness") {
                        git("https://example.invalid/s0-no-clone.git", branch = "main")
                    }
                }
            }
            """.trimIndent(),
        )
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", scriptPath.toAbsolutePath().toString()),
        ).requireExited()
        val exit = cliRun.exitCode
        val stderr = cliRun.stderr
        assertEquals(2, exit, "git() must be rejected fail-closed by the canonical bridge")
        assertTrue(stderr.contains("non-canonical plugins"), "rejection must name the gate: $stderr")
        assertTrue(stderr.contains("core.checkout"), "rejection must name the offending key: $stderr")
    }

    // ------------------------------------------------------------------
    // W-milestone / W-stash-unstash: registry-backed atomic steps
    // ------------------------------------------------------------------

    @Test
    fun `W-milestone emits MilestoneReached with the ordinal`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("milestonewitness") {
                        milestone(1, "s0-first")
                        echo("after-milestone")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit)
        assertEquals("success", outcomeOf(events))
        val reached = events.filterIsInstance<MilestoneReached>()
        assertEquals(1, reached.size)
        assertEquals(1, reached.single().ordinal)
    }

    @Test
    fun `W-stash-unstash stash then unstash round-trips through the authority events`() {
        val dir = Files.createTempDirectory("s0stash")
        val payload = dir.resolve("payload.txt")
        Files.writeString(payload, "s0-stash-payload")
        val scriptDir = dir.toAbsolutePath().toString()
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("produce") {
                        sh("cp $scriptDir/payload.txt stashme.txt")
                        stash("s0bundle", includes = "stashme.txt")
                    }
                    stage("consume") {
                        unstash("s0bundle")
                        sh("test -f stashme.txt && grep -q s0-stash-payload stashme.txt")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "stash/unstash round-trip must succeed: $events")
        assertEquals("success", outcomeOf(events))
        assertTrue(events.any { it is StashCreated }, "StashCreated authority event missing: $events")
        assertTrue(events.any { it is StashRestored }, "StashRestored authority event missing: $events")
    }

    // ------------------------------------------------------------------
    // W-parallel: branches execute with their authority events
    // ------------------------------------------------------------------

    @Test
    fun `W-parallel branches run and project branch authority events`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("parallelwitness") {
                        parallel {
                            branch("left") {
                                echo("left-ran")
                            }
                            branch("right") {
                                echo("right-ran")
                            }
                        }
                    }
                }
            }
            """,
        )
        assertEquals(0, exit)
        assertEquals("success", outcomeOf(events))
        val started = events.filterIsInstance<ParallelBranchStarted>()
        assertEquals(2, started.size, "both branches must start: $events")
        assertEquals(setOf("left", "right"), started.map { it.branchName }.toSet())
        assertTrue(events.filterIsInstance<ParallelBranchFinished>().size == 2)
    }

    // ------------------------------------------------------------------
    // W-waitUntil / W-timestamps: scoped poll and decorator blocks
    // ------------------------------------------------------------------

    @Test
    fun `W-waitUntil predicate polls until it succeeds and projects poll events`() {
        val marker = Files.createTempFile("s0wait", ".marker").toAbsolutePath().toString()
        Files.deleteIfExists(Path.of(marker))
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("waitwitness") {
                        waitUntil {
                            sh("test -f $marker && exit 0 || { touch $marker; exit 1; }")
                        }
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "waitUntil must succeed once the predicate holds: $events")
        assertEquals("success", outcomeOf(events))
        assertTrue(events.any { it is WaitUntilPolled }, "WaitUntilPolled authority event missing: $events")
        assertTrue(events.any { it is WaitUntilCompleted }, "WaitUntilCompleted authority event missing: $events")
    }

    @Test
    fun `W-timestamps decorator block enters and exits`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("tswitness") {
                        timestamps {
                            echo("decorated")
                        }
                    }
                }
            }
            """,
        )
        assertEquals(0, exit)
        assertEquals("success", outcomeOf(events))
        assertTrue(events.any { it is TimestampsEntered }, "TimestampsEntered authority event missing: $events")
        assertTrue(events.any { it is TimestampsExited }, "TimestampsExited authority event missing: $events")
    }
}
