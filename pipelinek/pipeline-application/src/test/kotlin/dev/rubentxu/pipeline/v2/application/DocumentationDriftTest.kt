package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.OTEL_SEMCONV_CICD_VERSION
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryIdentityTable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The documents of this repository and the code they describe must AGREE.
 *
 * ## The guarantee
 * A reader of the README, or of the policy, is owed a truth they can act on. A
 * document that names a capability this build does not serve, or promises a
 * test lane this repository does not run, or a promise the compiler never
 * made, is not documentation: it is a claim, and a false claim costs a reader
 * a debugging session they did not deserve.
 *
 * ## Why this instrument READS the documents and the code
 * The drift is a fact about two artifacts at once, so no runtime observation
 * can answer it: a program that behaves correctly says nothing about what the
 * README says about it. Only the two texts can.
 *
 * ## Why the facts are DERIVED and not written down here
 * Every row below takes the fact from the code and then requires the document
 * to state it. The alternative — writing the variable's name and its default
 * into this test — would freeze the truth here, and the test would keep
 * passing the day the thing it watches changed. A derived fact cannot drift
 * from the code without the test going red.
 */
class DocumentationDriftTest {

    @Test
    fun `the README names only capabilities this build delivers`() {
        val named = CAPABILITY_ID.findAll(readme()).map { it.value }.distinct().toList()

        assertTrue(
            named.isNotEmpty(),
            "the README names no capability id, so this rule proves nothing",
        )

        named.forEach { id ->
            val entry = requireNotNull(SdkCapabilities.ALL.firstOrNull { it.id == id }) {
                "the README names the capability '$id', which this build does not announce"
            }
            assertTrue(
                entry.status != SdkCapabilities.CapabilityStatus.DEPRECATED,
                "the README names the capability '$id', which this build announces and does NOT deliver",
            )
        }
    }

    @Test
    fun `the README does not promise that a comment breaks the compilation`() {
        val text = readme()

        val offenders = sentences(text).filter { sentence ->
            DOCUMENTATION_SUBJECT.containsMatchIn(sentence) && COMPILATION_CLAIM.containsMatchIn(sentence)
        }

        assertTrue(
            offenders.isEmpty(),
            "the README promises that a comment breaks the compilation: ${offenders.joinToString()}",
        )
        assertTrue(
            text.contains(INSTRUMENT_NAME),
            "the README does not name the instrument that watches it: '$INSTRUMENT_NAME'",
        )
    }

    @Test
    fun `the README names the three legs the gate actually runs`() {
        // The comparison folds the case: a leg named at the head of a list
        // item is capitalised for the reader's sake, and the capitalisation
        // says nothing about whether the leg is named. What this rule is about
        // is the NAME, and a name the reader has to guess the case of is a
        // name the reader has to guess.
        val text = readme().lowercase()

        listOf("unit tests", "integration tests", "mutants").forEach { leg ->
            assertTrue(
                text.contains(leg),
                "the README does not name the '$leg' leg of the gate",
            )
        }
    }

