package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path

object FitnessPaths {

    /**
 * This object is the single traversal behind the architecture censuses, so it also decides what
 * those censuses MEASURE.
 *
 * The walk used to be unpruned and swept in every `build/` and `.gradle/` tree. Build outputs are
 * not source: their Kotlin content depends on which tasks ran, what is cached, and what a previous
 * test wrote to disk — never on the code under fitness. A census that counts them is measuring the
 * last build, which is HARNESS FIDELITY LAW §4 ("a harness MUST NOT be able to make its own subject
 * look broken") in its exact form. Measured 2026-10-18: 1674 Kotlin files were walked, of which 41
 * lived under `build/` or `.gradle/`, and none of those 41 carried `ProcessBuilder(` at that
 * moment — so the hole was LATENT rather than firing. A latent hermeticity hole is still a defect:
 * it decides red or green by build state instead of by source.
 */

/**
     * Directories that are NEVER source, wherever they appear. Tooling metadata and VCS state: a
     * worktree is a second copy of the repository, and counting it would double every census and
     * let one branch's code answer for another's.
     */
    private val ALWAYS_NON_SOURCE = setOf(".git", ".gradle", ".worktrees")

    fun v2Root(): Path {
        val override = System.getProperty("fitness.v2.root", "v2")
        return Path.of(override).toAbsolutePath().normalize()
    }

    /**
     * True when [path] lies in a tree that is not source, decided by SHAPE rather than by name.
     *
     * Why a shape rule and not "any directory called `build`": a build output is a `build`
     * directory sitting at a MODULE root, so it always appears BEFORE the `src` that holds code.
     * A package legitimately named `build` — `…/src/main/kotlin/company/build/Builder.kt` — is
     * real source and appears AFTER it. Pruning on the bare name deleted that package, which
     * `FitnessPathsScopingTest` caught as an empty walk: a census that prunes its own subject is
     * worse than one that never pruned, because it reports green while measuring nothing.
     *
     * The comparison is made against the path RELATIVE TO [root], never against the absolute
     * path. An absolute comparison would let any `build`-named directory in the checkout prefix
     * prune the entire walk, and every census would silently pass on an empty set.
     */
    private fun isUnderNonSourceDir(root: Path, path: Path): Boolean {
        val relative = try {
            root.relativize(path)
        } catch (_: IllegalArgumentException) {
            // Different roots carry no relative path, so the walk cannot have descended through
            // this file. Refusing to prune is the safe direction: a file the caller cannot place
            // is not silently dropped.
            return false
        }
        val segments = relative.iterator().asSequence().map { it.toString() }.toList()
        if (segments.any { it in ALWAYS_NON_SOURCE }) return true
        val srcAt = segments.indexOf("src")
        return segments.withIndex().any { (index, segment) ->
            segment == "build" && (srcAt < 0 || index < srcAt)
        }
    }

    fun walkKotlinFiles(root: Path): List<Path> {
        return Files.walk(root).use { stream ->
            stream.filter { it.toFile().isFile &&
                !isUnderNonSourceDir(root, it) &&
                (it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".kts")) }
                .toList()
        }
    }

    fun walkBuildFiles(root: Path): List<Path> {
        return Files.walk(root).use { stream ->
            stream.filter { it.toFile().isFile &&
                !isUnderNonSourceDir(root, it) &&
                (it.fileName.toString() == "build.gradle.kts" || it.fileName.toString().endsWith(".toml")) }
                .toList()
        }
    }
}
