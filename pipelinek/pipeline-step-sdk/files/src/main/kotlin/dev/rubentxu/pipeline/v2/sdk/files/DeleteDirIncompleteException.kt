package dev.rubentxu.pipeline.v2.sdk.files

import java.io.IOException
import java.nio.file.Path

/**
 * The deleteDir deletion did not converge: entries still exist under the
 * target after the walk.
 *
 * The failure is typed and carries the truth of the run so the caller does not
 * have to guess it from a message:
 *
 *  - [survivors] are the entries the filesystem held when the post-deletion
 *    verification read the target back — a refused removal, an entry that came
 *    back under a delete that reported success, or content created during the
 *    window;
 *  - [deletedCount] is what this attempt really removed, the progress the next
 *    attempt converges from.
 *
 * No `.deleted` marker is written for a run that fails this way: a receipt of
 * cleanliness the verification refuted is a false success. Once the cause is
 * resolved, re-running the step deletes the rest.
 *
 * @property target the declared path whose deletion was attempted
 * @property survivors entries still present under [target] after verification
 * @property deletedCount entries the filesystem confirmed gone this run
 */
class DeleteDirIncompleteException(
    val target: Path,
    val survivors: List<Path>,
    val deletedCount: Int,
) : IOException(
    "deleteDir left ${survivors.size} survivor(s) under '$target' " +
        "($deletedCount entries were deleted); resolve the cause and run it again",
)
