package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Path

/**
 * The remote the checkout speaks to, and the ref of the declared branch.
 *
 * **Why this type exists.** The checkout used to name the remote in TWO
 * different ways, and the two ways could name two different remotes: the probe
 * asked the URL the run declared, and the download asked git for "the remote of
 * this repository", which is the `origin` the checkout happens to have
 * configured. A workspace whose `origin` pointed somewhere else downloaded from
 * somewhere else and then tried to materialise the commit the DECLARED remote
 * had announced. This type is the single authority: every command that talks
 * to a remote names THIS url and THIS ref.
 *
 * **Why the ref has a canonical form.** `git ls-remote` matches its patterns by
 * TAIL: `git ls-remote -- <url> main` also matches `refs/tags/main`, and
 * `git ls-remote -- <url> v1` matches `refs/tags/v1` (measured with git
 * 2.56.0). A branch name that also exists as a tag would therefore be probed as
 * the tag, and the download would ask for a different thing than the probe
 * did. `refs/heads/<branch>` names the branch and only the branch, and both
 * commands use it.
 *
 * **Why the argv lives here.** The list that is guarded has to be the list that
 * is executed: a guard over a hand-built copy checks a command that was never
 * examined, and the two lists are free to drift apart the day somebody edits
 * one of them. Every argv in this class is built once and used twice — once to
 * run the command and once to check it.
 */
data class GitRemoteSelection(
    /** The remote the run declared. Every command that talks to a remote names it. */
    val url: String,
    /** The branch the run declared, as the author wrote it. */
    val branch: String,
) {

    /**
     * The ref the declared branch names, in the ONE form every command asks for.
     *
     * A branch that already names a full ref is taken as it is: the author said
     * `refs/heads/main`, and rewriting it to `refs/heads/refs/heads/main` would
     * name a ref that does not exist.
     */
    val ref: String
        get() = if (branch.startsWith("refs/")) branch else "refs/heads/$branch"

    /**
     * The name of the LOCAL branch the declared branch is materialised under.
     *
     * `git checkout -B <name>` writes a branch name, and a full ref is not a
     * branch name: git refuses `refs/heads/main` as one. The last segment is
     * the name the author meant.
     */
    val localBranch: String
        get() = if (branch.startsWith("refs/")) branch.substringAfterLast('/') else branch

    /** The argv that asks the remote for the head of the declared branch. */
    fun lsRemoteArgv(): List<String> = listOf("git", "ls-remote", "--", url, ref)

    /**
     * The argv that downloads the declared branch from the DECLARED remote.
     *
     * The remote is named here on purpose: a `git fetch` without a remote asks
     * the repository for its own `origin`, and the checkout would then download
     * from a remote the run never declared.
     */
    fun fetchArgv(workspace: Path): List<String> =
        listOf("git", "-C", workspace.toString(), "fetch", "--", url, ref)

    /** The argv that clones the declared remote and branch into [workspace]. */
    fun cloneArgv(workspace: Path): List<String> =
        listOf("git", "clone", "--branch", branch, "--", url, workspace.toString())

    /**
     * The argv that puts the DECLARED branch at [sha].
     *
     * `-B <branch> <sha>` moves the declared branch to the commit and checks it
     * out, and leaves every other branch where it was. `git reset --hard <sha>`
     * does the opposite: it moves the branch the checkout HAPPENS to be on, so
     * a workspace on `feature` would have `feature` rewritten to the remote's
     * commit and the declared branch would never materialise (measured with git
     * 2.56.0).
     *
     * **Why there is no `--force`.** With `--force`, git OVERWRITES the local
     * changes of the tree: a modified tracked file is replaced by the content of
     * the target commit and a staged change is reverted (measured with git
     * 2.56.0). The checkout was therefore destroying work nobody asked it to
     * destroy. Without `--force`, git refuses the checkout when the target would
     * overwrite a local change ("Los cambios locales de los siguientes archivos
     * serán sobrescritos por checkout … Abortando"), and that refusal is the
     * policy the product wants: the COMMAND can no longer discard anything, and
     * the decision about a tree that carries work is taken before the command
     * runs, not by its error message.
     */
    fun materialiseArgv(workspace: Path, sha: String): List<String> =
        listOf("git", "-C", workspace.toString(), "checkout", "-B", localBranch, sha)
}
