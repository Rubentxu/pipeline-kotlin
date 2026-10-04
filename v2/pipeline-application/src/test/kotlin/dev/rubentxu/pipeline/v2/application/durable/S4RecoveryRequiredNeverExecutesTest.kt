package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0103 R1-E — the structural law, stated as a contract over the DECISION and nothing else.
 *
 * The owner's condition for declaring R1-E closed was: *"no `ExternalSubprocess + RUNNING` path
 * may reach `Execute` without having first obtained a conclusive recovery observation."* That is a
 * statement about every path, not about the one that happened to be measured, so it is proved
 * here as a matrix rather than as a story.
 *
 * ## Why a matrix and not one more end-to-end test
 *
 * [S4RecoveryUnobservableFailsClosedTest] proves the consequence through the real spine: handler
 * count, capability reads, cursor writes and the surviving journal row. This proves the *shape* of
 * the decision itself, over every policy/status/substrate combination that can reach the resolver,
 * with a scripted observer standing in for the filesystem so the matrix can enumerate the cases an
 * OS would otherwise have to be asked for one at a time.
 *
 * It is a contract test at HF0: [DurableInvocationResolver] is the real authority, called directly,
 * with no coordinator and no process. Nothing here reimplements the decision — the assertions are
 * about what the authority RETURNS, and a script that disagreed with it would not make this fail.
 *
 * ## The shape being pinned
 *
 * `Execute` is produced in exactly one place: the replay kernel's `RERUN`. So the whole question is
 * whether a required observation can be bypassed to reach it. This matrix asserts it cannot, for
 * every combination, including the ones that must still fall through to the kernel.
 */
class S4RecoveryRequiredNeverExecutesTest {

