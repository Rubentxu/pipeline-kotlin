package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.DurableInvocationResolver
import dev.rubentxu.pipeline.v2.application.durable.InvocationReconciliation
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive

/**
 * TRAIN H3 / PR-019 slice 0 — RED-first characterization of the durable RECOVERY contract.
 *
 * PR-019 moves invocation and recovery out of
 * [dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator] into engines.
 * This pins the contract that move must preserve, BEFORE the move, at HF0 (pure contract),
 * with no production code touched.
 *
 * Why this and not the coordinator: `deterministicGate` and `replayResolution` plus the
 * precedence law between them had no direct coverage at all — a single existing test
 * referenced the resolver, and only through the shell capability. The decision is the part
 * whose reordering would silently change what a RESUMED run is allowed to do, so it is
 * exactly the part to pin first.
 *
 * The three laws:
 *
 *  1. PRECEDENCE. divergence, then external-subprocess recovery, then replay. A divergent
 *     fingerprint must win even when the replay policy would have reused the row: a changed
 *     payload under a reused operation id is a fail-closed condition, not a cache hit.
 *  2. PURITY. The gate and the replay mapping are total functions of their inputs. They touch
 *     no journal, cursor, event sink, process or executor. [ForbiddenJournal] enforces this
 *     mechanically instead of trusting the assertion: any call fails the test.
 *  3. TOTALITY. Every [ReplayDecision] maps to exactly one resolution, with no else branch
 *     absorbing an unhandled case.
 */
class InvocationRecoveryCharacterizationTest {

    private val operationId = "op-run-0-0"
    private val clock: dev.rubentxu.pipeline.v2.domain.durable.Clock =
        object : dev.rubentxu.pipeline.v2.domain.durable.Clock {
            override fun now(): Instant = Instant.parse("2026-10-02T00:00:00Z")
        }
    private val sameFingerprint = Fingerprint("a".repeat(64))
    private val otherFingerprint = Fingerprint("b".repeat(64))

    /**
     * Law 2, mechanically. The reconciliation decision is documented to touch no journal; this
     * double turns that claim into a failing test the moment it stops being true, instead of a
     * comment that quietly rots.
     */
    private class ForbiddenJournal : OperationJournal {
        private fun reject(): Nothing = throw AssertionError(
            "The reconciliation decision must stay pure: it touched the journal. " +
                "A decision that writes is a decision that has left the pure core.",
        )

        override fun append(op: DurableOperation, deadlineMs: Long?) = reject()
        override fun beginOperation(
            opId: String,
            attempt: Int,
            fingerprint: String,
            inputJson: String,
            deadlineMs: Long?,
        ) = reject()
        override fun get(opId: String): DurableOperation? = reject()
        override fun get(opId: String, attempt: Int): DurableOperation? = reject()
        override fun listForRun(runId: String): List<DurableOperation> = reject()
        override fun getDeadlineMs(opId: String, attempt: Int): Long? = reject()
        override fun getEndedAt(opId: String, attempt: Int): Long? = reject()
        override fun getStartedAt(opId: String, attempt: Int): Long? = reject()
    }

    private fun input(payload: String) = OperationInput(
        stepId = "core.echo",
        params = mapOf("payload" to JsonPrimitive(payload)),
        runId = "run-1",
        attempt = 1,
    )

    private fun current(fingerprint: Fingerprint, payload: String = "payload-a") = RerunOperation(
        id = operationId,
        fingerprint = fingerprint,
        input = input(payload),
        output = null,
        status = OperationStatus.PENDING,
        attempt = 1,
    )

    /** The journaled row a real run would find: a memoized row of the same operation id. */
    private fun journaledRow(
        fingerprint: Fingerprint,
        status: OperationStatus,
        payload: String = "payload-a",
    ) = MemoizedOperation(
        id = operationId,
        fingerprint = fingerprint,
        input = input(payload),
        output = null,
        status = status,
        attempt = 1,
        cachedOutput = null,
    )

