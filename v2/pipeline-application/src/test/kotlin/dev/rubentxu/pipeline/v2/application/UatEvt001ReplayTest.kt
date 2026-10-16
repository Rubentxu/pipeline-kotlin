package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.support.CliContractTestKit
import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Disabled
import java.nio.file.Path
import java.nio.file.Paths

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * UAT-EVT-001: CLI invocation produces a JSON event log that can be re-parsed
 * and yields the same timeline across two invocations.
 */
@Disabled("migrated-pending")
@Timeout(120)
class UatEvt001ReplayTest {

    // WU-LPR-072: shared AppBinSupport handles the pipelinek (post-WU-LPR-070)
    // and pipeline-application (legacy) install locations.
    private val appBin: Path by lazy { AppBinSupport.discover() }

    private val helloScript: Path by lazy {
        Paths.get(javaClass.getResource("/hello.pipeline.kts")!!.toURI())
    }

    @Test
    fun `cli run emits parseable JSON array`() {
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", helloScript.toString()),
        ).requireExited()
        val stdout = cliRun.stdout.trim()
        assertTrue(stdout.isNotEmpty(), "stdout must not be empty")
        // PK-NEXT R0.3: contract is decoded via the testkit. Line 1 is
        // the LaunchReceipt, line 2 is the JSON event document. The
        // "starts with '['" / "ends with ']'" assertions are wrong
        // against this contract and the testkit replaces them.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = cliRun.stdout,
            stderr = cliRun.stderr,
            format = ObservationFormat.JSON,
        )
        val events = contract.eventDocument.events
        assertNotNull(events)
    }

    @Test
    fun `re-parsed timeline equals original with correct kinds`() {
        val (stdout, events) = runAndDecode()
        // hello.pipeline.kts (DSL, 1 stage x 1 step) under the Single Runtime Spine (LF-0208).
        //
        // RunCorrelated leads the timeline, and that is now the contract rather than an accident of
        // implementation. It is emitted when the run acquires a durable identity — after the lease,
        // together with the `last-run` control record that `--resume` and execution ownership both
        // read. Emitting it after compilation would leave a script that fails to compile with no
        // correlation record at all, so the order is what keeps an early failure observable.
        // Ratified in `PipelineIds.kt` and ADR-PK1-IDENTITY (2026-10-15); the previous wording said
        // only "before the first RunStarted", which was true but under-determined, and three UATs
        // filled the gap by asserting an order no document had promised.
        //
        // RunCorrelated, CompilationStarted, CompilationFinished, RunStarted, StageStarted,
        // StepStarted, EchoOutputCaptured, StepFinished, StageFinished, RunFinished
        assertEquals(10, events.size, "Expected 10 events for hello DSL pipeline: $stdout")

        assertTrue(events[0] is RunCorrelated, "events[0] must be RunCorrelated")
        assertTrue(events[1] is CompilationStarted, "events[1] must be CompilationStarted")
        assertTrue(events[2] is CompilationFinished, "events[2] must be CompilationFinished")
        assertTrue(events[3] is RunStarted, "events[3] must be RunStarted")
        assertTrue(events[4] is StageStarted, "events[4] must be StageStarted (DSL evaluated)")
        assertTrue(events[5] is StepStarted, "events[5] must be StepStarted")
        assertTrue(events[6] is EchoOutputCaptured, "events[6] must be EchoOutputCaptured")
        assertTrue(events[7] is StepFinished, "events[7] must be StepFinished")
        assertTrue(events[8] is StageFinished, "events[8] must be StageFinished")
        assertTrue(events[9] is RunFinished, "events[9] must be RunFinished")

        // The count and the positions are the easy half. This is the half that a shift cannot fake:
        // the correlation must be the SAME run every later event belongs to, so a timeline that grew
        // a RunCorrelated from a different run — or whose runId silently differs below it — fails
        // here rather than passing on cardinality alone.
        val correlated = events[0] as RunCorrelated
        val correlatedRunId = correlated.runId
        assertTrue(
            correlated.correlationId.value.isNotBlank(),
            "RunCorrelated must carry a non-blank correlation token: $correlated",
        )
        val foreignRunIds = events.drop(1)
            .map { event -> event.runId }
            .filter { it != correlatedRunId }
            .distinct()
        assertTrue(
            foreignRunIds.isEmpty(),
            "every event after RunCorrelated must belong to the run it correlated; found other " +
                "runIds $foreignRunIds against $correlatedRunId",
        )

        val cf = events[2] as CompilationFinished
        assertEquals("v1", cf.cacheKey.version, "cacheKey.version must be v1")
        assertEquals(64, cf.cacheKey.value.length, "cacheKey.value must be 64-char hex")
        // M1-F.1 (PK-R1 R1-B extension): the Kotlin 2.4.10 compiler confirms
        // `-Xuse-fast-jar-file-system=false` with an INFO diagnostic. The diagnostic
        // is NOT a failure — it is the observable confirmation that the option was
        // accepted. The UAT asserts on ERROR/FATAL only, the user-actionable cases.
        val errorCfDiags = cf.diagnostics.filter {
            it.severity == ScriptDiagnosticSeverity.ERROR ||
                it.severity == ScriptDiagnosticSeverity.FATAL
        }
        assertTrue(
            errorCfDiags.isEmpty(),
            "CompilationFinished diagnostics must have no ERROR/FATAL for DSL: ${cf.diagnostics}",
        )

        val rf = events[9] as RunFinished
        assertEquals("success", rf.outcome, "RunFinished outcome must be success")
        val errorRfDiags = rf.diagnostics.filter {
            it.severity == ScriptDiagnosticSeverity.ERROR ||
                it.severity == ScriptDiagnosticSeverity.FATAL
        }
        assertTrue(
            errorRfDiags.isEmpty(),
            "RunFinished diagnostics must have no ERROR/FATAL: ${rf.diagnostics}",
        )

        val ss = events[4] as StageStarted
        assertEquals(0, ss.stageIndex, "stageIndex must be 0")
        assertEquals("hello", ss.stageName, "stageName must be hello")

        val stepStarted = events[5] as StepStarted
        assertEquals(0, stepStarted.stageIndex, "step stageIndex must be 0")
        assertEquals(0, stepStarted.stepIndex, "stepIndex must be 0")
        // G3: auto-named DSL steps are `<stage>/<type>-<index>` (DslCompiledPipelineCompilerTest).
        assertEquals("hello/echo-0", stepStarted.stepName, "stepName must follow the G3 contract")
        assertEquals("echo", stepStarted.stepType, "stepType must be echo")
    }

    @Test
    fun `two cli invocations yield structurally equal timelines`() {
        val (_, events1) = runAndDecode()
        val (_, events2) = runAndDecode()

        assertEquals(events1.size, events2.size, "Both runs must produce the same number of events")

        // LF-0206 identity model: each invocation has exactly one RunId and
        // two invocations of the same script MUST have different RunIds
        // (M1-001) while producing structurally identical timelines.
        val runIds1 = events1.map { it.runId }.distinct()
        val runIds2 = events2.map { it.runId }.distinct()
        assertEquals(1, runIds1.size, "Timeline 1 must use a single RunId")
        assertEquals(1, runIds2.size, "Timeline 2 must use a single RunId")
        assertTrue(
            runIds1.single() != runIds2.single(),
            "Two invocations of the same script must have different RunIds (M1-001)",
        )

        for (i in events1.indices) {
            val e1 = events1[i]
            val e2 = events2[i]

            // LF-0206: RunId is unique per invocation. Timelines must be
            // structurally equal EXCEPT the run id, which must differ
            // between invocations (M1-001) while staying internally
            // consistent within each timeline.
            assertEquals(e1.kind, e2.kind, "Event $i kind must match")
            assertEquals(e1.sequence, e2.sequence, "Event $i sequence must match")

            if (e1 is CompilationFinished && e2 is CompilationFinished) {
                assertEquals(e1.cacheKey.version, e2.cacheKey.version, "Event $i cacheKey.version must match")
                assertEquals(e1.cacheKey.value, e2.cacheKey.value, "Event $i cacheKey.value must match")
            }
            if (e1 is RunFinished && e2 is RunFinished) {
                assertEquals(e1.outcome, e2.outcome, "Event $i outcome must match")
            }
        }
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", helloScript.toString()),
        ).requireExited()
        val exitCode = cliRun.exitCode
        val stdout = cliRun.stdout
        if (exitCode != 0) {
            throw IllegalStateException("CLI exited with $exitCode. stderr: ${cliRun.stderr}")
        }
        // PK-NEXT R0.3: contract is decoded via the testkit.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = cliRun.stdout,
            stderr = cliRun.stderr,
            format = ObservationFormat.JSON,
        )
        if (contract.eventDocument.decodeError != null) {
            throw IllegalStateException(
                "JSON event document failed to decode: ${contract.eventDocument.decodeError}",
            )
        }
        val events = contract.eventDocument.events
        return stdout to events
    }
}
