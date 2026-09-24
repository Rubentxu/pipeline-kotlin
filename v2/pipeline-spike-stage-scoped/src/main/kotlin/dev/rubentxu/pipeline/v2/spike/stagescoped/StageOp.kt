package dev.rubentxu.pipeline.v2.spike.stagescoped

import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Stage-op ADT.
 *
 * Every case carries the payload it actually needs; the union is closed and
 * total. Adding a new kind of operation requires updating every `when` over
 * [StageOp] — that's intentional and is the Haskell-inspired contract.
 *
 * Pure data: NO I/O, NO facade, NO clock. Construction produces a value;
 * execution is the interpreter's job.
 */
sealed interface StageOp {
    /** A declaratively constructed StepSpec (the existing eager pipeline path). */
    data class Eager(val spec: StepSpec) : StageOp

    /**
     * A runtime-returning call captured inside a stage body. The [ordinal]
     * is monotonic and contiguous within a single stage plan (see
     * [LexicalOrderSpec]); it is part of the durable identity.
     *
     * [call] is a typed ADT — no flag bag, no `Map<String, Any?>`.
     */
    data class Suspend(
        val ordinal: Int,
        val call: SuspendCall,
    ) : StageOp
}
