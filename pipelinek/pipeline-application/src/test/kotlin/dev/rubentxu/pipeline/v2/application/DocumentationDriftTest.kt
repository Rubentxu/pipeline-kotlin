package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * The documents of this repository and the code they describe must AGREE.
 *
 * ## The guarantee
 *
 * A reader of the README, or of the policy, is owed a truth they can act on. A
 * document that names a capability this build does not serve, or a ceiling this
 * launcher does not enforce, or a promise the compiler never made, is not
 * documentation: it is a claim, and a false claim costs a reader a debugging
 * session they did not deserve.
 *
 * ## Why this instrument READS the documents and the code
 *
 * The drift is a fact about two artifacts at once, so no runtime observation
 * can answer it: a program that behaves correctly says nothing about what the
 * README says about it. Only the two texts can.
 *
 * ## Why the facts are DERIVED and not written down here
 *
 * Every row below takes the fact from the code and then requires the document
 * to state it. The alternative — writing the variable's name and its default
 * into this test — would freeze the truth here, and the test would keep
 * passing the day the launcher changed its ceiling. A derived fact cannot
 * drift from the code without the test going red.
 *
 * That is also why the ceiling's semantics are read from the launcher rather
 * than asserted here: the refusal of a non-positive budget is a property of
 * the launcher, and the documents have to describe THAT refusal.
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
    fun `the documents state the ceiling the launcher enforces`() {
        val launcher = launcher()
        val declaration = requireNotNull(SCENARIO_CEILING.find(launcher)) {
            "the launcher does not declare a scenario ceiling"
        }
        val variable = declaration.groupValues[1]
        val default = declaration.groupValues[2]

        assertTrue(
            launcher.contains(POSITIVE_SECONDS_MESSAGE),
            "the launcher does not refuse a budget that is not a positive number of seconds",
        )
        assertTrue(
            NON_POSITIVE_BUDGET.containsMatchIn(launcher),
            "the launcher does not refuse a budget that is zero or negative",
        )

        listOf("README.md" to readme(), "AGENTS.md" to agents()).forEach { (name, document) ->
            val window = windowAfter(document, variable)

            assertTrue(
                defaultIsStated(default).containsMatchIn(window),
                "the $name does not state the ceiling's default of $default seconds",
            )
            assertTrue(
                NO_DISABLE_MODE.containsMatchIn(window),
                "the $name does not say that the ceiling has no disable mode",
            )
            assertTrue(
                window.contains("positive"),
                "the $name does not say that the budget must be a positive number of seconds",
            )
            assertTrue(
                window.contains("FAIL"),
                "the $name does not say that a budget that is not positive makes the scenario FAIL",
            )
            assertTrue(
                !ZERO_DISABLES.containsMatchIn(window),
                "the $name claims that 0 disables the ceiling",
            )
        }
    }

    @Test
    fun `no two examples in one directory share a numeric prefix`() {
        val examples = repositoryRoot().resolve(EXAMPLES_DIRECTORY)
        val byDirectory = Files.walk(examples).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(PIPELINE_SUFFIX) }
                .map { examples.relativize(it) }
                .collect(Collectors.groupingBy { it.parent?.toString().orEmpty() })
        }

        byDirectory.forEach { (directory, files) ->
            val collisions = files
                .mapNotNull { file -> numericPrefix(file.fileName.toString())?.let { it to file } }
                .groupBy { it.first }
                .filterValues { it.size > 1 }

            assertTrue(
                collisions.isEmpty(),
                "the examples in '$directory' share a numeric prefix: " +
                    collisions.entries.joinToString { (prefix, group) ->
                        "$prefix -> ${group.joinToString { it.second.fileName.toString() }}"
                    },
            )
        }
    }

    @Test
    fun `every directive the launcher handles is documented in its header`() {
        val launcher = launcher()
        val documented = DOCUMENTED_DIRECTIVE.findAll(launcher).map { it.groupValues[1] }.toSet()
        val handled = HANDLED_DIRECTIVE.findAll(launcher).map { it.groupValues[1] }.toSet()

        assertTrue(documented.isNotEmpty(), "the launcher documents no directive, so this rule proves nothing")
        assertTrue(handled.isNotEmpty(), "the launcher handles no directive, so this rule proves nothing")

        assertEquals(
            emptySet<String>(),
            handled - documented,
            "the launcher handles directives its header does not document",
        )
        assertEquals(
            emptySet<String>(),
            documented - handled,
            "the launcher documents directives it does not handle",
        )
    }

    @Test
    fun `an example that names a pipeline in its first line names itself`() {
        val examples = repositoryRoot().resolve(EXAMPLES_DIRECTORY)

        Files.walk(examples).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(PIPELINE_SUFFIX) }
                .forEach { file ->
                    val firstLine = firstLineOf(file)
                    val named = NAMED_PIPELINE.findAll(firstLine).map { it.value }.toList()

                    if (named.isNotEmpty()) {
                        assertTrue(
                            firstLine.contains(file.fileName.toString()),
                            "${file.fileName} names a pipeline in its first line and not itself: $named",
                        )
                    }
                }
        }
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

    private fun launcher(): String = Files.readString(repositoryRoot().resolve("examples/run.sh"))

    /**
     * The sentences of a text.
     *
     * The sentence, and not the line and not the paragraph, is the unit a
     * PROMISE lives in: a line can hold two claims, and a paragraph can hold a
     * true claim and a false one. Splitting on a line would either miss the
     * false one or accuse the true one.
     */
    private fun sentences(text: String): List<String> =
        text.replace('\n', ' ').split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The characters that follow the first mention of a name.
     *
     * A window, and not the whole document: the facts about one ceiling live
     * next to the mention of the ceiling, and a rule that reads the whole file
     * passes on a document that says the truth somewhere else.
     */
    private fun windowAfter(document: String, variable: String): String {
        val index = document.indexOf(variable)
        require(index >= 0) {
            "the document does not name the ceiling's variable '$variable'"
        }
        return document.substring(index, minOf(document.length, index + WINDOW_CHARACTERS))
    }

    private fun numericPrefix(fileName: String): String? =
        NUMERIC_PREFIX.find(fileName)?.groupValues?.get(1)

    private fun firstLineOf(file: Path): String =
        Files.newBufferedReader(file).use { reader -> reader.readLine() ?: "" }

    private companion object {
        /** The name of this instrument, as the README has to name it. */
        const val INSTRUMENT_NAME = "DocumentationDriftTest"

        /** The directory the examples live in, relative to the repository root. */
        const val EXAMPLES_DIRECTORY = "examples"

        /** What a scenario is, as the launcher names it. */
        const val PIPELINE_SUFFIX = ".pipeline.kts"

        /** How far a window reaches from the mention of a name. */
        const val WINDOW_CHARACTERS = 400

        /**
         * What a repository root carries and a module does not.
         *
         * All three, and not one: a module with a README of its own would
         * satisfy a single marker, and the instrument would then read the
         * module's README and find nothing.
         */
        val ROOT_MARKERS = listOf("AGENTS.md", "README.md", "examples/run.sh")

        /** A capability id, as the catalogue spells them. */
        val CAPABILITY_ID = Regex("\\b[a-z][a-z0-9]*\\.[a-z][a-z0-9]*\\.v[0-9]+\\b")

        /** The subject of the promise this rule forbids. */
        val DOCUMENTATION_SUBJECT = Regex("comment|kdoc|markdown|documentation", RegexOption.IGNORE_CASE)

        /** The claim this rule forbids. */
        val COMPILATION_CLAIM = Regex(
            "fail(s|ed)? to compile|does not compile|no longer compiles|break(s|ing)? the compilation",
            RegexOption.IGNORE_CASE,
        )

        /** The ceiling the launcher declares, with its default. */
        val SCENARIO_CEILING = Regex("SCENARIO_TIMEOUT_SECONDS=\"\\$\\{([A-Z0-9_]+):-([0-9]+)\\}\"")

        /** What the launcher says when a budget is not a positive number of seconds. */
        const val POSITIVE_SECONDS_MESSAGE = "must be a positive number of seconds"

        /** The refusal of a budget that is zero or negative. */
        val NON_POSITIVE_BUDGET = Regex("\\(\\([^)]*<= *0[^)]*\\)\\)")

        /** The default, stated as the default. */
        fun defaultIsStated(default: String): Regex =
            Regex("\\b${Regex.escape(default)}\\b[^.]{0,40}default|default[^.]{0,40}\\b${Regex.escape(default)}\\b", RegexOption.IGNORE_CASE)

        /** The absence of a way to turn the ceiling off. */
        val NO_DISABLE_MODE = Regex("no\\s+[\"“”']?disable", RegexOption.IGNORE_CASE)

        /** The claim that zero turns the ceiling off. */
        val ZERO_DISABLES = Regex("disabl.{0,80}\\b0\\b|\\b0\\b.{0,80}disabl", RegexOption.IGNORE_CASE)

        /** A directive as the header of the launcher writes it. */
        val DOCUMENTED_DIRECTIVE = Regex("^#   //@ ([a-z-]+)", RegexOption.MULTILINE)

        /** A directive as the parser of the launcher reads it. */
        val HANDLED_DIRECTIVE = Regex("'//@ ([a-z-]+)")

        /** The numeric order a file carries in its name. */
        val NUMERIC_PREFIX = Regex("^([0-9]+)-")

        /** A pipeline named in a line of text. */
        val NAMED_PIPELINE = Regex("[A-Za-z0-9_.-]+\\.pipeline\\.kts")

        /** Where a sentence ends. */
        val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
    }
}
