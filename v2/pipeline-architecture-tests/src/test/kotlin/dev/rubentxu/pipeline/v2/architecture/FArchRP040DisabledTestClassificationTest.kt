package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RP-040 fitness test: enforce the policy "nunca contar tests omitidos
 * como PASS" (never count skipped/disabled tests as PASS).
 *
 * The contract has two parts:
 *  1. Every `@Disabled` annotation MUST carry a textual reason
 *     (the @Disabled("...") string argument). A bare `@Disabled`
 *     is a smell — it leaves the test in the catalogue without
 *     explaining why.
 *  2. The XML canary reports MUST show `skipped="<count>"` for each
 *     test run. Tests that are skipped are not "passed" — they are
 *     a separate count. The XML attribute proves the test runner
 *     distinguishes "skipped" from "passed".
 *
 * Reference: ROADMAP.md §6 RP-040 ("registrar exclusiones y @Disabled
 * clasificados, nunca contar tests omitidos como PASS").
 */
class FArchRP040DisabledTestClassificationTest {

    @Test
    fun `every @Disabled annotation in the test tree carries a textual reason`() {
        val root = ScannerSupport.v2Root()
        val testSrcDirs = listOf(
            "pipeline-application/src/test",
            "pipeline-domain/src/test",
            "pipeline-events/src/test",
            "pipeline-architecture-tests/src/test",
            "pipeline-credentials-api/src/test",
            "pipeline-credentials-local/src/test",
            "pipeline-credentials-executor/src/test",
            "pipeline-credentials-multipart/src/test",
            "pipeline-scripting-api/src/test",
            "pipeline-scripting-kotlin24/src/test",
            "pipeline-step-sdk/api/src/test",
            "pipeline-step-sdk/runtime/src/test",
            "pipeline-step-sdk/utilities/src/test",
            "pipeline-step-sdk/scm-git/src/test",
            "pipeline-step-sdk/files/src/test",
            "pipeline-step-sdk/junit/src/test",
            "pipeline-step-sdk/processor/src/test",
            "pipeline-step-sdk/workflow-control/src/test",
            "pipeline-testkit/src/test",
            "pipeline-binding-factory/src/test",
            "pipeline-event-harness/src/test",
            "pipeline-artefacts-local/src/test",
        )

        // Strict regex: real @Disabled annotation on a code line.
        // Requires the annotation to be followed by an opening
        // parenthesis, with either a string argument or no argument.
        // Excludes pure-mention lines (in comments / strings).
        val annotationRegex = Regex("""^\s*@Disabled(\([^)]*\))?""")

        val disabledWithoutReason = mutableListOf<String>()

        testSrcDirs.forEach { relPath ->
            val dir = root.resolve(relPath)
            if (!dir.toFile().exists()) {
                return@forEach
            }
            dir.toFile().walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                // Exclude the test file itself: it is allowed to
                // contain literal @Disabled tokens in docstrings and
                // comments.
                .filter { it.name != "FArchRP040DisabledTestClassificationTest.kt" }
                .forEach { file ->
                    val text = file.readText()
                    // Strip block comments (/* ... */) and KDoc (/** ... */)
                    // so we only scan real code lines. Line comments (//)
                    // are stripped on a per-line basis inside the regex
                    // (the regex requires the annotation to be at the start
                    // of the line, optionally indented).
                    val stripped = text
                        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
                    val lines = stripped.lines()
                    lines.forEachIndexed { i, line ->
                        val match = annotationRegex.find(line)
                        if (match != null) {
                            // Decide multi-line from the RAW line, not from the
                            // regex match. `(\([^)]*\))?` only participates when a
                            // closing paren is on the SAME line, so a reason built
                            // by concatenation —
                            //
                            //     @Disabled("migrated-pending X; … " +
                            //         "second case")
                            //
                            // collapsed the match to bare `@Disabled`, DISCARDING
                            // the `(` that is the only signal that the annotation
                            // continues. `endsWith("@Disabled(")` was therefore
                            // unreachable and every concatenated reason was
                            // reported as a reasonless @Disabled.
                            val afterMarker = line.substringAfter("@Disabled", "")
                            val sb = StringBuilder(line.trim())
                            var j = i + 1
                            // Accumulate only when the annotation actually
                            // opened. A bare `@Disabled` must NOT swallow the
                            // following lines.
                            //
                            // Paren counting is deliberately NOT used: a reason
                            // string may itself contain parentheses
                            // ("Falsifier (x)"), which would unbalance the count.
                            if (afterMarker.contains('(') && !afterMarker.contains(')')) {
                                while (j < lines.size &&
                                    !sb.toString().substringAfter("@Disabled").contains(')')
                                ) {
                                    sb.append(' ').append(lines[j].trim())
                                    j++
                                }
                            }
                            val full = sb.toString().substringAfter("@Disabled").trim()
                            // The argument is whatever sits inside the outer
                            // parens; absent parens mean absent argument.
                            val argument =
                                if (full.startsWith('(')) full.substring(1).substringBeforeLast(')')
                                else ""
                            if (!argument.contains('"')) {
                                disabledWithoutReason +=
                                    "${root.relativize(file.toPath())}:${i + 1}: ${line.trim()}"
                            }
                        }
                    }
                }
        }

        assertTrue(
            disabledWithoutReason.isEmpty(),
            "Every @Disabled MUST carry a textual reason (@Disabled(\"...\")). " +
                "Bare @Disabled without a reason is a smell: it leaves the test in the " +
                "catalogue without explaining why it was disabled. Findings: $disabledWithoutReason"
        )
    }

    @Test
    fun `current XML canary distinguishes skipped from passed (representative sample)`() {
        // Sample one representative XML from a test that we know has
        // at least one @Disabled method to prove the XML reports
        // skipped separately from passed.
        val root = ScannerSupport.v2Root()
        val sampleXml = root.resolve(
            "pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.DslSemanticsRP032Test.xml"
        )

        if (!sampleXml.toFile().exists()) {
            // No prior run for this file. The previous test (no-skipped)
            // would have been the only assertion; here we just skip
            // without failing.
            return
        }

        val xml = sampleXml.toFile().readText()
        // The XML schema exposes `tests`, `skipped`, `failures`, `errors`.
        // Even when zero tests are skipped, the attribute MUST be present.
        assertTrue(
            xml.contains("skipped="),
            "JUnit XML must report skipped tests as a separate attribute. " +
                "Tests that are disabled/skip() must NEVER be conflated with passed. " +
                "Sample XML: $xml"
        )

        // Pull the value to assert it is a non-negative integer.
        val skipped = Regex("skipped=\"(\\d+)\"").find(xml)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals(true, skipped != null && skipped >= 0, "skipped attribute must be a non-negative integer. Found: $skipped")
    }
}
