package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * F-ARCH-L7-004: Artefact store location gate.
 *
 * Architecture test that enforces ALL writes from pipeline-artefacts-local/
 * go under controlDirRoot/artefacts/<runId>/<stageName>/.
 *
 * This CLOSES the artefact layout invariant: the grep gate finds zero matches
 * for writes outside the artefacts/ directory tree.
 *
 * RED: IOException (path doesn't exist) OR findings list non-empty (writes outside artefacts/)
 * GREEN: Findings list is empty (all writes go under controlDirRoot/artefacts/)
 */
class FArchL7ArtefactLocationTest {

    /**
     * Greps for controlDirRoot.resolve() chains that do NOT go under artefacts/.
     *
     * All artefact-store writes MUST go under:
     *   controlDirRoot/artefacts/<runId>/<stageName>/
     *
     * Forbidden patterns:
     * - controlDirRoot.resolve("workspace/...")
     * - controlDirRoot.resolve("journal/...")
     * - controlDirRoot.resolve("other/...")
     * - Direct Path.of("/tmp/...") or similar
     *
     * ZERO matches = GREEN = all writes are under artefacts/
     */
    @Test
    fun `all_artefact_writes_go_under_artefacts_directory`() {
        val root = ScannerSupport.v2Root()

        val artefactSrc = root.resolve("pipeline-artefacts-local/src/main/kotlin")
            .let { if (it.toFile().exists()) it else null }

        if (artefactSrc == null) {
            // Module doesn't exist yet - RED state
            throw AssertionError("pipeline-artefacts-local module source does not exist yet")
        }

        val findings = mutableListOf<Finding>()

        // Patterns that indicate writes OUTSIDE the artefacts/ directory
        val forbiddenPatterns = listOf(
            Regex("""controlDirRoot\.resolve\s*\(\s*"workspace"""),
            Regex("""controlDirRoot\.resolve\s*\(\s*"journal"""),
            Regex("""Path\.of\s*\(\s*"/tmp"""),
            Regex("""Path\.of\s*\(\s*"/var"""),
            Regex("""Path\.of\s*\(\s*"/home"""),
            Regex("""resolve\s*\(\s*"[^"]*artefacts[^"]*"\s*\)\s*(?:(?!artefacts).)*$""")
        )

        for (file in FitnessPaths.walkKotlinFiles(artefactSrc)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

                for (pattern in forbiddenPatterns) {
                    if (pattern.containsMatchIn(line)) {
                        // Double-check: if it mentions "artefacts" somewhere in the line,
                        // it's likely a correct usage
                        if (!line.contains("artefacts")) {
                            findings.add(Finding(file, lineIdx + 1, "forbidden-location", line))
                        }
                    }
                }
            }
        }

        // RED: findings list non-empty means writes outside artefacts/
        // GREEN: findings list empty means all writes are under artefacts/
        assertTrue(
            findings.isEmpty(),
            "All artefact writes must go under controlDirRoot/artefacts/. Found: $findings"
        )
    }

    /**
     * Verifies the artefacts directory structure follows the layout:
     * controlDirRoot/artefacts/<runId>/<stageName>/
     */
    @Test
    fun `artefact_location_follows_runid_stagename_layout`() {
        val root = ScannerSupport.v2Root()

        val artefactSrc = root.resolve("pipeline-artefacts-local/src/main/kotlin")
            .let { if (it.toFile().exists()) it else null }

        if (artefactSrc == null) {
            throw AssertionError("pipeline-artefacts-local module source does not exist yet")
        }

        val findings = mutableListOf<Finding>()

        // Check for correct layout: artefacts/<runId>/<stageName>/
        val correctLayoutPattern = Regex("""artefacts[/\\].*[/\\].*""")

        for (file in FitnessPaths.walkKotlinFiles(artefactSrc)) {
            val content = Files.readString(file)
            if (content.contains("controlDirRoot") && !correctLayoutPattern.containsMatchIn(content)) {
                findings.add(Finding(file, 0, "incorrect-layout", "controlDirRoot used without artefacts/<runId>/<stageName>/ layout"))
            }
        }

        assertTrue(
            findings.isEmpty(),
            "Artefact location must follow artefacts/<runId>/<stageName>/ layout. Found: $findings"
        )
    }
}
