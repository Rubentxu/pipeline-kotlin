package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Replay policy DECLARED by a durable operation.
 *
 * This enum is the declared INPUT to the replay decision, not the decision itself.
 * The single authority that decides is the ordered table in
 * `dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy.decide`
 * (ADR-0103 D1: "the table is normative and its ORDER is the contract"), which is why the
 * rows below describe the policy's meaning WITHIN that table rather than claiming to be it.
 *
 * ## The names do not all mean what they say, and that is deliberate
 *
 * [RERUN] is the load-bearing example, and ADR-0103 D2a fixed the CONTRACT while D2b deferred
 * the RENAME, because the enum name is inside the fingerprint hash and renaming it would change
 * the durable identity of existing history. So the name stays and the meaning is corrected here.
 * AGENTS.md carries the same warning for the decision type: do not read `RERUN` as "this is a
 * re-run".
 */
enum class ReplayPolicy {
    /**
     * Reuse a reusable completion only when the effect set is PURELY read-only; otherwise
     * re-execute.
     *
     * The effect-awareness is not an optimisation, it is the contract (table rule 5): a mixed set
     * containing `WRITES_WORKSPACE` or `EXECUTES_SUBPROCESS` must NOT memoise even when `READ_ONLY`
     * is also declared, or the engine would skip re-writing workspace state. This is why the test
     * is `purely read-only` and not `contains(READ_ONLY)`.
     */
    MEMOIZED,

    /**
     * REUSE, not re-execution: a reusable completion short-circuits to `SKIP`.
     *
     * Table rule 4: `RERUN + reusable(SUCCEEDED || UNSTABLE) -> SKIP`. The comment on that rule is
     * the authority for this sentence: "The name `RERUN` means the opposite of what it says: this
     * is reuse, not re-execution." Anything that is NOT a reusable completion, and every fresh
     * invocation, still executes (rules 1 and 6).
     */
    RERUN,

    /**
     * Constrains the re-execution of durable HISTORY, and nothing else.
     *
     * A fresh invocation executes (table rule 1: admission is never suppressed by the replay
     * layer). With history present the decision is the typed `ReplayDecision.ABORT` (rule 3), which
     * is a fail-closed outcome the caller interprets, NOT an exception thrown from here.
     */
    NEVER,
}
