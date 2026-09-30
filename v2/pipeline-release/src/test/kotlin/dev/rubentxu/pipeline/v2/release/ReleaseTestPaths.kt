package dev.rubentxu.pipeline.v2.release

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates the `v2/` build root for tests that need to read real build output.
 *
 * Deliberately independent of the fitness module's `ScannerSupport`: the
 * release contract must be verifiable without depending on the architecture
 * test harness, because the build itself runs these checks. Resolution is
 * overridable with `-Prelease.v2.root=...` so CI can point at a different
 * build tree without editing code.
 */
internal object ReleaseTestPaths {

    fun v2Root(): Path {
        System.getProperty("release.v2.root")?.let { return Paths.get(it).toAbsolutePath() }
        // This module lives at <v2>/pipeline-release/, so the parent is <v2>/.
        val here = Paths.get("").toAbsolutePath()
        val parent = here.parent ?: here
        return if (Files.isDirectory(parent.resolve("pipeline-application"))) parent else here
    }
}
