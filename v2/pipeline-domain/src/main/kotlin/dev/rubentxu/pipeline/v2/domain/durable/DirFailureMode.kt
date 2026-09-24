package dev.rubentxu.pipeline.v2.domain.durable

/**
 * WU-RP-053-DIR-FAILURE-MODE: typed ADT for the failure-handling mode of a
 * `dir(...)` block.
 *
 * Mirrors Jenkins `dir()` semantics: the cwd is restored when the block exits,
 * INCLUDING ON EXCEPTION. The default is [Contained] so the pipeline continues
 * with the next sibling statement after a failed `dir(...)` block — the
 * failure is scoped to the block, not the whole stage.
 *
 * ## Cases
 *
 * - [Contained]: a StepFailed inside the block is captured. The `DirExited`
 *   event still fires (cwd restored), a typed `BlockFailureContained` event is
 *   emitted for observability, and the stage loop proceeds to the next sibling
 *   step. The stage as a whole only fails if a subsequent step fails or the
 *   outermost stage scope surfaces the contained failures via the reducer.
 *
 * - [AbortStage]: legacy behaviour preserved as an explicit opt-in. A StepFailed
 *   inside the block propagates and aborts the stage as before. Equivalent to
 *   pre-fix PipelineK semantics.
 *
 * ## Why an ADT and not a boolean
 *
 * Per AGENTS.md §STRICT TYPED FUNCTIONAL DESIGN, mutually-exclusive modes
 * belong in a sealed ADT, not a flag bag. The cases carry meaningfully
 * different runtime behaviour and observability events; collapsing them into
 * a single constructor with a discriminator would hide both.
 *
 * ## Default semantics
 *
 * Default in DSL: [Contained]. Jenkins-faithful. Operators that need the
 * legacy abort-the-stage semantics must pass `mode = DirFailureMode.AbortStage`
 * explicitly at the `dir(...)` call site.
 *
 * @see dev.rubentxu.pipeline.v2.application.durable.BlockShellScope.Directory
 */
sealed interface DirFailureMode {
    /** Jenkins default: failure inside the block is contained; pipeline continues. */
    data object Contained : DirFailureMode

    /** Legacy semantics: failure inside the block aborts the stage. */
    data object AbortStage : DirFailureMode

    companion object {
        /**
         * Jenkins-parity default: a `dir(...)` block without an explicit
         * failure mode is contained — the cwd is restored and the pipeline
         * continues with the next sibling statement.
         */
        fun default(): DirFailureMode = Contained
    }
}

/**
 * Convenience predicates over the closed [DirFailureMode] ADT.
 *
 * Kept outside the sealed interface so the ADT remains purely declarative
 * data and so this predicate stays a free-standing pure function (it cannot
 * mutate state and it cannot fail; it is exhaustively decidable on the two
 * ADT cases).
 */
val DirFailureMode.isAborting: Boolean
    get() = this is DirFailureMode.AbortStage

/** Human-readable name used in `BlockFailureContained` events and receipts. */
val DirFailureMode.name: String
    get() = when (this) {
        DirFailureMode.Contained -> "Contained"
        DirFailureMode.AbortStage -> "AbortStage"
    }
