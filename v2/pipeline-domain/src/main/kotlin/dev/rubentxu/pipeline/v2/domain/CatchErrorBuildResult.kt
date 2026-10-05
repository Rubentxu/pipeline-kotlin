package dev.rubentxu.pipeline.v2.domain

/**
 * P3-E E4 — the CLOSED set of results a `catchError` scope may declare.
 *
 * ## Why this type exists at all
 *
 * The decision over this value used to be a `when` on a raw `String` whose final arm was
 *
 * ```kotlin
 * "FAILURE" -> re-throw outward
 * "SUCCESS" -> suppress, continue clean
 * else      -> suppress, continue UNSTABLE
 * ```
 *
 * `UNSTABLE` is the correct reading of exactly one token, `"UNSTABLE"`. Written as `else`
 * it became the reading of *everything else* — `"FALURE"`, `"success"`, `""`, `"WAT"` — and
 * every one of those SUPPRESSES the failure the scope was installed to catch. A pipeline
 * that misspelled its own error handling did not fail loudly; it went unstable and carried
 * on, which is the worst available outcome because nothing is reported.
 *
 * That is a fail-open on the run's control flow, and it was only ever closed by hand: three
 * string literals matched in one place, and the arm meant for the third one swallowed the
 * rest of the world.
 *
 * ## Why `parse` returns null instead of defaulting
 *
 * The whole defect was a default. A parser that turns an unrecognised token into `Unstable`
 * would reproduce it exactly. So [parse] is total over the vocabulary and **fails closed by
 * returning null**, and the caller is required to turn that null into an abort. The wire
 * spellings stay in upper case, exactly as they have always travelled.
 *
 * @see CatchErrorBuildResult.parse
 */
sealed interface CatchErrorBuildResult {

    /** The caught failure is suppressed and the run continues clean. */
    data object Success : CatchErrorBuildResult

    /** The caught failure is suppressed, but the run is marked unstable. */
    data object Unstable : CatchErrorBuildResult

    /** The failure is NOT caught here; it propagates to the enclosing scope. */
    data object Failure : CatchErrorBuildResult

    companion object {

        /**
         * The historical wire spellings, in the case they have always used.
         *
         * `buildResult` in `CatchErrorTriggered` travels upper case while `RunFinished.outcome`
         * travels lower case. That asymmetry is history, not a mistake to normalise away.
         */
        private val SUPPORTED: Map<String, CatchErrorBuildResult> = mapOf(
            "SUCCESS" to Success,
            "UNSTABLE" to Unstable,
            "FAILURE" to Failure,
        )

        /** Every spelling this runtime accepts, for diagnostics and validation messages. */
        val supportedTokens: Set<String> = SUPPORTED.keys

        /**
         * The single boundary where a declared `buildResult` becomes typed.
         *
         * Returns **null** for anything outside [supportedTokens]. The caller MUST treat that
         * as a configuration error and fail closed — never as [Unstable].
         */
        fun parse(token: String): CatchErrorBuildResult? = SUPPORTED[token]
    }
}
