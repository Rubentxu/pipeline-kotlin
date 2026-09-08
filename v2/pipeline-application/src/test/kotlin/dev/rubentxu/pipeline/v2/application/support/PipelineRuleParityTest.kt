package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * SPIKE-017 parity proof (spec R3, R4; scenario S1).
 *
 * ERR-S-001 (`catchError { sh("exit 1"); echo("after-failure") }; echo("after-catch")`) is today a
 * real subprocess UAT with up to a 300s timeout. Here the equivalent declarative PipelineSpec (built
 * via the same in-process `pipeline { }` DSL the compiler consumes) runs through [PipelineRule] and
 * reproduces the coordinator semantics in-process. This proves the fast, deterministic harness and
 * surfaces the G3 step-name divergence (`... echo-0` vs expected `echo`) without spawning a process.
 */
@Timeout(60)
class PipelineRuleParityTest {

    @Test
    fun `ERR-S-001 catchError semantics reproduce in-process fast`(@TempDir workDir: Path) {
        val source = """
            pipeline {
                stages {
                    stage("test") {
                        catchError(message = "tolerated") {
                            sh("exit 1")
                            echo("after-failure")
                        }
                        echo("after-catch")
                    }
                }
            }
        """.trimIndent()
        val spec = pipeline {
            stages {
                stage("test") {
                    catchError(message = "tolerated") {
                        sh("exit 1")
                        echo("after-failure")
                    }
                    echo("after-catch")
                }
            }
        }
        val result = PipelineRule.run(spec, "err-s-001.pipeline.kts", source, "wc-inprocess-err-s-001", workDir)

        // catchError suppresses the failure (default UNSTABLE): CatchErrorTriggered must be present.
        val catch = result.events.filterIsInstance<CatchErrorTriggered>()
        assertTrue(catch.isNotEmpty(), "CatchErrorTriggered must be emitted in-process")
        assertTrue(catch.first().message == "tolerated", "catchError message must survive")
        assertTrue(result.outcome !is dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure,
            "catchError must suppress failure; outcome=${result.outcome}")

        // Record the observed step names to expose the G3 divergence (echo-0 vs expected echo).
        val stepNames = result.events.filterIsInstance<StepFinished>().map { it.stepName }
        println("PIPELINE-RULE in-process elapsed=${result.elapsedMs}ms outcome=${result.outcome}")
        println("PIPELINE-RULE StepFinished names=$stepNames")

        // R4: deterministic + fast (<5s after warm-up; coordinator run excludes DSL builder compile).
        assertTrue(result.elapsedMs < 5_000L, "in-process run must be <5s, was ${result.elapsedMs}ms")

        // G3 divergence is logged above as evidence (test/<type>-0 vs DSL contract 'echo'); it is
        // tracked in UAT_GATE_GAPS_DIAGNOSIS, not asserted green here.
    }

    @Test
    fun `withCredentials with no store fails closed and never dispatches body`(@TempDir workDir: Path) {
        // R2 / S3: no store -> the coordinator's CredentialScopePort stub returns Unavailable, so the
        // run must fail closed and the withCredentials body must NOT dispatch.
        val spec = pipeline {
            stages {
                stage("test") {
                    environment(credentialsId = "no-store-key", variable = "SECRET") {
                        echo("in-scope-must-not-run")
                    }
                }
            }
        }
        val result = PipelineRule.run(
            spec, "fail-closed.pipeline.kts", "withCredentials no store",
            "wc-inprocess-failclosed", workDir,
        )

        assertTrue(result.outcome is RunOutcome.Failure, "no store must fail closed, got ${result.outcome}")
        assertTrue(
            result.events.filterIsInstance<StepStarted>().none { it.stepName.contains("echo") },
            "withCredentials body must not be dispatched when the store is unavailable",
        )
    }
}
