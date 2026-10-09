package dev.rubentxu.pipeline.v2.sdk.files

/**
 * What a caller is permitted to do to a workspace ROOT, as a closed set of the
 * three states that actually exist.
 *
 * ## Why this is a type and not a `Boolean`
 *
 * Both workspace executors used to take `protectWorkspaceRoot: Boolean = false`.
 * That is the shape AGENTS.md §5/§8 forbids: one bit standing in for a
 * three-state reality, where the third state has no spelling. The three states:
 *
 * ```text
 * ScratchOwned      PipelineK created this directory and owns its lifecycle
 * UserOwned         the user pointed --workspace at their own project
 * DecidedElsewhere  nobody has resolved ownership yet
 * ```
 *
 * `Boolean` admits `ScratchOwned` and `UserOwned` and makes `DecidedElsewhere`
 * unrepresentable, so every caller had to answer a question it does not own by
 * picking one of two bits. Two adapters each re-derived the answer from the
 * lease and got it right; a third that forgot would wipe a user's project with
 * no test to stop it, because the failure is only observable in the wiring.
 *
 * The type fixes that at construction: a caller that has not resolved ownership
 * cannot name "allowed", and a caller cannot express a decision on behalf of a
 * lease it did not inspect.
 *
 * ## Why the DSL default is NOT changed by this
 *
 * `StepSpec.DeleteDir(path = ".")` keeps its default. `deleteDir()` with no
 * argument is a Jenkins contract, pinned by
 * `FArchL7JenkinsVerbatimSignatureReflectionTest` (WCL-S-008, FIL-ALL-001), and
 * removing it would break source compatibility for every existing pipeline for
 * no safety gain: `UserOwned` already refuses the root before any effect. The
 * defect was never the default's existence, it was that the default's outcome
 * was decided by a bit the caller could pass wrong.
 *
 * See `docs/v2/07-uat/C8_WORKSPACE_ROOT_DELETION_RECEIPT.md`.
 *
 * @param permitsRootWipe `true` only for a directory PipelineK created.
 *   `DecidedElsewhere` is false on purpose: an unresolved ownership question
 *   must not resolve toward destruction.
 */
enum class RootDestruction(
    val permitsRootWipe: Boolean,
) {
    /** PipelineK's own scratch. Wiping it is the point of the Step. */
    ScratchOwned(permitsRootWipe = true),

    /** A user-owned checkout pointed at by `--workspace`. Never wiped. */
    UserOwned(permitsRootWipe = false),

    /**
     * Ownership has not been resolved. Treated exactly like [UserOwned].
     *
     * This is the case the old `Boolean` could not express, and the reason it
     * is safe: the absence of a decision fails closed toward preservation.
     */
    DecidedElsewhere(permitsRootWipe = false),
    ;

    companion object {
        /**
         * Derive the intent from a lease ownership answer.
         *
         * Kept here, beside the enum, so the mapping is stated once. The
         * adapters used to re-derive it inline, and two derivations of one rule
         * are two rules waiting to diverge.
         */
        fun fromUserOwned(isUserOwned: Boolean): RootDestruction =
            if (isUserOwned) UserOwned else ScratchOwned
    }
}