    @Test
    fun `no required recovery ever resolves to Execute, whatever the observer says`() {
        // Every way the substrate can answer, crossed with every replay policy a Step may declare.
        val observations = listOf(
            "Recovered-SUCCEEDED" to RunningSubprocessObservation.Recovered(StepOutcome.Success, OperationStatus.SUCCEEDED),
            "Recovered-FAILED" to RunningSubprocessObservation.Recovered(
                StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "exit 1")),
                OperationStatus.FAILED,
            ),
            "Recovered-LOST" to RunningSubprocessObservation.Recovered(
                StepOutcome.Failure(PipelineFailure(FailureKind.INFRASTRUCTURE, "gone")),
                OperationStatus.LOST,
            ),
            "Unavailable" to RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured),
        )
        val policies = listOf(ReplayPolicy.RERUN, ReplayPolicy.MEMOIZED, ReplayPolicy.NEVER)

        val escapes = buildList {
            for ((name, observation) in observations) {
                for (policy in policies) {
                    for (effect in Effect.entries) {
                        val resolution = resolve(
                            recoveryPolicy = RecoveryPolicy.ExternalSubprocess,
                            status = OperationStatus.RUNNING,
                            replayPolicy = policy,
                            effect = effect,
                            observation = observation,
                        )
                        if (resolution == InvocationReconciliation.Execute) {
                            add("observation=$name replayPolicy=$policy effect=$effect")
                        }
                    }
                }
            }
        }

        assertTrue(
            escapes.isEmpty(),
            "LAW: `ExternalSubprocess + RUNNING` must never reach Execute, whatever the observer " +
                "returns and whatever the replay policy declares. These combinations escaped: $escapes",
        )
    }

    /**
     * The other half, so the law cannot be satisfied by simply refusing to run: the cases where
     * recovery genuinely does NOT apply must still reach the replay kernel, and an operation that is
     * not in flight must still resolve.
     *
     * A fail-closed resolver that answered `RecoveryUnobservable` to everything would pass the test
     * above while having stopped being a runtime. This is what stops that shortcut.
     */
    @Test
    fun `recovery that genuinely does not apply still reaches the replay kernel`() {
        // None policy: not applicable, so the declared policy decides — RERUN executes.
        assertTrue(
            resolve(
                RecoveryPolicy.None, OperationStatus.RUNNING, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS,
                RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured),
            ) is InvocationReconciliation.Execute,
            "LAW: a policy that declares no recovery owes no observation, so RERUN must still " +
                "execute. Fail-closed is not a reason to stop the world.",
        )

        // ExternalSubprocess but the row is terminal: nothing in flight to reattach.
        for (terminal in listOf(
            OperationStatus.SUCCEEDED, OperationStatus.FAILED,
            OperationStatus.FAILED_TIMEOUT, OperationStatus.ABORTED, OperationStatus.LOST,
        )) {
            val resolution = resolve(
                RecoveryPolicy.ExternalSubprocess, terminal, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS,
                RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured),
            )
            assertTrue(
                resolution !is InvocationReconciliation.RecoveryUnobservable,
                "LAW: a terminal row is not in flight, so there is nothing to be unable to observe. " +
                    "Only a RUNNING row can be unobservable. Got $resolution for $terminal",
            )
        }
    }

    @Test
    fun `the unobservable arm is reachable only for a RUNNING row under a subprocess policy`() {
        val unobservable = RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured)
        val recoverPolicy = RunningSubprocessObservation.Recovered(StepOutcome.Success, OperationStatus.SUCCEEDED)

        val byCombination: List<Triple<String, String, Boolean>> = buildList {
            for (policy in listOf(RecoveryPolicy.None, RecoveryPolicy.ExternalSubprocess)) {
                for (status in OperationStatus.entries) {
                    val r = resolve(policy, status, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS, unobservable)
                    add(Triple(policy.javaClass.simpleName, status.name, r is InvocationReconciliation.RecoveryUnobservable))
                }
            }
        }

        val actual = byCombination.filter { it.third }.map { "${it.first}+${it.second}" }.sorted()
        val expected = listOf("ExternalSubprocess+${OperationStatus.RUNNING.name}").sorted()
        // RecoveryPolicy is a sealed interface, so a data object has no .name; the class name is
        // the stable discriminator here and matches the ledger line below.
        assertTrue(
            actual == expected,
            "LAW: `RecoveryUnobservable` must mean exactly one thing — a RUNNING row under a " +
                "subprocess recovery policy. If it also fires for terminal rows it is saying " +
                "'I cannot see it' about operations that are already closed, and if it never fires " +
                "the arm is dead code. Got $actual, expected $expected",
        )

        // And the observer is never consulted for a combination that owes it nothing.
        for ((policy, status) in listOf(
            RecoveryPolicy.None to OperationStatus.RUNNING,
            RecoveryPolicy.None to OperationStatus.SUCCEEDED,
            RecoveryPolicy.ExternalSubprocess to OperationStatus.SUCCEEDED,
            RecoveryPolicy.ExternalSubprocess to OperationStatus.LOST,
        )) {
            val (_, probes) = resolveCountingProbes(
                policy, status, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS, recoverPolicy,
            )
            assertTrue(
                probes == 0,
                "LAW: the observer is asked only about a REQUIRED recovery. Being asked anyway " +
                    "would be the same defect one level up — a component that cannot see the " +
                    "policy, answering a question about it. $policy+$status probed $probes times",
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves one combination against a FRESH authority wired to an observer that answers exactly
     * what the case needs. Rebuilding per case is deliberate: it is the only way to count probes
     * per combination, and a shared resolver would let one case's probe count answer for another.
     */
    private fun resolve(
        recoveryPolicy: RecoveryPolicy,
        status: OperationStatus,
        replayPolicy: ReplayPolicy,
        effect: Effect,
        observation: RunningSubprocessObservation,
    ): InvocationReconciliation =
        resolveCountingProbes(recoveryPolicy, status, replayPolicy, effect, observation).first

    private fun resolveCountingProbes(
        recoveryPolicy: RecoveryPolicy,
        status: OperationStatus,
        replayPolicy: ReplayPolicy,
        effect: Effect,
        observation: RunningSubprocessObservation,
    ): Pair<InvocationReconciliation, Int> {
        val observer = ScriptedObserver(observation)
        val input = OperationInput(stepId = STEP_ID, params = emptyMap(), runId = RUN_ID, attempt = 1)
        val fingerprint = Fingerprint.compute(input, STEP_ID, replayPolicy, 1)
        val journaled = RerunOperation(
            id = OPERATION_ID,
            fingerprint = fingerprint,
            input = input,
            output = null,
            status = status,
            attempt = 1,
        )
        val resolution = DurableInvocationResolver(
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            journal = InMemoryOperationJournal(SystemClock()),
            runningSubprocessRecovery = observer,
        ).reconcileInvocation(
            metadata = StepMetadata(
                effects = setOf(effect),
                replayPolicy = replayPolicy,
                recoveryPolicy = recoveryPolicy,
            ),
            journaled = journaled,
            currentOperation = journaled,
            operationId = OPERATION_ID,
        )
        return resolution to observer.probes.get()
    }

    private companion object {
        const val STEP_ID = "law.recovery.required"
        const val RUN_ID = "law-run"
        const val OPERATION_ID = "law-op"
    }

    private class ScriptedObserver(
        private val answer: RunningSubprocessObservation = RunningSubprocessObservation.Unavailable(
            UnobservableCause.NoControlRootConfigured,
        ),
    ) : RunningSubprocessRecovery {
        val probes = java.util.concurrent.atomic.AtomicInteger(0)
        override fun observe(operationId: String): RunningSubprocessObservation {
            probes.incrementAndGet()
            return answer
        }
    }
}
