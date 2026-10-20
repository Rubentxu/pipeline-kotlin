package dev.rubentxu.pipeline.v2.domain

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Concurrent dispatch of one [ExecutionUnit.Concurrent] wave through the
 * **same** [StepDispatcher] instance that single steps use (LF-0207 —
 * M2-004: "parallel usa dispatcher principal").
 *
 * ## The single-dispatcher property
 *
 * The wave dispatcher is a decorator: it owns no execution logic of its
 * own. Every step of every wave is handed to the injected [delegate] —
 * the identical instance the serial path uses. There is no second
 * execution path to bypass the port.
 *
 * ## Determinism
 *
 * All wave steps are submitted before any result is collected, and
 * results are collected in **declaration order** — not completion order.
 * Downstream folding via [RunOutcomeReducer] ("first failure wins") is
 * therefore deterministic regardless of thread scheduling (M2-005).
 *
 * ## Executor ownership
 *
 * The injected [ExecutorService] is owned by the caller (the composition
 * root): this class submits work and collects results but never shuts
 * the pool down. A same-thread executor degrades waves to sequential
 * dispatch while preserving the exact call ordering semantics.
 *
 * ## Failure policy
 *
 * The [StepDispatcher] contract forbids throwing to signal step failure,
 * but a dispatcher BUG may still throw on a worker thread. Such throwables
 * are contained per step and mapped to
 * [StepOutcome.Failure] with [FailureKind.INFRASTRUCTURE] — one broken
 * step must not poison the wave. Interruption of the collecting thread
 * propagates (cancellation semantics are the caller's concern).
 *
 * Not thread-safe per instance configuration is irrelevant: the class is
 * stateless, so a single instance may be shared across concurrent runs.
 */
class ConcurrentStepDispatcher(
    private val delegate: StepDispatcher,
    private val executor: ExecutorService,
) {

    /**
     * Dispatches every step of the wave concurrently and returns the
     * outcomes in the steps' declaration order.
     *
     * All steps are submitted before the first result is awaited, so the
     * wave makes progress even if the executor is single-threaded.
     */
    fun dispatchAll(steps: List<StepDescriptor>, context: StepExecutionContext): List<StepOutcome> {
        val futures: List<Future<StepOutcome>> = steps.map { step ->
            executor.submit(
                Callable<StepOutcome> {
                    try {
                        delegate.dispatch(step, context)
                    } catch (ex: Throwable) {
                        StepOutcome.Failure(
                            PipelineFailure(
                                kind = FailureKind.INFRASTRUCTURE,
                                message = "dispatcher bug while executing step '${step.id}': " +
                                    "${ex::class.java.simpleName}: ${ex.message ?: "no message"}",
                                cause = ex,
                            )
                        )
                    }
                }
            )
        }
        return futures.map { it.get() }
    }
}
