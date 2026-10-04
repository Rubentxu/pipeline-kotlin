package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * M1 — the Output Plane is independent of the event plane, and the build is what says so.
 *
 * ## Why this is a fitness test and not a code comment
 *
 * [ADR-M1 D3] requires output continuation to be an order of its own: a cursor is a stream
 * identity plus a committed **byte offset**, not an event sequence. A comment saying so is a
 * request. This reads the module's declared dependencies and its production sources, so the
 * independence survives a well-meaning PR that reaches for a shared type because it was one import
 * away.
 *
 * The direction is one-way on purpose: `:pipeline-application` may write to `:pipeline-output`,
 * because the product must put bytes somewhere. `:pipeline-output` may not reach back into
 * `:pipeline-events` or `:pipeline-domain`, because that is how the event plane would acquire an
 * authority over output bytes again — the second authority this whole line exists to remove.
 *
 * The source scan strips comments first. Without that, the KDoc on `OutputStreamId` — which names
 * `EventCursor` in order to explain why it is *not* one — would satisfy the very law it documents.
 * The same trap closed `SingleDurableAuthorityFitnessTest` in the other direction.
 */
class M1OutputPlaneIndependenceFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private fun moduleBuildFile(module: String): Path =
        v2Root.resolve(module).resolve("build.gradle.kts")

    private fun productionSources(module: String): List<Path> {
        val main = v2Root.resolve(module).resolve("src/main/kotlin")
        if (!Files.isDirectory(main)) return emptyList()
        return ScannerSupport.walkKotlinFiles(v2Root).filter { it.startsWith(main) }.toList()
    }

    @Test
    fun `the output module declares no dependency on the event plane or the domain`() {
        val buildFile = moduleBuildFile("pipeline-output")
        assertTrue(Files.exists(buildFile), "cannot read $buildFile; this guard would be vacuous")

        val declared = stripComments(Files.readString(buildFile))
            .lineSequence()
            .map { line -> line.trim() }
            // Match the project(...) CALL, not a line that begins with it. The first version of
            // this guard filtered on line.startsWith("project(\""), which no real dependency line
            // ever satisfies — they read `implementation(project(":..."))`. Adding a forbidden
            // dependency left the suite GREEN. A guard that cannot match its own subject is worse
            // than no guard, because it reads as coverage.
            .filter { line -> line.contains("project(\"") }
            .toList()

        val forbidden = listOf(":pipeline-events", ":pipeline-domain", ":pipeline-application")
        val violations: List<String> = declared.filter { line -> forbidden.any { f -> line.contains("\"$f\"") } }

        if (violations.isNotEmpty()) {
            throw AssertionError(
                ":pipeline-output must not depend on ${forbidden.joinToString()}. ADR-M1 D3 makes output " +
                    "continuation an order of its own; a shared dependency is how the event plane would " +
                    "re-acquire an authority over output bytes. Found: $violations",
            )
        }
    }

    @Test
    fun `no production source in the output plane reaches for event or domain vocabulary`() {
        val sources = productionSources("pipeline-output")
        assertTrue(
            sources.isNotEmpty(),
            "found no production sources under pipeline-output/src/main; the scan is looking in the wrong place",
        )

        // Comments are stripped first: OutputStreamId's KDoc names EventCursor precisely to say it
        // is not one, and a raw text scan would read that as a violation.
        val offenders: List<Path> = sources.filter { source: Path ->
            val code = stripComments(Files.readString(source))
            code.contains("dev.rubentxu.pipeline.v2.events") ||
                code.contains("dev.rubentxu.pipeline.v2.domain")
        }

        if (offenders.isNotEmpty()) {
            throw AssertionError(
                "these production sources reach into the event or domain plane, which ADR-M1 D3 " +
                    "forbids: " + offenders.map { f -> v2Root.relativize(f).toString() },
            )
        }
    }

    /** Block comments and line comments removed; string literals left alone on purpose. */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
}
