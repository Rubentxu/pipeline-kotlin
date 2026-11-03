package dev.rubentxu.pipeline.v2.scripting

/**
 * Where the executable form of a pipeline script came from.
 *
 * ## Why this is a domain enum and not a boolean
 * A boolean ("was it cached?") can only answer yes or no, and the answer this
 * vocabulary has to give is three-valued: the artifact was **compiled** here,
 * it was **reused** from a store an earlier run wrote, or the record **does not
 * say**. The third value is not a flavour of the first two: it is what a durable
 * row written before this field existed decodes to, and a vocabulary that cannot
 * say "I do not know" would have to invent one of the other two — which is how a
 * record starts lying about its own past.
 *
 * The same three-valued shape already exists in the failure vocabulary
 * ([dev.rubentxu.pipeline.v2.domain.FailureKind]), and the precedent is the
 * point: a missing field decodes to a sentinel, never to an invented value.
 *
 * ## Why it lives in the scripting API and not in the events module
 * The event hierarchy already depends on this module ([CacheKey] travels in
 * `CompilationFinished`), and the module that PRODUCES the origin — the
 * scripting adapter — depends on the events module, not the other way round.
 * A vocabulary the producer needs and the consumer needs therefore belongs
 * below both of them, or it would have to travel twice.
 */
enum class ScriptOrigin {

    /**
     * The script was compiled from its source during this run.
     * Nothing was reused: the compiler ran.
     */
    COMPILED,

    /**
     * A compilation stored by an earlier run was reused; the source was NOT
     * compiled during this run.
     */
    REUSED,

    /**
     * The record does not say where the artifact came from.
     *
     * This is the sentinel for rows written before the origin was recorded. It
     * is also the honest answer for a word this build does not know: a spelling
     * nobody can interpret is not a fact, and turning it into `COMPILED` would
     * make an unreadable record claim that a compiler ran.
     */
    UNKNOWN,
    ;

    companion object {

        /**
         * The single boundary where a declared token becomes typed.
         * The map is derived from [entries] rather than written out again: a second
         * list of the same vocabulary is a second authority, and the two drift
         * the first time someone adds a constant.
         * Returns **null** outside the vocabulary, and deliberately does NOT fall back to
         * [UNKNOWN]: a default here would turn a typo into a silent classification.
         */
        private val BY_TOKEN: Map<String, ScriptOrigin> = entries.associateBy { it.name }

        /** Every token this runtime accepts, for diagnostics on a refused spelling. */
        val supportedTokens: Set<String> = BY_TOKEN.keys

        /** Total over the vocabulary; **null** for anything else. Never defaults. */
        fun parse(token: String): ScriptOrigin? = BY_TOKEN[token]

        /**
         * The origin of a record that carries none, or carries a word this build
         * does not know.
         *
         * This is the rule the durable reader applies, and it lives HERE so that
         * every reader of a stored origin applies the same one. Absence and
         * ignorance both mean "not recorded", and neither is allowed to become
         * an invented [COMPILED].
         */
        fun parseOrUnknown(token: String?): ScriptOrigin = token?.let { BY_TOKEN[it] } ?: UNKNOWN
    }
}
