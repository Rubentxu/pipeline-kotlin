package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.ConsolePlaneProbe
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * S3.2 — `environment`: certify the `EnvironmentPatch` law.
 *
 * ## Why this suite exists
 *
 * `S0SemanticWitnessMatrixTest.W-env` already witnesses the POSITIVE half: a
 * value declared in `environment { env(k, v) }` reaches the child process
 * verbatim. That is the half that is easy and the half that would pass even if
 * the implementation were ambient.
 *
 * The law the Semantic Constitution actually states is the other half:
 *
 * ```
 * parent ExecutionContext + EnvironmentPatch -> child ExecutionContext
 * ```
 *
 * and never a global environment. Java has no `System.setenv`, so "we never
 * mutate the ambient environment" is not something this codebase can state by
 * accident — it is something it must be *able* to state. These witnesses are
 * what make it sayable, and they are written so the ambient implementation
 * fails them rather than passes them.
 *
 * ## The discriminating shape
 *
 * A run with a stage that declares `environment`, followed by a stage that does
 * not. The second stage is the load-bearing one:
 *
 * - a PATCH implementation prints the value in stage 1 and nothing in stage 2;
 * - an AMBIENT implementation (whatever mutates the engine's own environment, or
 *   leaks a projection into a shared options bag) prints the value in BOTH.
 *
 * There is no configuration of "correct" that makes the ambient answer right.
 *
 * ## Scope respected
 *
 * This suite certifies the propagation and isolation of `environment`. It does
 * NOT re-litigate credential materialisation: `EnvironmentSpec` carries
 * plaintext by design, and secret-bearing environment belongs to
 * `withCredentials` / `CredentialScope`, which is RP7-ASX territory, not S3.2.
 */
@Timeout(300)
class S3EnvironmentSemanticWitnessTest {

    private val appBin: Path by lazy { AppBinSupport.discover() }

    private fun run(script: String): Triple<Int, List<DomainEvent>, Path> {
        val dir = Files.createTempDirectory("s3env")
        // S4/M1: the control dir is named on the command line instead of being inferred from the
        // invocation CWD. These witnesses assert on process output, and process output is the
        // Output Plane's authority; a reader that had to guess where the plane landed would be
        // asserting on a default rather than on the run.
        val controlDir = dir.resolve("control")
        val scriptPath = dir.resolve("witness.pipeline.kts")
        Files.writeString(scriptPath, script.trimIndent())
        val stdoutFile = dir.resolve("events.json")
        val process = ProcessBuilder(
            appBin.toString(),
            "run", "--format", "json",
            // ORDER IS LOAD-BEARING: CliParser stops consuming options at the first argument
            // that is not a flag, so anything after the script path is ignored in silence. A
            // `--control-root` written after the script reads as if the plane were redirected
            // when it was not, and every read then fails for a reason that has nothing to do
            // with the pipeline under test.
            "--control-root",
            controlDir.toAbsolutePath().toString(),
            scriptPath.toAbsolutePath().toString(),
        )
            .directory(dir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val exitCode = process.waitFor()
        val stdout = Files.readString(stdoutFile).trim()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(stdout.startsWith("[") && stdout.endsWith("]"), "event log must be a JSON array: $stdout$stderr")
        return Triple(exitCode, JsonEventLog.decode(stdout), controlDir)
    }

    private fun outcomeOf(events: List<DomainEvent>): String = (events.last() as RunFinished).outcome

    /**
     * Every process transcript this run produced, read from the Output Plane.
     *
     * These witnesses used to read `EchoOutputCaptured.content` and went RED with an empty
     * string after M1 moved the bytes out of events — the pipeline was fine, the reader was
     * pointed at a channel that no longer carries output. The claims are unchanged; the
     * authority is the one that owns the bytes.
     */
    private fun processOutput(events: List<DomainEvent>, controlDir: Path): String =
        ConsolePlaneProbe.transcriptsOfSteps(controlDir, events, stepType = "sh")

    // ------------------------------------------------------------------
    // The load-bearing witness: a patch, not ambient state
    // ------------------------------------------------------------------

    @Test
    fun `a stage environment does not leak into a later stage or the engine's own environment`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("declared") {
                        environment { env("S3_ENV_SCOPE", "only-here") }
                        sh("echo declared=\$(printenv S3_ENV_SCOPE)")
                    }
                    stage("undeclared") {
                        sh("echo undeclared=[\$(printenv S3_ENV_SCOPE)]")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "both stages must succeed: ${processOutput(events, controlDir)}")
        assertEquals("success", outcomeOf(events))

        val out = processOutput(events, controlDir)
        assertTrue(
            out.contains("declared=only-here"),
            "the declared stage must SEE its own environment value: $out",
        )
        assertTrue(
            out.contains("undeclared=[]"),
            "a stage that declared no environment must NOT see the previous stage's value. " +
                "A non-empty bracket here means the environment leaked ambiently - the engine " +
                "process itself now carries the value, which is the defect this witness exists " +
                "to catch: $out",
        )
    }

