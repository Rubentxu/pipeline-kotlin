package dev.rubentxu.pipeline.v2.spike.stagescoped

import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Closed, immutable trace of a stage's interpretation.
 *
 * This is the spike's analogue of the canonical durable execution journal,
 * **scoped to one stage**, and bounded to runtime-returning calls. The
 * production journal covers everything (typed events + retry control rows);
 * this trace covers only the suspend outcomes — a strict subset that
 * proves the spike's invariants without crossing into canonical-path
 * territory.
 *
 * One case per legitimate shape, payload-typed. No flag bag.
 */
sealed interface Executed {
    /** Stage had no suspend calls; only eager additions. */
    data object Trivial : Executed

    /** Stage had at least one suspend call; outcomes are recorded in source order. */
    data class WithSuspend(val trace: List<Entry>) : Executed {
        init {
            // The ordinals in `trace` are exactly 1..N, contiguous, in order.
            // The interpreter preserves this invariant by construction; the
            // check here is a defensive fail-fast at construction time.
            trace.forEachIndexed { index, entry ->
                require(entry.ordinal == index + 1) {
                    "trace ordinals must be contiguous 1..N; got ${entry.ordinal} at index $index"
                }
            }
        }
    }

    /** One runtime-returning call, executed and recorded. */
    data class Entry(
        val ordinal: Int,
        val call: SuspendCall,
        val outcome: SuspendOutcome,
    )
}

/**
 * Interpreter boundary for the stage-scoped spike.
 *
 * Per the strict typed functional design: this object is the *only* place
 * the spike executes effects (via the [SuspendRuntimeFacade]). It does
 * NOT execute eager [StepSpec]s — those remain on the canonical path. The
 * `interpret` method is total and exhaustive over the [StageOp] ADT: add a
 * new case to [StageOp] and the compiler will fail this `when`.
 *
 * The interpreter is also a pure decision point before the effect: every
 * suspend call goes through a single typed dispatcher, no `Map<String, Any?>`.
 */
object StageScopedFrontend {

    /**
     * Interpret a [StagePlan] using the supplied [facade].
     *
     * Eager ops are accumulated untouched — they are passed to the canonical
     * durable spine unchanged. Only the suspend calls are resolved through
     * the façade and recorded.
     *
     * Returns an [Executed] trace; never throws on shape mismatch (exhaustive
     * `when`). The facade itself may throw on I/O failures; that is the
     * caller's responsibility (a future step in the spike can introduce a
     * typed rejection ADT if needed, but the current spike keeps the surface
     * intentionally narrow).
     */
    fun interpret(plan: StagePlan, facade: SuspendRuntimeFacade): Executed {
        val resolved = ArrayList<Executed.Entry>(plan.suspendCount)
        for (op in plan.ops) {
            when (op) {
                is StageOp.Eager -> {
                    // Eager ops are not interpreted by the spike; they remain
                    // on the canonical durable path. We deliberately drop them
                    // from the spike's trace — that's the whole point of the
                    // spike: it doesn't double-execute them.
                    continue
                }
                is StageOp.Suspend -> {
                    val outcome = dispatch(op.call, facade)
                    resolved += Executed.Entry(
                        ordinal = op.ordinal,
                        call = op.call,
                        outcome = outcome,
                    )
                }
            }
        }
        return if (resolved.isEmpty()) {
            Executed.Trivial
        } else {
            Executed.WithSuspend(resolved.toList())
        }
    }

    /** Typed dispatcher — every [SuspendCall] case routes here, exhaustively. */
    private fun dispatch(call: SuspendCall, facade: SuspendRuntimeFacade): SuspendOutcome =
        when (call) {
            is SuspendCall.Pwd           -> facade.pwd(call.tmp)
            is SuspendCall.ReadFile      -> facade.readFile(call.file)
            is SuspendCall.FileExists    -> facade.fileExists(call.file)
            is SuspendCall.ShReturnStdout -> facade.shReturnStdout(call.script, call.encoding)
            is SuspendCall.IsUnix        -> facade.isUnix()
        }
}
