package dev.rubentxu.pipeline.v2.application.durable

import java.nio.file.Files
import java.nio.file.Path

/**
 * Recognises a source-control checkout, so the cleanup Steps can refuse to
 * destroy it.
 *
 * ## Why this lives in one place
 *
 * `core.deleteDir` ([DeleteDirOperationsAdapter]) and `core.cleanWs`
 * ([CleanWsOperationsAdapter]) both decide whether the workspace root is the
 * user's own project. When that predicate was defined twice, the two Steps
 * could drift apart and one of them would guard a deletion the other allowed.
 * The duplicated copy in [CleanWsOperationsAdapter] even documented itself as
 * "mirrors the identical helper in [DeleteDirOperationsAdapter]", which is a
 * claim about a code body that can silently stop being true. There is now a
 * single definition, so the two Steps cannot disagree by construction.
 *
 * ## Why a VCS marker is the discriminator
 *
 * The original condition was merely "the user passed `--workspace`". That is
 * false as a general rule: the compatibility corpus runs
 * `pipelinek run --workspace <@TempDir>` against a *disposable* scratch
 * directory, and fixture `11-workflow-control` calls `deleteDir()` there. The
 * over-broad interlock refused that legitimate wipe and turned a green corpus
 * fixture into `exit 1` (defect C12).
 *
 * What actually distinguishes a project is that it is under version control.
 * A scratch workspace has no marker and therefore keeps the WCL-S-001/S-002
 * wipe contract. This is a property of the *target directory*, not of how the
 * CLI was invoked.
 *
 * ## Known limitation
 *
 * A bare non-VCS project tree is NOT protected by this predicate. It reads as a
 * scratch directory and is treated as wipeable. That is recorded as follow-up
 * debt in `docs/v2/07-uat/C10_C13_SESSION_CLOSURE.md` (item 2): a safer default
 * or an explicit opt-in is wanted, but changing that default would alter the
 * wipe contract for every scratch workspace and is a behaviour change, not a
 * refactor. This refactor deliberately preserves the current behaviour.
 */
object ProjectCheckoutDetector {

    /**
     * Markers that identify a working copy. `.git` may be a directory (normal
     * clone) or a file (worktree / submodule), so existence is the test, not
     * its type.
     */
    private val VCS_MARKERS = listOf(".git", ".hg", ".svn")

    /**
     * True when [base] looks like a source-control checkout rather than a
     * disposable scratch directory.
     *
     * Checked at [base] itself and one level up, so a workspace nested inside a
     * repository (e.g. `repo/build/agent-ws`) is still recognised as belonging
     * to that project and is therefore protected.
     *
     * @param base the directory to classify. `null` is not accepted here; the
     *   caller owns the null check for the optional `--workspace` override.
     * @return true when [base] or its parent carries a VCS marker.
     */
    fun isProjectCheckout(base: Path): Boolean =
        listOf(base, base.parent).any { candidate ->
            candidate != null && VCS_MARKERS.any { marker ->
                Files.exists(candidate.resolve(marker))
            }
        }
}
