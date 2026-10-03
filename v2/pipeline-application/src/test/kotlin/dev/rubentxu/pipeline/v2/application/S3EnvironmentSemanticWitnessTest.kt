package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
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

    private fun run(script: String): Pair<Int, List<DomainEvent>> {
        val dir = Files.createTempDirectory("s3env")
        val scriptPath = dir.resolve("witness.pipeline.kts")
        Files.writeString(scriptPath, script.trimIndent())
        val stdoutFile = dir.resolve("events.json")
        val process = ProcessBuilder(appBin.toString(), "run", scriptPath.toAbsolutePath().toString())
            .directory(dir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val exitCode = process.waitFor()
        val stdout = Files.readString(stdoutFile).trim()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(stdout.startsWith("[") && stdout.endsWith("]"), "event log must be a JSON array: $stdout$stderr")
        return exitCode to JsonEventLog.decode(stdout)
    }

    private fun outcomeOf(events: List<DomainEvent>): String = (events.last() as RunFinished).outcome

    /** Every step stdout the run captured, joined. */
    private fun capturedStdout(events: List<DomainEvent>): String =
        events.filterIsInstance<dev.rubentxu.pipeline.v2.events.EchoOutputCaptured>()
            .joinToString("\n") { it.content }

    // ------------------------------------------------------------------
    // The load-bearing witness: a patch, not ambient state
    // ------------------------------------------------------------------

    @Test
    fun `a stage environment does not leak into a later stage or the engine's own environment`() {
        val (exit, events) = run(
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
        assertEquals(0, exit, "both stages must succeed: ${capturedStdout(events)}")
        assertEquals("success", outcomeOf(events))

        val out = capturedStdout(events)
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
        val (exit, events) = run(
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
        assertEquals(0, exit, "re-declaring a key is a legal override, not a failure: ${capturedStdout(events)}")
        assertEquals("success", outcomeOf(events))
        assertTrue(
            capturedStdout(events).contains("precedence=second"),
            "the LAST declaration must win (Map semantics), not the first and not an error: " +
                capturedStdout(events),
        )
    }

    // ------------------------------------------------------------------
    // Isolation between sibling stages in the same run
    // ------------------------------------------------------------------

    @Test
    fun `sibling stages each see only their own environment`() {
        val (exit, events) = run(
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
        assertEquals(0, exit, capturedStdout(events))
        assertEquals("success", outcomeOf(events))
        val out = capturedStdout(events)
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
        val (exit, events) = run(
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
        assertEquals(0, exit, capturedStdout(events))
        assertEquals("success", outcomeOf(events))
        assertTrue(
            capturedStdout(events).contains("gate=open"),
            "the gate must be satisfied by the stage's own environment, or the stage is skipped: " +
                capturedStdout(events),
        )
    }

    @Test
    fun `a gate on an absent variable skips the stage instead of running it`() {
        // The negative leg of the same consumer: a gate that does not match must
        // SKIP, proving the value reached the gate rather than the gate always
        // passing.
        val (exit, events) = run(
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
        assertEquals(0, exit, "a skip is a stage outcome, not a run failure: ${capturedStdout(events)}")
        assertEquals("success", outcomeOf(events))
        assertTrue(
            !capturedStdout(events).contains("SHOULD_NOT_RUN"),
            "the gate must actually gate: a body that ran proves the predicate was ignored: " +
                capturedStdout(events),
        )
        assertTrue(
            events.any { it is dev.rubentxu.pipeline.v2.events.StageSkipped },
            "the skip must be observable as an event, not inferred from a missing body: $events",
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
        val (_, events) = run(
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
}
