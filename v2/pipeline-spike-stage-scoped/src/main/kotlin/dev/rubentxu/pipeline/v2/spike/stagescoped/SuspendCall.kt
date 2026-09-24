package dev.rubentxu.pipeline.v2.spike.stagescoped

/**
 * Suspend-call ADT.
 *
 * One case per legitimate shape of runtime-returning call. Each case carries
 * the payload it actually needs (no boolean+nullable pair, no string mode,
 * no `Map<String, Any?>`). This mirrors the BodyExecutionPolicy lesson from
 * docs/v2/07-uat/B10_W1B_BODY_EXECUTION_POLICY_RECEIPT.md and is the
 * Haskell-inspired way to make invalid combinations unrepresentable.
 */
sealed interface SuspendCall {
    /** Workspace path; `tmp = true` selects the deterministic temp variant. */
    data class Pwd(val tmp: Boolean) : SuspendCall

    /** Read a workspace file. */
    data class ReadFile(val file: String) : SuspendCall

    /** Test a workspace file's existence. */
    data class FileExists(val file: String) : SuspendCall

    /**
     * Shell invocation with `returnStdout = true`. Encoding is `null` when the
     * caller accepts the default.
     */
    data class ShReturnStdout(val script: String, val encoding: String?) : SuspendCall

    /** OS-conditional query: true on POSIX, false otherwise. */
    data object IsUnix : SuspendCall
}

/**
 * Outcome shape expected by a given [SuspendCall]. Pure: returns the
 * declared type category, not the actual runtime value. The interpreter
 * fills the value in via the facade.
 */
sealed interface SuspendOutcome {
    data class StringOutcome(val value: String) : SuspendOutcome
    data class BooleanOutcome(val value: Boolean) : SuspendOutcome
}

/**
 * Pure decision: what is the typed category of a [SuspendCall]?
 *
 * This is what makes the interpreter exhaustively typed — adding a case
 * here forces every consumer to update its `when` or the compile fails.
 */
fun SuspendCall.expectedOutcome(): SuspendOutcome = when (this) {
    is SuspendCall.Pwd           -> SuspendOutcome.StringOutcome(value = "")
    is SuspendCall.ReadFile      -> SuspendOutcome.StringOutcome(value = "")
    is SuspendCall.ShReturnStdout -> SuspendOutcome.StringOutcome(value = "")
    is SuspendCall.FileExists    -> SuspendOutcome.BooleanOutcome(value = false)
    is SuspendCall.IsUnix        -> SuspendOutcome.BooleanOutcome(value = false)
}
