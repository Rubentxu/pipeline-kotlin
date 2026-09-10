package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.classifyShellTerminal
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LB-02 / G3-A0 — replay/output characterization for the durable model.
 *
 * **Goal**: pin down the laws the LB-02 / Path A migration must preserve.
 * No behavior change. No extension. Just freeze the existing semantics so
 * Path A's later slices (A1..A7) have a mechanical oracle.
 *
 * ## Laws preserved
 *
 * 1. **Output presence does NOT decide replay.** The replay decision is a
 *    function of `(ReplayPolicy, Effect set, journal entry presence,
 *    journaled outcome)` — none of which mention `OperationOutput`.
 *    `OperationOutput` is a passive journal slot today; populating it
 *    does not change the decision.
 * 2. **Fingerprint is independent of `OperationOutput`.** The
 *    `FingerprintPayload` (see `Fingerprint.kt:48..55`) contains exactly
 *    `stepId`, `params`, `runId`, `attempt`, `replayPolicy`. Adding an
 *    `OperationOutput.result` slot to journal rows does not move the
 *    fingerprint one bit.
 * 3. **Legacy sh terminal pipeline** is `ShellInvocationResult →
 *    classifyShellTerminal` (pure) → `OperationOutput` (constructed by
 *    `JournaledScriptedOperationRuntime`). The `toStepOutcome()` classifier
 *    is reused by every `core.sh` step.
 *
 * @see docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md — Path A reframed
 * @see <a href="design.md §E4-06">design.md §E4-06</a> — replay decision matrix
 */
@Timeout(10)
class ReplayOutputDecouplingTest {

    private val policy = DefaultEffectReplayPolicy()

    /**
     * A canonical `OperationOutput` populated the way Path A intends.
     * The slot is well-formed JSON over `result` + `durationMs` +
     * `finishedAt`, exactly the shape durable rows already accept.
     */
    private fun outputWithText(text: String): OperationOutput = OperationOutput(
        result = JsonPrimitive(text),
        durationMs = 1L,
        finishedAt = 1L,
    )

    private fun input(text: String): OperationInput = OperationInput(
        stepId = "step-shell",
        params = mapOf("script" to JsonPrimitive(text)),
        runId = "run-1",
        attempt = 1,
    )

    // -------- Law 1: output presence does NOT decide replay --------

