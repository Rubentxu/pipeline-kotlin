package dev.rubentxu.pipeline.v2.domain

/**
 * Classification of failure kinds for error reporting.
 * Used by [dev.rubentxu.pipeline.v2.dsl.StepSpec.Error] step type.
 */
enum class FailureKind {
    /** Infrastructure-level failure (network, disk, etc.) */
    INFRASTRUCTURE,
    /** Network-level failure */
    NETWORK,
    /** Script-level failure (command exited non-zero) */
    SCRIPT,
    /** User-level failure (invalid input, etc.) */
    USER,
    /** Timeout exceeded */
    TIMEOUT,
    /** A plugin could not execute its declared contract. */
    PLUGIN,
    /** The IR payload does not conform to the canonical `dsl-v1` contract */
    SCHEMA,
    /** A persisted operation cannot be safely replayed by this runtime. */
    REPLAY_COMPATIBILITY,
    /** An invariant of the execution engine was violated. */
    ENGINE,
    /** Unknown failure */
    UNKNOWN,
    ;

    companion object {

        /**
         * P3-E E6b — the single boundary where a declared token becomes typed.
         *
         * The map is derived from [entries] rather than written out again: a second list of
         * the same vocabulary is a second authority, and the two drift the first time someone
         * adds a constant.
         *
         * Returns **null** outside the vocabulary, and deliberately does NOT fall back to
         * [UNKNOWN]: a default here would turn a typo into a silent classification, which is
         * the exact defect the DSL bridge exists to refuse.
         */
        private val BY_TOKEN: Map<String, FailureKind> = entries.associateBy { it.name }

        /** Every token this runtime accepts, for diagnostics on a refused spelling. */
        val supportedTokens: Set<String> = BY_TOKEN.keys

        /** Total over the vocabulary; **null** for anything else. Never defaults. */
        fun parse(token: String): FailureKind? = BY_TOKEN[token]
    }
}
