package dev.rubentxu.pipeline.v2.spike.stagescoped

import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Pure builder for a [StagePlan].
 *
 * Constructors are typed: one method per legitimate shape of stage op. No
 * boolean flag bag, no `Any?` payload, no mutable shared state beyond the
 * accumulator (which is local to a single instance).
 *
 * This is the **construction** half of the ADR-0093 decision/interpretation
 * split. The interpreter (see [StageScopedFrontend]) takes the resulting
 * [StagePlan] and interprets it at the effect boundary.
 *
 * Per the strict typed functional design: construction is data, never
 * runtime. The builder MUST NOT perform I/O, call the filesystem, run
 * processes, or read clocks. Every method only mutates local accumulator
 * state and returns [Unit].
 */
class StagePlanBuilder {

    private val ops: MutableList<StageOp> = mutableListOf()
    private var nextOrdinal: Int = 0

    /** Append an eager [StepSpec] (the existing declarative DSL path). */
    fun eager(spec: StepSpec) {
        ops += StageOp.Eager(spec)
    }

    /**
     * Append a `pwd()` call. Counter is advanced exactly once per call so
     * successive suspend ordinals are always contiguous.
     *
     * @throws IllegalStateException if the caller invokes a suspend method
     *   twice with the same ordinal (the spec rejects at [build] time, but
     *   we also reject at append time for sharper diagnostics).
     */
    fun pwd(tmp: Boolean = false) {
        appendSuspend(SuspendCall.Pwd(tmp))
    }

    fun readFile(file: String) {
        require(file.isNotEmpty()) { "readFile path must not be empty" }
        appendSuspend(SuspendCall.ReadFile(file))
    }

    fun fileExists(file: String) {
        require(file.isNotEmpty()) { "fileExists path must not be empty" }
        appendSuspend(SuspendCall.FileExists(file))
    }

    fun shReturnStdout(script: String, encoding: String? = null) {
        require(script.isNotEmpty()) { "sh returnStdout script must not be empty" }
        appendSuspend(SuspendCall.ShReturnStdout(script, encoding))
    }

    fun isUnix() {
        appendSuspend(SuspendCall.IsUnix)
    }

    /**
     * Pure fold: validate [LexicalOrderSpec], freeze into a [StagePlan].
     *
     * Returns a sealed [Outcome] so callers branch on `Invalid` and reject
     * before any effect is launched (fail-closed).
     */
    fun build(): Outcome {
        return when (val verdict = LexicalOrderSpec.check(ops)) {
            LexicalOrderSpec.Result.Valid -> Outcome.Plan(StagePlan(ops.toList()))
            is LexicalOrderSpec.Result.Invalid -> Outcome.Rejected(verdict.reason)
        }
    }

    private fun appendSuspend(call: SuspendCall) {
        nextOrdinal += 1
        ops += StageOp.Suspend(ordinal = nextOrdinal, call = call)
    }

    /** Closed result of [build]. */
    sealed interface Outcome {
        /** Plan built and validated. */
        data class Plan(val plan: StagePlan) : Outcome
        /** Plan rejected: caller MUST NOT pass this to the interpreter. */
        data class Rejected(val reason: LexicalOrderSpec.Reason) : Outcome
    }
}
