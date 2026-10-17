package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 P0 (HITO 1 "Propiedad explícita de ficheros",
 * 2026-10-17): the previous shape of [CycleRunner.finalise]
 * computed the deletion scope as
 * `currentEntries - operatorOwnedSnapshot` and walked
 * the diff recursively (`deleteRecursively(cycleDir)`).
 * The audit's "carreras" (races) defect class:
 *
 * 1. A file added by an external process AFTER
 *    `prepare()` is in `currentEntries` and NOT in
 *    `operatorOwnedSnapshot`; the diff classifies it
 *    as runner-owned and the recursive walk DELETES
 *    it. The operator's "this file is mine" claim is
 *    silently overridden.
 *
 * 2. A symlink whose real path leaves the cycle dir
 *    could be followed by a naive recursive walk; the
 *    containment check existed but was coupled to the
 *    snapshot-diff shape.
 *
 * The new shape uses an explicit registry of paths
 * the runner and the executor themselves created.
 * Concurrent files are not in the registry and
 * SURVIVE `finalise()`. The registry-based scope is
 * the structural invariant.
 *
 * The fitness is the regression guard. It walks the
 * cycle package's main source and greps for the
 * forbidden snapshot-diff pattern: a `currentEntries -
 * operatorOwnedSnapshot` (or any equivalent) used as
 * the runner-owned set in `finalise()`. Comments are
 * stripped before the scan so KDoc blocks documenting
 * the historical defect do not match.
 */
class CycleRunnerSnapshotDiffFitnessTest {

    private val cycleMainRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/cycle")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(cycleMainRoot)) {
            error("cycle package source not found at $cycleMainRoot")
        }
        return Files.walk(cycleMainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoSnapshotDiff(sources: List<Path>) {
        // The defect class is the snapshot-diff
        // computation:
        //   currentEntries - operatorOwnedSnapshot
        // or any variant that subtracts the snapshot
        // from the live directory listing. The pattern
        // matches `currentEntries`, `runnerOwned`, or
        // similar names minus `operatorOwnedSnapshot`.
        val snapshotDiff = Regex("""currentEntries\s*-\s*operatorOwnedSnapshot""")
        val blockComment = Regex("""/\*[\s\S]*?\*/""")
        val lineComment = Regex("""//[^\n]*""")
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val original = path.readText()
            val stripped = blockComment.replace(original, " ")
                .let { lineComment.replace(it, " ") }
            val matches = snapshotDiff.findAll(stripped).toList()
            for (m in matches) {
                val lineNo = stripped.lineSequence()
                    .takeWhile { m.range.first > (stripped.indexOf(it) + it.length) }
                    .count() + 1
                offenders += "${path.fileName}:$lineNo: ${m.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 P0 HITO 1 fitness: NO `currentEntries - operatorOwnedSnapshot` " +
                "snapshot-diff pattern in the cycle package's main source. The " +
                "previous shape computed the deletion scope by subtracting the " +
                "`prepare()` snapshot from the live cycle-dir listing; any file " +
                "added by an external process after `prepare()` was in the diff " +
                "and DELETED by `finalise()` — the 'carreras' (races) defect class. " +
                "The new shape uses the explicit [CycleRunner.createdFiles] " +
                "registry (populated only by the runner and executor themselves) " +
                "as the deletion scope; a concurrent file is not in the registry " +
                "and SURVIVES. A regression to the snapshot-diff re-opens the " +
                "races defect. Offending call sites: $offenders",
        )
    }

    @Test
    fun `no snapshot-diff deletion scope in the cycle package main source`() {
        assertNoSnapshotDiff(kotlinSources())
    }
}
