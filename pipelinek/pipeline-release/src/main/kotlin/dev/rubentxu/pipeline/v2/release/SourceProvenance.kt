package dev.rubentxu.pipeline.v2.release

/**
 * Source provenance integrity — P0.5.
 *
 * The defect this exists to prevent is a *lie of address*. The candidate
 * manifest records a `sourceCommit`, and that value is read with
 * `git rev-parse HEAD` while the distribution ZIP is compiled from the working
 * tree. Those are two different sources of truth:
 *
 * ```text
 * git rev-parse HEAD   -> the COMMIT identity recorded in the manifest
 * distZip              -> the WORKING TREE, i.e. the actual BYTES
 * ```
 *
 * Edit a tracked file, build, and commit afterwards, and the manifest names a
 * commit that would compile *different bytes*. The harness then checks out the
 * named commit, rebuilds, obtains a different digest, and concludes the
 * candidate is irreproducible — with no visible cause. That is precisely the
 * 0.43.0 family of defect arriving through a different door, and it is why this
 * is a fail-closed law rather than a warning.
 *
 * The law: **the source commit recorded in a candidate MUST identify the exact
 * tree that produced the candidate's bytes.** A dirty tree, or a commit that is
 * not the one the bytes came from, is a refusal, not a caveat.
 *
 * This file is the pure decider. It takes observed facts and returns a verdict;
 * it performs no I/O and knows nothing about Git. [SourceProvenanceProbe] is
 * the adapter that gathers the facts, and the Gradle task is the interpreter
 * that fails the build on [SourceProvenanceVerdict.Refused].
 */
object SourceProvenance {

    /**
     * Decide whether the recorded commit may be sealed into a candidate.
     *
     * The cases are mutually exclusive and exhaustive on purpose. `Clean` and
     * `Unknown` are distinct because "I could not check" is not "it is fine" —
     * collapsing them is how a probe outage becomes a silent pass.
     */
    fun evaluate(facts: SourceProvenanceFacts): SourceProvenanceVerdict =
        when {
            facts.headCommit == PROVENANCE_UNKNOWN ->
                SourceProvenanceVerdict.Unverifiable(
                    "the source commit could not be read from version control " +
                        "(git absent, or this is an exported source tree). A candidate whose " +
                        "provenance cannot be observed MUST NOT be sealed: the harness has " +
                        "nothing to check out and no way to reproduce the bytes.",
                )

            facts.modifiedTrackedFiles > 0 || facts.stagedButUncommitted > 0 ->
                SourceProvenanceVerdict.Refused(
                    "the working tree carries uncommitted changes to tracked files " +
                        "(${facts.modifiedTrackedFiles} modified, " +
                        "${facts.stagedButUncommitted} staged). The manifest would name commit " +
                        "${facts.headCommit} while the distribution ZIP was compiled from " +
                        "content that commit does not contain, so the recorded provenance " +
                        "would be false. Commit the work, or build from a clean tree.",
                )

            facts.builtFromCommit != null && facts.builtFromCommit != facts.headCommit ->
                SourceProvenanceVerdict.Refused(
                    "the distribution ZIP was built from commit ${facts.builtFromCommit} but the " +
                        "head of the branch is now ${facts.headCommit}. Re-run the build so the " +
                        "recorded commit and the compiled bytes describe the same tree.",
                )

            else -> SourceProvenanceVerdict.Clean(facts.headCommit)
        }

    /**
     * Marker used when version control cannot answer. It is deliberately not
     * the empty string: an empty commit would read as a claim about a build
     * nobody can trace, which is exactly the claim this law forbids.
     */
    const val PROVENANCE_UNKNOWN: String = "unknown"
}

/**
 * The observed facts about version control at materialization time. A
 * snapshot of what was true when the bytes were produced, not a live query.
 */
data class SourceProvenanceFacts(
    /** `git rev-parse HEAD`, or [SourceProvenance.PROVENANCE_UNKNOWN]. */
    val headCommit: String,
    /** Count of tracked files differing from HEAD in the working tree. */
    val modifiedTrackedFiles: Int,
    /** Count of tracked files staged in the index but not yet committed. */
    val stagedButUncommitted: Int,
    /**
     * The commit the distribution ZIP was actually compiled from, when the
     * build can observe it. `null` means "not independently observable", which
     * is different from "verified to be HEAD": the clean-tree law above is what
     * carries the guarantee in that case.
     */
    val builtFromCommit: String? = null,
)

/** Verdict of the source provenance law. */
sealed interface SourceProvenanceVerdict {

    /** The tree is clean and the recorded commit identifies the built bytes. */
    data class Clean(val commit: String) : SourceProvenanceVerdict

    /** The tree is dirty, or the bytes came from a different commit. Refuse. */
    data class Refused(val reason: String) : SourceProvenanceVerdict

    /**
     * Version control could not be consulted. Distinct from [Clean] on
     * purpose: an unanswerable question is not a passing one.
     */
    data class Unverifiable(val reason: String) : SourceProvenanceVerdict

    /** Operator-facing diagnostic, carried verbatim into the build log. */
    fun render(): String = when (this) {
        is Clean -> "source provenance VERIFIED: clean tree at $commit"
        is Refused -> "source provenance REFUSED: $reason"
        is Unverifiable -> "source provenance UNVERIFIABLE: $reason"
    }
}