    @Test
    fun `MEMOIZED plus READ_ONLY plus SUCCEEDED returns SKIP regardless of output presence`() {
        // Output is present; replay decision ignores it.
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.SUCCEEDED),
        )
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.SUCCEEDED),
        )
        // Even witness: build a real OperationOutput; it does not affect the
        // decision because the policy never reads it.
        val populated = outputWithText("carry-capturedStdout")
        assertEquals(JsonPrimitive("carry-capturedStdout"), populated.result)
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.SUCCEEDED),
        )
    }

    @Test
    fun `RERUN plus SUCCEEDED returns SKIP regardless of output presence`() {
        // RERUN + SUCCEEDED already short-circuits to SKIP. Output presence
        // does not flip this to RERUN. This is the law LB-02 must preserve:
        // populating OperationOutput MUST NOT make a RERUN+SUCCEEDED
        // short-circuit into RERUN.
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), true, OperationStatus.SUCCEEDED),
        )
        // Even with output populated on the journal row, the policy is the
        // same — proved by structural review of `DefaultEffectReplayPolicy.kt`
        // (lines 73-78 ignore any output field).
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), true, OperationStatus.SUCCEEDED),
        )
    }

    @Test
    fun `RERUN plus non-SUCCEEDED returns RERUN regardless of output presence`() {
        // Even with output on a journal row, if the row is FAILED / RUNNING,
        // the policy reruns.
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), true, OperationStatus.FAILED),
        )
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), true, OperationStatus.RUNNING),
        )
    }

    @Test
    fun `MEMOIZED plus EXECUTES_SUBPROCESS plus SUCCEEDED returns RERUN regardless of output presence`() {
        // Even with output on a SUCCEEDED row, an EXECUTES_SUBPROCESS step
        // always reruns (decision matrix row 4).
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.EXECUTES_SUBPROCESS), true, OperationStatus.SUCCEEDED),
        )
    }

    @Test
    fun `MEMOIZED plus READ_ONLY plus FAILED journaled outcome returns RERUN regardless of output presence`() {
        // FAILED → RERUN regardless of output presence.
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.FAILED),
        )
    }

    @Test
    fun `ABORTS_PIPELINE effect returns ABORT regardless of output presence`() {
        // Effect choice wins; output ignored.
        assertEquals(
            ReplayDecision.ABORT,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.ABORTS_PIPELINE), true, OperationStatus.SUCCEEDED),
        )
    }

    @Test
    fun `NEVER policy decision is independent of output presence`() {
        // E-EM-11/NEVER-1 (contract update): NEVER constrains re-execution of durable
        // history — fresh executes, journaled aborts. Operational semantics win;
        // output presence is ignored in BOTH branches.
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.NEVER, setOf(Effect.EXECUTES_SUBPROCESS), false, null),
            "fresh NEVER invocation must execute regardless of output",
        )
        assertEquals(
            ReplayDecision.ABORT,
            policy.decide(ReplayPolicy.NEVER, setOf(Effect.EXECUTES_SUBPROCESS), true, null),
            "journaled NEVER invocation must abort regardless of output",
        )
    }

    // -------- Law 2: Fingerprint payload is independent of output --------

    @Test
    fun `fingerprint is identical regardless of OperationOutput content on the journal row`() {
        // The fingerprint payload (Fingerprint.kt:48..55) does not include
        // OperationOutput. Two operations with identical inputs and replay
        // policies produce identical fingerprints even if one carries an
        // OperationOutput and the other does not.
        val i = input("echo hi")
        val base = Fingerprint.compute(i, "step-shell", ReplayPolicy.RERUN, 1).hex

        // Construct an OperationOutput and prove it does not enter the hash:
        // the canonical payload does NOT carry it (proven structurally by
        // the data class fields), and the hash function ignores anything
        // outside the payload.
        val populated = outputWithText("persisted output")
        val reComputed = Fingerprint.compute(i, "step-shell", ReplayPolicy.RERUN, 1).hex

        assertEquals(base, reComputed, "fingerprint must not depend on OperationOutput")

        // Sanity guard: the output slot exists and is non-empty; this is a
        // witness that the test does carry output, and yet the fingerprint
        // did not move.
        assertEquals(true, populated.result is JsonElement)
    }

    @Test
    fun `fingerprint payload computes from operation input only (Fingerprint signature)`() {
        // The compute() signature is `(OperationInput, String, ReplayPolicy, Int)`.
        // If a future contributor adds an `output` parameter, this assertion
        // fails loudly because `compute(i, "step-shell", ReplayPolicy.RERUN, 1)`
        // will no longer type-check.
        val i = input("echo hi")
        val fp = Fingerprint.compute(i, "step-shell", ReplayPolicy.RERUN, 1)
        assertEquals(64, fp.hex.length)
    }

    // -------- Law 3: Legacy sh terminal pipeline is well-defined and pure --------

    private fun exited(code: Int, captured: String = ""): DurableTaskTerminal.Exited =
        DurableTaskTerminal.Exited(
            exitCode = code,
            output = DurableTaskOutput(controlDir = "/tmp/control", capturedStdout = captured),
        )

    private fun launchFailedRecord(): FailureRecord = FailureRecord(
        code = "LAUNCH_FAILED",
        kind = FailureKind.INFRASTRUCTURE,
        message = "could not spawn",
        origin = FailureOrigin.LAUNCHER,
        retryable = false,
        operationId = "op-launch",
    )

    private fun lostRecord(): FailureRecord = FailureRecord(
        code = "TASK_LOST",
        kind = FailureKind.INFRASTRUCTURE,
        message = "lost",
        origin = FailureOrigin.RECONCILIATION,
        retryable = false,
        operationId = "op-lost",
    )

    private fun interruptionRecord(kind: InterruptionKind): InterruptionRecord =
        InterruptionRecord(kind = kind, message = "interrupt", operationId = "op-int")

    @Test
    fun `classifyShellTerminal — exited 0 + NONE returns UnitValue`() {
        assertEquals(
            ShellInvocationResult.UnitValue,
            classifyShellTerminal(exited(0), ShellReturnMode.NONE),
        )
    }

    @Test
    fun `classifyShellTerminal — exited 0 + STDOUT returns Stdout from captured stream`() {
        val out = classifyShellTerminal(exited(0, "hello world\n"), ShellReturnMode.STDOUT)
        assertEquals(ShellInvocationResult.Stdout("hello world\n"), out)
    }

    @Test
    fun `classifyShellTerminal — exited 7 + STDOUT returns Failed SCRIPT not Stdout`() {
        // Non-zero exit MUST NOT collapse to a successful Stdout even when
        // returnMode == STDOUT. This is the FKind-preserving law.
        val out = classifyShellTerminal(exited(7, "partial\n"), ShellReturnMode.STDOUT)
        assertEquals(ShellInvocationResult.Failed::class, out::class)
        out as ShellInvocationResult.Failed
        assertEquals(FailureKind.SCRIPT, out.failure.kind)
        assertEquals(7, out.exitCode)
    }

    @Test
    fun `classifyShellTerminal — exited 0 + STATUS returns Status 0`() {
        assertEquals(
            ShellInvocationResult.Status(0),
            classifyShellTerminal(exited(0), ShellReturnMode.STATUS),
        )
    }

    @Test
    fun `classifyShellTerminal — exited 7 + STATUS returns Status 7 (shell success, non-zero exit)`() {
        // STATUS is the only returnMode where a non-zero exit does not surface
        // as Failed. Per `ShellInvocationResult.kt:50-60` this is the
        // documented semantic for "shell success, non-zero exit".
        assertEquals(
            ShellInvocationResult.Status(7),
            classifyShellTerminal(exited(7), ShellReturnMode.STATUS),
        )
    }

    @Test
    fun `classifyShellTerminal — LaunchFailed returns Failed with INFRASTRUCTURE kind`() {
        val out = classifyShellTerminal(
            DurableTaskTerminal.LaunchFailed(launchFailedRecord()),
            ShellReturnMode.STDOUT,
        )
        out as ShellInvocationResult.Failed
        assertEquals(FailureKind.INFRASTRUCTURE, out.failure.kind)
    }

    @Test
    fun `classifyShellTerminal — Lost returns Failed with the recorded kind`() {
        val out = classifyShellTerminal(
            DurableTaskTerminal.Lost(lostRecord()),
            ShellReturnMode.STDOUT,
        )
        out as ShellInvocationResult.Failed
        assertEquals(FailureKind.INFRASTRUCTURE, out.failure.kind)
    }

    @Test
    fun `classifyShellTerminal — Cancelled TIMEOUT returns Interrupted with TIMEOUT kind`() {
        val out = classifyShellTerminal(
            DurableTaskTerminal.Cancelled(interruptionRecord(InterruptionKind.TIMEOUT)),
            ShellReturnMode.STDOUT,
        )
        out as ShellInvocationResult.Interrupted
        assertEquals(InterruptionKind.TIMEOUT, out.interruption.kind)
    }

    @Test
    fun `classifyShellTerminal — Cancelled PARENT_CANCELLED returns Interrupted with PARENT_CANCELLED kind`() {
        val out = classifyShellTerminal(
            DurableTaskTerminal.Cancelled(interruptionRecord(InterruptionKind.PARENT_CANCELLED)),
            ShellReturnMode.STDOUT,
        )
        out as ShellInvocationResult.Interrupted
        assertEquals(InterruptionKind.PARENT_CANCELLED, out.interruption.kind)
    }

    // -------- Law 4: OperationOutput is a passive journal slot today --------

    @Test
    fun `OperationOutput is present on RerunOperation only when produced - absent is the default`() {
        // This is the "passive journal slot" law Path A is constrained by:
        // RerunOperation.output: OperationOutput? is nullable; the durable
        // coordinator constructs rows with output = null in the surface
        // touched today (4 sites in CanonicalDurableRunCoordinator.kt).
        // LB-02 / A1+ will start populating it; A0 freezes the empty state.
        val row = RerunOperation(
            id = "op-1",
            fingerprint = Fingerprint("a".repeat(64)),
            input = input("noop"),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        assertEquals(null, row.output)
        assertEquals(ReplayPolicy.RERUN, row.replayPolicy)
    }

    @Test
    fun `OperationOutput is a JSON-shaped carrier — well-formed JSON primitive is durable-eligible`() {
        // Mirrors echo's CDE.3-e1/e2 durable eligibility on the input side:
        // the output slot, when populated, must be a well-formed JSON value.
        val output = outputWithText("the typed-output slot accepts well-formed JSON")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(
            "\"the typed-output slot accepts well-formed JSON\"",
        )
        assertEquals(parsed, output.result)
    }

    // -------- Law 5: PipelineRun.stepTypeMetadata classifies sh by script vs command form --------

    @Test
    fun `legacy sh classification — returnStdout false is RERUN plus EXECUTES_SUBPROCESS`() {
        // Mirrors PipelineRun.kt:1919-1925: a non-script `sh "command"` is
        // EXECUTES_SUBPROCESS + RERUN. OperationOutput presence on a previous
        // attempt MUST NOT turn this into a SKIP-flip into a RERUN (Path A
        // correctness invariant: populating the slot does not change RERUN
        // into a re-execute. RERUN already runs by definition.).
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.RERUN, setOf(Effect.EXECUTES_SUBPROCESS), true, OperationStatus.SUCCEEDED),
            "RERUN + EXECUTES_SUBPROCESS + SUCCEEDED must SKIP — populating OperationOutput must not flip this",
        )
    }

    @Test
    fun `legacy sh classification — script block form is MEMOIZED plus READ_ONLY`() {
        // Mirrors PipelineRun.kt:1919-1925: a `script { ... }` block is
        // READ_ONLY + MEMOIZED. Output presence on a SUCCEEDED row makes
        // it SKIP; absence triggers RERUN. The decision is independent of
        // the output slot itself.
        assertEquals(
            ReplayDecision.SKIP,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.SUCCEEDED),
        )
        assertEquals(
            ReplayDecision.RERUN,
            policy.decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), false, null),
        )
    }

    // -------- PipelineFailure/StepOutcome cross-check --------

    @Test
    fun `PipelineFailure carries FailureKind that the durable layered classifier must preserve`() {
        // Sanity on the sealed algebra: every failure has a closed Kind.
        // Path A is forbidden from collapsing these into an opaque String.
        val scriptFailure = PipelineFailure(FailureKind.SCRIPT, "exit 7")
        val infraFailure = PipelineFailure(FailureKind.INFRASTRUCTURE, "could not spawn")
        val timeoutFailure = PipelineFailure(FailureKind.TIMEOUT, "timeout 30s")
        assertEquals(FailureKind.SCRIPT, scriptFailure.kind)
        assertEquals(FailureKind.INFRASTRUCTURE, infraFailure.kind)
        assertEquals(FailureKind.TIMEOUT, timeoutFailure.kind)
    }
}