    private fun resolver(controlDir: Path? = null) = DurableInvocationResolver(
        divergenceDetector = StrictFingerprintDivergenceDetector(),
        effectReplayPolicy = dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy(),
        journal = ForbiddenJournal(),
        // TRAIN H3 / PR-019: the compatibility hook is a PORT. The test drives the real adapter
        // against a temp control directory, so the decision is exercised with the same
        // implementation production uses rather than a stand-in that could drift from it.
        runningSubprocessRecovery = dev.rubentxu.pipeline.v2.application.durable.ExternalSubprocessRecovery(clock, controlDir),
    )

    // ===== law 1: the deterministic gate is a pure divergence check =====

    @Test
    fun `a matching fingerprint does not gate, so the replay decision is reached`() {
        val gate = resolver().deterministicGate(
            currentOperation = current(sameFingerprint),
            journaled = journaledRow(sameFingerprint, OperationStatus.SUCCEEDED),
            operationId = operationId,
            effects = emptySet(),
            replayPolicy = ReplayPolicy.RERUN,
        )

        assertNull(
            gate,
            "A matching fingerprint must not produce a terminal resolution; the replay kernel decides instead",
        )
    }

    @Test
    fun `a divergent fingerprint gates closed and names the operation`() {
        val gate = resolver().deterministicGate(
            currentOperation = current(otherFingerprint, payload = "payload-a"),
            journaled = journaledRow(sameFingerprint, OperationStatus.SUCCEEDED, payload = "payload-b"),
            operationId = operationId,
            effects = emptySet(),
            replayPolicy = ReplayPolicy.RERUN,
        )

        assertEquals(
            InvocationReconciliation.Diverged(operationId),
            gate,
            "Divergence is a terminal fail-closed resolution that must carry the operation id",
        )
    }

    @Test
    fun `divergence outranks a replay policy that would otherwise have reused the row`() {
        // The gate is asked with MEMOIZED, the policy that on its own would reuse a completed
        // row. Divergence must still win: this is the assertion that pins the ORDER.
        val gate = resolver().deterministicGate(
            currentOperation = current(otherFingerprint, payload = "payload-a"),
            journaled = journaledRow(sameFingerprint, OperationStatus.SUCCEEDED, payload = "payload-b"),
            operationId = operationId,
            effects = emptySet(),
            replayPolicy = ReplayPolicy.MEMOIZED,
        )

        assertTrue(
            gate is InvocationReconciliation.Diverged,
            "A changed payload under a reused operation id is a fail-closed divergence, not a cache " +
                "hit; the replay policy must not be consulted to rescue it, got $gate",
        )
    }

    @Test
    fun `an absent journal row never gates`() {
        assertNull(
            resolver().deterministicGate(
                currentOperation = current(otherFingerprint),
                journaled = null,
                operationId = operationId,
                effects = emptySet(),
                replayPolicy = ReplayPolicy.RERUN,
            ),
            "A fresh run has nothing to diverge from",
        )
    }

    // ===== law 3: the replay mapping is total and closed =====

    @Test
    fun `every replay decision maps to exactly one resolution`() {
        val expected = mapOf(
            ReplayDecision.SKIP to InvocationReconciliation.ReuseCompleted,
            ReplayDecision.ABORT to InvocationReconciliation.RejectedAbort(operationId),
            ReplayDecision.RERUN to InvocationReconciliation.Execute,
        )

        ReplayDecision.entries.forEach { decision ->
            assertEquals(
                expected[decision],
                resolver().replayResolution(decision, operationId),
                "ReplayDecision.$decision must map to its single resolution; a missing arm would " +
                    "hide an unhandled case behind an else branch",
            )
        }
    }

    // ===== the full precedence, through the public decision =====

