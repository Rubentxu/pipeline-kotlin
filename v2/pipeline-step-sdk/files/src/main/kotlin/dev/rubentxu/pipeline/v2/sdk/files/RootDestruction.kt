package dev.rubentxu.pipeline.v2.sdk.files

import dev.rubentxu.pipeline.v2.domain.workspace.DestructiveAuthorization

/**
 * What a caller is permitted to do to a workspace ROOT, as a closed set of the
 * three states that actually exist.
 *
 * ## Why this is a type and not a `Boolean`
 *
 * Both workspace executors used to take `protectWorkspaceRoot: Boolean = false`.
 * That is the shape this codebase forbids: one bit standing in for a
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
 * ## Why this exposes no `permitsRootWipe: Boolean`
 *
 * A property collapsing the three states back into one bit would reinstate the
 * exact defect under a new name: the executors would branch on the bit, the
 * [DecidedElsewhere] case would be indistinguishable from [UserOwned] at the
 * call site, and adding a fourth state would mean re-deciding what the bit means.
 * Consumers `when`-match the cases instead, so a new case is a compile error in
 * every consumer until someone states what it does.
 *
 * The consequence is that [DecidedElsewhere] is not "UserOwned with extra steps":
 * it is the case that has no verdict yet, and it is treated conservatively
 * because an unanswered ownership question must not resolve toward destruction.
 *
 * ## Why the DSL default is NOT changed by this
 *
 * `StageScope.deleteDir(path = ".")` keeps its default. `deleteDir()` with no
 * argument is a Jenkins contract, and what holds it in place in this tree is
 * the tracked scenario `v2/examples/control-flow/11-workflow-control.pipeline.kts`,
 * which calls `deleteDir()` bare: drop the default and that program stops
 * compiling, so stage 3 of `scripts/verify-all.sh` goes red. An earlier
 * architecture fitness used to pin this and was deleted by the RESET; the
 * guarantee moved to a different custodian, so the citation moved with it.
 *
 * Removing the default would break source compatibility for every existing
 * pipeline for no safety gain: [UserOwned] already refuses the root before any
 * effect. The defect was never the default's existence, it was that the
 * default's outcome was decided by a bit the caller could pass wrong.
 */
enum class RootDestruction {
    /** PipelineK's own scratch. Wiping it is the point of the Step. */
    ScratchOwned,

    /** A user-owned checkout pointed at by `--workspace`. Never wiped. */
    UserOwned,

    /**
     * Ownership has not been resolved. Treated exactly like [UserOwned].
     *
     * This is the case the old `Boolean` could not express, and the reason it
     * is safe: the absence of a decision fails closed toward preservation.
     */
    DecidedElsewhere,
    ;

    companion object {
        /**
         * The intent a lease's verdict authorises.
         *
         * Kept here, beside the enum, so the mapping is stated once. Both
         * adapters used to re-derive it inline from the SAME
         * [DestructiveAuthorization], and two derivations of one rule are two
         * rules waiting to diverge.
         *
         * The parameter is the authorisation itself rather than a `Boolean` of
         * it, for the reason this enum exists at all: a bit cannot express
         * "nobody has decided", so a caller holding an unanswered question had to
         * pick one of two answers. [DecidedElsewhere] stays reachable through the
         * executors' default and nowhere else — which is the honest reading of an
         * ownership question nobody has asked yet.
         *
         * [DecidedElsewhere] has no branch here, and that is deliberate rather than
         * an oversight: an authorisation is an ANSWER. A caller that has one
         * cannot also be in the undecided case, and manufacturing a branch for it
         * would reintroduce the bit this type removed.
         */
        fun from(authorization: DestructiveAuthorization): RootDestruction =
            when (authorization) {
                is DestructiveAuthorization.Permitted -> ScratchOwned
                is DestructiveAuthorization.Refused -> UserOwned
            }
    }
}
