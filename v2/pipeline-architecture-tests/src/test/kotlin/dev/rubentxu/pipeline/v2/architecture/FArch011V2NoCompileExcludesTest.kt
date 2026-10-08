package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * No build file may exclude SOURCES from compilation.
 *
 * The law is about hiding code from the compiler. It is deliberately not a blanket ban on the token
 * `exclude(`: a `fileTree(...) { exclude(...) }` declares which files are INPUTS to a task and hides
 * nothing from the compiler. Measured 2026-10-08, the blanket form failed three legitimate
 * `inputs.files(fileTree(...) { exclude(...) })` declarations in the plugin SDK modules while those
 * modules compiled every source they have — an over-fire, and an over-firing law teaches people to
 * delete the law.
 *
 * Both directions are pinned here, because a narrowed scanner is only trustworthy if it is shown to
 * still catch what it was narrowed away from, and to no longer catch what it never meant:
 *
 *   allowed  `fileTree(...) { exclude(...) }`          input/pattern filter
 *   flagged  `KotlinCompile { exclude(...) }`          the original violation
 *   flagged  `sourceSets { setExcludes(...) }`         the SAME exclusion via Gradle's setter, which
 *                                                     a scan for `exclude(` alone would miss
 *   flagged  a multi-line `fileTree` filter            over-fires on purpose: see the scanner KDoc
 */
class FArch011V2NoCompileExcludesTest {

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val findings = ScannerSupport.findExcludeCalls(root)
        assertTrue(
            findings.isEmpty(),
            "No build file may exclude sources from compilation: $findings",
        )
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        private fun fixture(text: String): Path {
            val file = tempDir.resolve("build.gradle.kts")
            file.toFile().writeText(text.trimIndent())
            return file
        }

        @Test
        fun `scanner rejects the synthetic compile exclude`() {
            fixture(
                """
                plugins { kotlin("jvm") }
                tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                    exclude("**/Legacy.kt")
                }
                """,
            )

            val findings = ScannerSupport.findExcludeCalls(tempDir)

            assertTrue(findings.isNotEmpty(), "Scanner must detect the compile exclude in the fixture")
            assertTrue(findings.first().excerpt.contains("exclude("))
        }

        @Test
        fun `scanner rejects the setExcludes spelling of the same exclusion`() {
            fixture(
                """
                plugins { kotlin("jvm") }
                sourceSets {
                    main { setExcludes(listOf("**/Legacy.kt")) }
                }
                """,
            )

            val findings = ScannerSupport.findExcludeCalls(tempDir)

            assertTrue(
                findings.isNotEmpty(),
                "A scan for 'exclude(' alone would let the same exclusion ride in as 'setExcludes('",
            )
            assertTrue(findings.first().excerpt.contains("setExcludes("))
        }

        @Test
        fun `scanner allows an input file-tree filter`() {
            fixture(
                """
                plugins { kotlin("jvm") }
                val resourcesDir = layout.buildDirectory.dir("resources/main")
                tasks.register("computeDigest") {
                    inputs.files(fileTree(resourcesDir) { exclude(setOf("META-INF/generated.json")) })
                }
                """,
            )

            val findings = ScannerSupport.findExcludeCalls(tempDir)

            assertTrue(
                findings.isEmpty(),
                "A fileTree filter declares inputs, it does not hide a source from the compiler: $findings",
            )
        }

        @Test
        fun `scanner still rejects a multi-line fileTree filter`() {
            fixture(
                """
                plugins { kotlin("jvm") }
                val resourcesDir = layout.buildDirectory.dir("resources/main")
                tasks.register("computeDigest") {
                    inputs.files(
                        fileTree(resourcesDir) {
                            exclude("META-INF/generated.json")
                        },
                    )
                }
                """,
            )

            val findings = ScannerSupport.findExcludeCalls(tempDir)

            assertTrue(
                findings.isNotEmpty(),
                "A lookback wide enough to allow this would also allow a compile exclude sitting " +
                    "under a fileTree line, so the law over-fires here on purpose",
            )
        }
    }
}