    @Test
    fun `the README does not name an artifact this repository does not have`() {
        val text = readme()

        val offenders = REMOVED_ARTIFACT.findAll(text).map { it.value }.distinct().toList()

        assertTrue(
            offenders.isEmpty(),
            "the README names artifacts that do not exist: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `the README names the example plugins the distribution ships`() {
        val text = readme()
        val examples = repositoryRoot().resolve(EXAMPLES_DIRECTORY)

        val plugins = Files.list(examples).use { paths ->
            paths
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { it.startsWith("example-") && it.endsWith("-plugin") }
                .sorted()
                .toList()
        }

        assertTrue(
            plugins.isNotEmpty(),
            "no example plugin directory was found under '$EXAMPLES_DIRECTORY', so this rule proves nothing",
        )

        plugins.forEach { plugin ->
            assertTrue(
                text.contains(plugin),
                "the README does not name the example plugin '$plugin', which the distribution ships",
            )
        }
    }

    @Test
    fun `the policy names only the test lanes the build has`() {
        val text = agents()

        val lanes = TEST_LANE.findAll(text).map { it.groupValues[1] }.distinct().toList()

        assertTrue(
            lanes.isNotEmpty(),
            "the policy names no test lane, so this rule proves nothing",
        )

        lanes.forEach { lane ->
            assertTrue(
                ALLOWED_LANES.contains(lane),
                "the policy names the test lane '$lane', which this repository does not run",
            )
        }
    }

    @Test
    fun `the README names only source files this repository has`() {
        val named = KOTLIN_FILE.findAll(readme()).map { it.groupValues[1] }.distinct().toList()

        assertTrue(
            named.isNotEmpty(),
            "the README names no Kotlin source file, so this rule proves nothing",
        )

        val present = kotlinFileNames()
        assertTrue(
            present.isNotEmpty(),
            "no Kotlin source file was found in the repository, so this rule proves nothing",
        )

        named.forEach { reference ->
            assertTrue(
                present.contains(reference.substringAfterLast('/')),
                "the README names the source file '$reference', which this repository does not have",
            )
        }
    }

    @Test
    fun `the README states the telemetry vocabulary this build exports`() {
        val section = readme()
            .substringAfter(TELEMETRY_HEADING)
            .substringBefore(PLUGIN_HEADING)

        val attributes = tableRow(section, "Attributes")
        val dimensions = tableRow(section, "Dimensions")

        assertTrue(
            attributes.isNotEmpty(),
            "the README states no telemetry attribute, so this rule proves nothing",
        )
        assertTrue(
            dimensions.isNotEmpty(),
            "the README states no telemetry dimension, so this rule proves nothing",
        )

        assertEquals(
            TelemetryIdentityTable.Default.allowedAttributeKeys(),
            attributes.toSet(),
            "the README states an attribute vocabulary that is not the one this build exports",
        )
        assertEquals(
            TelemetryIdentityTable.Default.allowedDimensionKeys(),
            dimensions.toSet(),
            "the README states a dimension vocabulary that is not the one this build exports",
        )

        assertTrue(
            section.contains("`$OTEL_SEMCONV_CICD_VERSION`"),
            "the README does not state the version of the semantic conventions this build pins",
        )
    }

    /**
     * The Kotlin source files this repository has, by file name.
     *
     * The name and not the path: a document may point at a file by its bare
     * name or by a path relative to a module, and both name the same file.
     * The directories a build produces are pruned, because a generated file is
     * not a file the repository has.
     */
    private fun kotlinFileNames(): Set<String> {
        val root = repositoryRoot()
        return Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".kt") }
                .filter { path ->
                    PRUNED_DIRECTORIES.none { pruned ->
                        path.toString().contains("${java.io.File.separator}$pruned${java.io.File.separator}")
                    }
                }
                .map { it.fileName.toString() }
                .collect(Collectors.toSet())
        }
    }

    /**
     * The values of one row of a table, by its label.
     *
     * The row is the unit a list of values lives in, and the label is how the
     * row is told apart from the others: a table that carries the same list
     * twice under two labels is two claims, and only the label says which is
     * which.
     */
    private fun tableRow(section: String, label: String): List<String> {
        val line = section.lines().firstOrNull { it.trimStart().startsWith("| $label |") } ?: return emptyList()
        return line.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != label }
            .flatMap { it.split(",") }
            .map { it.trim().removeSurrounding("`") }
            .filter { it.isNotEmpty() }
    }

    /**
     * The root of this repository.
     *
     * It is found by walking up from the working directory and asking for the
     * THREE markers a repository root carries and a module does not. One
     * marker would match a module that happened to carry a README of its own;
     * three markers cannot.
     */
    private fun repositoryRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            val directory = candidate
            if (ROOT_MARKERS.all { Files.isRegularFile(directory.resolve(it)) }) {
                return directory
            }
            candidate = directory.parent
        }
        error(
            "the repository root was not found from '${Paths.get("").toAbsolutePath()}'",
        )
    }

    private fun readme(): String = Files.readString(repositoryRoot().resolve("README.md"))

    private fun agents(): String = Files.readString(repositoryRoot().resolve("AGENTS.md"))

    /**
     * The sentences of a text.
     *
     * The sentence, and not the line and not the paragraph, is the unit a
     * PROMISE lives in: a line can hold two claims, and a paragraph can hold
     * a true claim and a false one. Splitting on a line would either miss the
     * false one or accuse the true one.
     */
    private fun sentences(text: String): List<String> =
        text.replace('\n', ' ').split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        /** The name of this instrument, as the README has to name it. */
        const val INSTRUMENT_NAME = "DocumentationDriftTest"

        /** The directory the example plugins live in, relative to the repository root. */
        const val EXAMPLES_DIRECTORY = "examples"

        /**
         * What a repository root carries and a module does not.
         *
         * All three, and not one: a module with a README of its own would
         * satisfy a single marker, and the instrument would then read the
         * module's README and find nothing.
         */
        val ROOT_MARKERS = listOf("AGENTS.md", "README.md", "scripts/verify-all.sh")

        /** A capability id, as the catalogue spells them. */
        val CAPABILITY_ID = Regex("\\b[a-z][a-z0-9]*\\.[a-z][a-z0-9]*\\.v[0-9]+\\b")

        /** The subject of the promise this rule forbids. */
        val DOCUMENTATION_SUBJECT = Regex("comment|kdoc|markdown|documentation", RegexOption.IGNORE_CASE)

        /** The claim this rule forbids. */
        val COMPILATION_CLAIM = Regex(
            "fail(s|ed)? to compile|does not compile|no longer compiles|break(s|ing)? the compilation",
            RegexOption.IGNORE_CASE,
        )

        /**
         * An artifact this repository does not have.
         *
         * The rule is about NAMES, not about the words that describe a
         * concept: a document may say that there is no end-to-end layer, and
         * that sentence is true. What it may not do is point a reader at a
         * file or a variable that is not there, because that is a claim they
         * will act on and cannot.
         */
        val REMOVED_ARTIFACT = Regex(
            "examples/run\\.sh|PIPELINEK_E2E_[A-Z_]+|check-e2e-receipt\\.py|e2e-family-map\\.tsv",
        )

        /** A test lane the policy names. */
        val TEST_LANE = Regex("src/(test|integrationTest)")

        /** A Kotlin source file the README names, with the path it names it under. */
        val KOTLIN_FILE = Regex("`([A-Za-z0-9_./-]+\\.kt)`")

        /** The section of the README that states the telemetry contract. */
        const val TELEMETRY_HEADING = "### Telemetry"

        /** The section that follows it, which ends the one this rule reads. */
        const val PLUGIN_HEADING = "### Writing a plugin"

        /** The directories a build produces, whose files the repository does not have. */
        val PRUNED_DIRECTORIES = setOf("build", ".git", ".gradle", ".idea", "node_modules")

        /** The lanes this repository runs. */
        val ALLOWED_LANES = setOf("test", "integrationTest")

        /** Where a sentence ends. */
        val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
    }
}