    @Test
    fun `a fresh run with no journal row executes`() {
        val metadata = StepMetadata(
            effects = emptySet(),
            replayPolicy = ReplayPolicy.RERUN,
            recoveryPolicy = RecoveryPolicy.None,
        )

        assertEquals(
            InvocationReconciliation.Execute,
            resolver().reconcileInvocation(
                metadata = metadata,
                journaled = null,
                currentOperation = current(otherFingerprint),
                operationId = operationId,
            ),
            "With no journal row there is nothing to reuse, recover or diverge from: the invocation runs",
        )
    }

    @Test
    fun `recovery is skipped when the step declares no external subprocess policy`() {
        // A control dir EXISTS, so only the declared policy can keep recovery from firing. If
        // recovery were a status sniff rather than a declared effect, this row would be
        // recovered here — which is the regression this law exists to prevent.
        val resolution = resolver(controlDir = Path.of("/tmp/h3-characterization-control")).reconcileInvocation(
            metadata = StepMetadata(
                effects = setOf(Effect.EXECUTES_SUBPROCESS),
                replayPolicy = ReplayPolicy.RERUN,
                recoveryPolicy = RecoveryPolicy.None,
            ),
            journaled = journaledRow(sameFingerprint, OperationStatus.RUNNING),
            currentOperation = current(sameFingerprint),
            operationId = operationId,
        )

        assertTrue(
            resolution !is InvocationReconciliation.RecoverRunning,
            "Recovery is a declared-policy effect, not a status sniff: a RUNNING row on a step that " +
                "does not declare ExternalSubprocess must fall through to the replay decision, got $resolution",
        )
    }

    @Test
    fun `a divergent row resolves to Diverged through the full precedence`() {
        val resolution = resolver().reconcileInvocation(
            metadata = StepMetadata(
                effects = emptySet(),
                replayPolicy = ReplayPolicy.MEMOIZED,
                recoveryPolicy = RecoveryPolicy.None,
            ),
            journaled = journaledRow(sameFingerprint, OperationStatus.SUCCEEDED, payload = "payload-b"),
            currentOperation = current(otherFingerprint, payload = "payload-a"),
            operationId = operationId,
        )

        assertEquals(
            InvocationReconciliation.Diverged(operationId),
            resolution,
            "End to end: divergence is decided before the recovery hook and before the replay kernel",
        )
    }

    /**
     * Law 1, with teeth. The case above is NOT sufficient on its own: its recovery hook is inert
     * (no ExternalSubprocess policy, no control dir), so it resolves to Diverged whichever order
     * the two steps run in. It pins the divergence GATE, not the precedence.
     *
     * This case is the one that actually observes the order. Every precondition that makes the
     * recovery hook fire is present — ExternalSubprocess declared, a RUNNING row, and a real
     * control directory — so an operation with no reconcilable control data classifies as Lost and
     * resolves to RecoverRunning. If the hook were consulted first the run would recover a
     * DIFFERENT payload than the one the journal describes. Divergence must still win.
     *
     * Verified by mutation: moving the recovery hook above the divergence gate turns this red.
     */
    @Test
    fun `divergence outranks an armed recovery hook`() {
        val controlDir = Files.createTempDirectory("h3-precedence")
        try {
            val resolution = resolver(controlDir = controlDir).reconcileInvocation(
                metadata = StepMetadata(
                    effects = setOf(Effect.EXECUTES_SUBPROCESS),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                    recoveryPolicy = RecoveryPolicy.ExternalSubprocess,
                ),
                journaled = journaledRow(sameFingerprint, OperationStatus.RUNNING, payload = "payload-b"),
                currentOperation = current(otherFingerprint, payload = "payload-a"),
                operationId = operationId,
            )

            assertEquals(
                InvocationReconciliation.Diverged(operationId),
                resolution,
                "The recovery hook is armed and would resolve to RecoverRunning; divergence must still " +
                    "be decided first, otherwise a resumed run acts on a payload the journal does not describe",
            )
        } finally {
            controlDir.toFile().deleteRecursively()
        }
    }
}
