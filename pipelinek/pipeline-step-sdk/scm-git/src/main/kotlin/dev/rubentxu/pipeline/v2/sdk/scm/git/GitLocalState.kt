package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Path

/**
 * The state of the TRACKED content of a working tree, relative to the commit the
 * tree holds.
 *
 * **Why the state is a fact of its own.** What the checkout DID (clone, update, leave
 * the tree alone) and the state it FOUND the tree in are two different facts. A
 * checkout can leave a tree alone because it already holds the declared commit, and
 * the same tree can carry changes that are not the run's. The first is an idempotency
 * of the COMMIT; the second is the absence of an idempotency of the TREE. A reader
 * that learns only the first reads a "clean checkout" over a dirty tree.
 *
 * **What each state means.**
 * - [CLEAN]: the tracked content of the tree IS the commit the tree holds.
 * - [MODIFIED]: the tracked content of the tree DIFFERS from the commit the tree
 *   holds. These are the changes that a forced checkout would destroy.
 * - [UNKNOWN]: the state is not known. It is the state of a payload encoded before
 *   this field existed, and the state of a tree whose state could not be read.
 *
 * **What is NOT part of the state.** Untracked files are not modifications of the
 * tracked content, and a checkout that does not force never overwrites one unless the
 * destination carries the same path — and git refuses that case on its own. A tree
 * full of untracked files is [CLEAN] here: the state says whether the tracked
 * content matches the commit, and says nothing else.
 */
enum class GitLocalState {

    /** The tracked content of the tree IS the commit the tree holds. */
    CLEAN,

    /** The tracked content of the tree DIFFERS from the commit the tree holds. */
    MODIFIED,

    /** The state is not known: it was never read, or it was never published. */
    UNKNOWN,

    ;

    companion object {

        /**
         * The argv that reads the state of the tracked content.
         *
         * `--porcelain` is the machine-readable format of the status, and
         * `--untracked-files=no` leaves the untracked files out of it: they are not
         * modifications of the tracked content, and a status that listed them would
         * say "the tree differs from the commit" about a tree whose tracked content
         * does not differ at all.
         */
        fun statusArgv(workspace: Path): List<String> =
            listOf("git", "-C", workspace.toString(), "status", "--porcelain", "--untracked-files=no")

        /**
         * The state the output of [statusArgv] declares.
         *
         * The porcelain format prints one line per difference and NOTHING when there
         * is none, so an empty output is the clean tree and a non-empty one is a tree
         * whose tracked content differs from the commit it holds.
         */
        fun stateOf(statusOutput: String): GitLocalState =
            if (statusOutput.isBlank()) CLEAN else MODIFIED
    }
}
