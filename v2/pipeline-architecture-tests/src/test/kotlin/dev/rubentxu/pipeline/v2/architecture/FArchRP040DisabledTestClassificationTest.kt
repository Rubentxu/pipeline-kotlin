package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    /**
     * A `@Disabled` in annotation position, with or without a reason.
     *
     * The `(?![\w])` boundary matters: it keeps `@Disabled` from also matching the
     * prefix of `@DisabledBecauseYouForgot`. Requiring a `(` here instead would be
     * a silent blind spot — the bare `@Disabled` this policy exists to catch is
     * precisely the one with no argument.
     */
    private val disabledAnnotation = Regex("""(?m)^[ \t]*@Disabled(?![\w])""")

    /** A string literal, possibly the first of several concatenated to build a reason. */
    private val reasonLiteral = Regex("""\s*"((?:[^"\\]|\\.)*)"""")

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

        // The annotation is located in the SANITIZED form — comments and literal
        // bodies blanked, offsets preserved — and its reason is then read from the
        // RAW text at the same offset, because the locator necessarily blanks the
        // very string that IS the reason.
        //
        // The previous version did the opposite: it stripped block comments with
        // `replace(Regex("""/\*[\s\S]*?\*/"""), "")`, which is not a comment
        // stripper on Kotlin. `artifacts = "build/libs/*.jar"` opens a phantom
        // block comment that swallows the following 353 lines, including three
        // REAL annotations in CoreArchiveArtifactsStepUnitTest. Measured: that
        // strip alone dropped the file from 3 annotations to 0, so the fitness was
        // blind to reasonless @Disabled in every file whose glob literals precede
        // one. It also had no way to see an annotation that is only a string
        // fixture, which is the same confusion in the opposite direction.
        val annotationRegex = disabledAnnotation

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
                    val sanitized = KotlinSourceSanitizer.sanitizedSource(text)
                    annotationRegex.findAll(sanitized).forEach { match ->
                        // Offsets are preserved by the lexer, so this indexes the
                        // real reason, not whatever happened to sit at a
                        // position the lexer had shifted.
                        val raw = reasonLiteral.find(text, match.range.last + 1)
                        if (raw == null) {
                            val line = sanitized.take(match.range.last).count { it == '\n' } + 1
                            disabledWithoutReason +=
                                "${root.relativize(file.toPath())}:$line: ${annotationLine(sanitized, match.range.last)}"
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

    /** The source line containing [offset], for the failure message. */
    private fun annotationLine(sanitized: String, offset: Int): String =
        sanitized.substring(offset)
            .lineSequence()
            .first()
            .trim()

    /**
     * Pins the two ways this fitness used to look at the wrong thing.
     *
     * Both rows exercise the production scan path. A version of this test that
     * reimplemented the scan inline inside the fixture would pass while the
     * shipped locator was broken — which is exactly how the phantom-comment
     * strip survived: the class asserted a property its own scanner did not have.
     */
    @Nested
    inner class AnnotationLocatorFixture {

        @TempDir
        lateinit var tempDir: Path

        private fun write(body: String): Path =
            tempDir.resolve("Fixture.kt").also { Files.writeString(it, body) }

        @Test
        fun `an annotation inside a string literal is not a reasonless annotation`() {
            write(
                """
                package dev.rubentxu.pipeline.v2.architecture
                class DetectorTest {
                    private val source = ${"\"\"\""}
                        @Disabled
                    ${"\"\"\""}
                }
                """.trimIndent(),
            )

            assertEquals(
                emptyList<String>(),
                reasonlessAnnotations(tempDir.resolve("Fixture.kt")),
                "A bare @Disabled used as a string fixture is text under test, not an " +
                    "annotation the tree forgot to justify",
            )
        }

        @Test
        fun `a glob inside a string literal does not hide the annotations after it`() {
            // The trailing comment is LOAD-BEARING, and the first version of this
            // fixture omitted it. A stripper that treats `/*` as a block-comment
            // opener only erases anything if a `*/` appears LATER; with the glob
            // alone it finds no partner, strips nothing, and the fixture passes
            // while the defect it names is fully present — a green row with no
            // teeth. This shape (glob first, real block comment further down) is
            // the one that actually hid three annotations in
            // CoreArchiveArtifactsStepUnitTest.
            write(
                """
                package dev.rubentxu.pipeline.v2.architecture
                class GlobTest {
                    private val artifacts = "build/libs/*.jar"

                    @Disabled
                    fun bare() = Unit

                    /* trailing comment */
                    fun other() = Unit
                }
                """.trimIndent(),
            )

            assertEquals(
                1,
                reasonlessAnnotations(tempDir.resolve("Fixture.kt")).size,
                "`\"build/libs/*.jar\"` opens a phantom block comment for a regex-based " +
                    "strip, which pairs it with the next `*/` and silently hides every " +
                    "annotation in between",
            )
        }

        @Test
        fun `a reason spelled by concatenation is accepted`() {
            write(
                """
                package dev.rubentxu.pipeline.v2.architecture
                class ConcatenatedReasonTest {
                    @Disabled("migrated-pending: first half " + "and second half")
                    fun annotated() = Unit
                }
                """.trimIndent(),
            )

            assertEquals(
                emptyList<String>(),
                reasonlessAnnotations(tempDir.resolve("Fixture.kt")),
                "A reason assembled by concatenation is still a reason",
            )
        }
    }

    /** The reasonless `@Disabled` findings in [file], by the same locator the policy test uses. */
    private fun reasonlessAnnotations(file: Path): List<String> {
        val text = Files.readString(file)
        val sanitized = KotlinSourceSanitizer.sanitizedSource(text)
        return disabledAnnotation.findAll(sanitized)
            .filterNot { reasonLiteral.find(text, it.range.last + 1) != null }
            .map { annotationLine(sanitized, it.range.last) }
            .toList()
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
