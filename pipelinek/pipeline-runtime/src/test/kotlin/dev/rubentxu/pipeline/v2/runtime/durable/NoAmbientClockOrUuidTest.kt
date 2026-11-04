package dev.rubentxu.pipeline.v2.runtime.durable

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The durable engine reads NEITHER the platform clock NOR the platform uuid.
 *
 * ## The guarantee
 * No file of the engine calls `Instant.now()`, `UUID.randomUUID()` or
 * `System.currentTimeMillis()`. Those three are AMBIENT reads: they depend on
 * the clock of the machine and on the entropy of the process, so they are not
 * deterministic.
 *
 * ## Why it matters (measured, not assumed)
 * The engine read the clock and drew identities in 103 sites spread over 18
 * files. Three consequences, all real:
 *
 *  - A decision test could not pin the time: the same case produced a
 *    different timestamp on every run, and the result depended on the clock of
 *    the machine that ran it.
 *  - A test could not assert WHICH event was published, because the
 *    `eventId` differed on every run.
 *  - The engine's time and its collaborators' time were TWO sources: a
 *    collaborator could seal an event at one instant and the engine at
 *    another, and nothing stopped it.
 *
 * ## What this test looks at
 * It looks at CODE, not at prose: comment lines are dropped before the
 * search, because a KDoc may legitimately NAME `Instant.now()` while
 * explaining why the engine never calls it. What the engine may not do is
 * CALL it.
 *
 * The inventory this test produces when it fails is the exact list of the
 * sites that are left, with file and line.
 */
class NoAmbientClockOrUuidTest {

    @Test
    @DisplayName("the durable engine reads neither the platform clock nor the platform uuid")
    fun `the durable engine reads neither the platform clock nor the platform uuid`() {
        val offences = engineSources().flatMap { source ->
            codeLines(source).mapNotNull { (number, line) ->
                FORBIDDEN.forEach { (call, reason) ->
                    if (line.contains(call)) {
                        return@mapNotNull Offence(source.path, number, call, reason)
                    }
                }
                null
            }
        }

        assertEquals(
            emptyList<Offence>(),
            offences,
            buildString {
                append("the durable engine reads the platform in ${offences.size} site(s).")
                append(" Every ambient read makes a decision test unable to pin the time nor")
                append(" the identity, and lets two collaborators seal the same instant with")
                append(" different values.\n")
                offences.forEach { offence ->
                    append("  ${offence.file.fileName}:${offence.number} — ${offence.call}")
                    append(" (${offence.reason})\n")
                }
            },
        )
    }

    /**
     * Every `.kt` file of the durable engine.
     *
     * The walk starts at this module's `src/main/kotlin` and descends into the
     * package that holds the engine, so the test keeps working whether it runs
     * from the module directory or from the repository root.
     */
    private fun engineSources(): List<SourceFile> {
        val engineDir = moduleSourceRoot().resolve(PACKAGE_PATH)
        require(Files.isDirectory(engineDir)) {
            "the durable engine's sources were not found at '$engineDir'"
        }
        return Files.walk(engineDir).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".kt") }
                .map { SourceFile(it, Files.readString(it)) }
                .collect(Collectors.toList())
        }
    }

    /**
     * The lines of [source] that are CODE, each with its number in the FILE.
     *
     * A comment line is dropped: a KDoc may legitimately NAME `Instant.now()`
     * while explaining why the engine never calls it. What the engine may not
     * do is CALL it.
     *
     * The number is the line's position in the file, not in the filtered list:
     * a report that names a line the reader cannot open is a report nobody can
     * act on.
     */
    private fun codeLines(source: SourceFile): List<Pair<Int, String>> =
        source.text.lines()
            .mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                val isComment = trimmed.startsWith("*") ||
                    trimmed.startsWith("//") ||
                    trimmed.startsWith("/*")
                if (isComment) null else (index + 1) to line
            }

    /**
     * The root of this module's `src/main/kotlin`.
     */
    private fun moduleSourceRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            MODULE_RELATIVE_PATHS.forEach { relative ->
                val packageDir = candidate.resolve(relative).resolve(PACKAGE_PATH)
                if (Files.isDirectory(packageDir)) {
                    return candidate.resolve(relative)
                }
            }
            candidate = candidate.parent
        }
        error(
            "the sources of the runtime module were not found from '${Paths.get("").toAbsolutePath()}'",
        )
    }

    /** One source file and its text. */
    private data class SourceFile(val path: Path, val text: String)

    /** One ambient read the engine must not perform. */
    private data class Offence(
        val file: Path,
        val number: Int,
        val call: String,
        val reason: String,
    )

    private companion object {
        /**
         * The package every file of the durable engine lives under.
         *
         * The engine is the `:pipeline-runtime` module's `durable` package.
         * The scope is the PACKAGE and not the module: the module also carries
         * the core Steps, and those are outside the guarantee this test
         * states.
         */
        const val PACKAGE_PATH = "dev/rubentxu/pipeline/v2/runtime/durable"

        /**
         * Where the module's `src/main/kotlin` sits, relative to a directory
         * the test walks up from: inside the module directory itself, or
         * inside the repository that holds it.
         */
        val MODULE_RELATIVE_PATHS = listOf(
            "src/main/kotlin",
            "pipelinek/pipeline-runtime/src/main/kotlin",
        )

        /**
         * The ambient reads, and what each one costs.
         */
        val FORBIDDEN: List<Pair<String, String>> = listOf(
            "Instant.now()" to "the instant depends on the clock of the machine that runs it",
            "UUID.randomUUID()" to "the identity depends on the entropy of the process",
            "System.currentTimeMillis()" to "the instant depends on the clock of the machine that runs it",
        )
    }
}
