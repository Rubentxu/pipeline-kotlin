package dev.rubentxu.pipeline.v2.events

import java.io.File

/**
 * Locates the repository root for tests that must read files from it.
 *
 * Prefers the `pipeline.repoRoot` system property, which the Gradle build sets for
 * every Test task. Falls back to walking up from the working directory so the test
 * also works when run from an IDE that does not set the property.
 *
 * This replaces absolute paths such as
 * `/var/home/<user>/Proyectos/kotlin/pipeline-kotlin`, which pinned the suite to one
 * developer's checkout and to one worktree.
 */
object TestProjectRoot {
    val dir: File by lazy { resolve() }

    private fun resolve(): File {
        System.getProperty("pipeline.repoRoot")?.let { return File(it).absoluteFile }

        var cursor: File = File(System.getProperty("user.dir", ".")).absoluteFile
        repeat(8) {
            if (File(cursor, "v2/pipeline-domain").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error(
            "could not locate the repository root: no ancestor of " +
                "${System.getProperty("user.dir")} contains v2/pipeline-domain",
        )
    }
}