    // ------------------------------------------------------------------
    // Precedence: the last declaration inside the block wins
    // ------------------------------------------------------------------

    @Test
    fun `a later declaration of the same key inside the block wins`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("precedence") {
                        environment {
                            env("S3_ENV_PRECEDENCE", "first")
                            env("S3_ENV_PRECEDENCE", "second")
                        }
                        sh("echo precedence=\$(printenv S3_ENV_PRECEDENCE)")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "re-declaring a key is a legal override, not a failure: ${processOutput(events, controlDir)}")
        assertEquals("success", outcomeOf(events))
        assertTrue(
            processOutput(events, controlDir).contains("precedence=second"),
            "the LAST declaration must win (Map semantics), not the first and not an error: " +
                processOutput(events, controlDir),
        )
    }

    // ------------------------------------------------------------------
    // Isolation between sibling stages in the same run
    // ------------------------------------------------------------------

    @Test
    fun `sibling stages each see only their own environment`() {
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("a") {
                        environment { env("S3_ENV_SIBLING", "from-a") }
                        sh("echo siblingA=\$(printenv S3_ENV_SIBLING)")
                    }
                    stage("b") {
                        environment { env("S3_ENV_SIBLING", "from-b") }
                        sh("echo siblingB=\$(printenv S3_ENV_SIBLING)")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, processOutput(events, controlDir))
        assertEquals("success", outcomeOf(events))
        val out = processOutput(events, controlDir)
        assertTrue(out.contains("siblingA=from-a"), "stage a must see its own value: $out")
        assertTrue(
            out.contains("siblingB=from-b"),
            "stage b must see ITS OWN value, not stage a's: $out",
        )
    }

    // ------------------------------------------------------------------
    // The environment feeds the `when` gate as well as the process
    // ------------------------------------------------------------------

    @Test
    fun `a stage environment is visible to a gate declared in the same stage`() {
        // `whenEnvIs` reads the stage's declared environment (CompositionRoot binds
        // gateContext from stageEnvironment), so this is a second, independent
        // consumer of the same patch. If only the shell received the value, this
        // gate would evaluate against the engine's environment and the stage would
        // be SKIPPED — the discriminating failure for a "propagated to sh only"
        // implementation.
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("gated") {
                        environment { env("S3_ENV_GATE", "open") }
                        whenEnvIs("S3_ENV_GATE", "open")
                        sh("echo gate=open")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, processOutput(events, controlDir))
        assertEquals("success", outcomeOf(events))
        assertTrue(
            processOutput(events, controlDir).contains("gate=open"),
            "the gate must be satisfied by the stage's own environment, or the stage is skipped: " +
                processOutput(events, controlDir),
        )
    }

    @Test
    fun `a gate on an absent variable skips the stage instead of running it`() {
        // The negative leg of the same consumer: a gate that does not match must
        // SKIP, proving the value reached the gate rather than the gate always
        // passing.
        val (exit, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("gated-off") {
                        environment { env("S3_ENV_GATE_OFF", "closed") }
                        whenEnvIs("S3_ENV_GATE_OFF", "open")
                        sh("echo SHOULD_NOT_RUN")
                    }
                }
            }
            """,
        )
        assertEquals(0, exit, "a skip is a stage outcome, not a run failure: ${processOutput(events, controlDir)}")
        assertEquals("success", outcomeOf(events))
        // A skipped stage produced no `StepStarted`, so the Output Plane has no stream for it
        // and `processOutput` is empty. That emptiness is CORROBORATION, not the proof: on its
        // own it is equally consistent with a reader aimed at the wrong run, which is why the
        // load-bearing assertion is the `StageSkipped` event below. The two together say the
        // gate did its job — the body was never entered, and nothing about the run was lost.
        assertTrue(
            !processOutput(events, controlDir).contains("SHOULD_NOT_RUN"),
            "the gate must actually gate: a body that ran proves the predicate was ignored: " +
                processOutput(events, controlDir),
        )
        assertTrue(
            events.any { it is dev.rubentxu.pipeline.v2.events.StageSkipped },
            "the skip must be observable as an event, not inferred from a missing body: $events",
        )
        assertTrue(
            events.none { it is dev.rubentxu.pipeline.v2.events.StepStarted },
            "a skipped stage must not have started any step; a StepStarted here would mean the " +
                "body ran regardless of the gate: $events",
        )
    }

    // ------------------------------------------------------------------
    // The declared value is not leaked into the observable timeline
    // ------------------------------------------------------------------

    @Test
    fun `a declared environment value does not appear in the event timeline`() {
        // Recorded as a measured property, not an aspiration. `EnvironmentSpec`
        // carries plaintext by design and the child process must see it, so the
        // only honest question is whether the run's own observability surfaces
        // echo it. If a future change starts emitting it, THIS test is what turns
        // green into a finding instead of a silent new exposure.
        val secretish = "s3-env-value-do-not-echo-918273645"
        val (_, events, controlDir) = run(
            """
            pipeline {
                stages {
                    stage("leakcheck") {
                        environment { env("S3_ENV_LEAK", "$secretish") }
                        sh("true")
                    }
                }
            }
            """,
        )
        val timeline = events.joinToString("\n") { it.toString() }
        assertTrue(
            !timeline.contains(secretish),
            "the declared environment value leaked into the observable event timeline. This is " +
                "recorded as forbidden so a future emission is a failing test rather than a " +
                "silent new exposure: $timeline",
        )
    }

    // ------------------------------------------------------------------
    // S3.3: options are validated at the construction boundary
    // ------------------------------------------------------------------

    @Test
    fun `a non-positive options timeout is refused when the pipeline is declared, not at run time`() {
        // S3.3: `StageOption.Timeout` rejects a non-positive duration in its own
        // invariant, and `OptionsScope.timeout` rejects it before that. Both are
        // checked here through the REAL installed distribution, so what is proven
        // is that an author writing `timeout(0)` or `timeout(-5)` cannot reach a
        // run — the old shape carried the raw Long through the compiler and only
        // discovered the problem inside the interpreter.
        for (bad in listOf(0L, -5L)) {
            val dir = Files.createTempDirectory("s3opt")
            val scriptPath = dir.resolve("bad.pipeline.kts")
            Files.writeString(
                scriptPath,
                """
                pipeline {
                    stages {
                        stage("bad") {
                            options { timeout($bad) }
                            sh("true")
                        }
                    }
                }
                """.trimIndent(),
            )
            val out = dir.resolve("out.txt")
            val process = ProcessBuilder(appBin.toString(), "run", "--format", "json", scriptPath.toAbsolutePath().toString())
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.to(out.toFile()))
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            val exit = process.waitFor()
            val stdout = Files.readString(out).trim()
            val stderr = process.errorStream.bufferedReader().readText()

            assertTrue(
                exit != 0,
                "options { timeout($bad) } must be REFUSED, but the run exited 0. A zero or " +
                    "negative deadline is not a deadline: it either never fires or fires before the " +
                    "stage starts, and both look like a working timeout to the author.",
            )
            assertTrue(
                stdout.isEmpty() || !stdout.contains("StepStarted"),
                "a refused pipeline must not have started a step. stdout=$stdout stderr=$stderr",
            )
        }
    }
}
