package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.OTEL_SEMCONV_CICD_VERSION
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryIdentityTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `the documents do not claim that the run has no network`() {
        val offenders = listOf(readme(), agents())
            .flatMap { sentences(it) }
            .filter { sentence ->
                RUN_SUBJECT.containsMatchIn(sentence) && NETWORK_DENIAL.containsMatchIn(sentence)
            }

        assertTrue(
            offenders.isEmpty(),
            "the documents claim that the run has no network, and the child process of a shell " +
                "step keeps the host's network unless the run asks for the confined profile: " +
                "${offenders.joinToString()}",
        )
    }

    @Test
    fun `the README still says the network is behind a capability`() {
        val text = readme()

        assertTrue(
            NETWORK_BEHIND_A_CAPABILITY.containsMatchIn(text),
            "the README does not say that the network is behind a capability, so the reader is " +
                "left with the silence where the truth about the network should be",
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
        val section = telemetrySection()

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
     * The signals the documents name must be signals this build DECLARES.
     *
     * The name of an instrument is a promise to the consumer: a query that
     * names an instrument the product does not carry returns nothing, and the
     * consumer reads that emptiness as "nothing happened" instead of "the
     * instrument is not there". So the names the documents carry are checked
     * against the names the sources declare.
     *
     * The subject is the README, which is always here. The observability
     * example is read when it is here and adds its names to the same check,
     * so an example that names an instrument nobody declared is caught too.
     */
    @Test
    fun `the documents name only the signals this build declares`() {
        val declared = declaredSignalNames()
        assertTrue(
            declared.isNotEmpty(),
            "no signal name was derived from the sources, so this rule proves nothing",
        )

        val named = LinkedHashMap<String, String>()
        signalNamesIn(telemetrySection()).forEach { name ->
            named.putIfAbsent(name, "the README")
        }
        observabilityExampleFiles().forEach { file ->
            signalNamesIn(Files.readString(file)).forEach { name ->
                named.putIfAbsent(name, file.fileName.toString())
            }
        }

        assertTrue(
            named.isNotEmpty(),
            "no signal name was found in the documents, so this rule proves nothing",
        )

        named.forEach { (name, where) ->
            assertTrue(
                isDeclaredSignal(name, declared),
                "the document '$where' names the signal '$name', which this build does not declare",
            )
        }
    }

    /**
     * The dashboard must run the queries the example declares.
     *
     * The example is optional: the gate never requires it, and the engine does
     * not need it. When it IS here, a dashboard that runs another query than
     * the one the queries file declares is a consumer reading a different
     * picture than the one this repository ships.
     */
    @Test
    fun `the dashboard runs the queries the example declares`() {
        val example = repositoryRoot().resolve(EXAMPLES_DIRECTORY).resolve(OBSERVABILITY_DIRECTORY)
        val queries = example.resolve(QUERIES_FILE)
        val dashboard = example.resolve(DASHBOARD_FILE)
        if (!Files.isRegularFile(queries) || !Files.isRegularFile(dashboard)) {
            return
        }

        val declared = Files.readString(queries).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val expressions = dashboardExpressions(Files.readString(dashboard))

        assertTrue(
            declared.isNotEmpty(),
            "the queries file declares no query, so this rule proves nothing",
        )
        assertEquals(
            declared.sorted(),
            expressions.sorted(),
            "the dashboard does not run the queries the example declares",
        )
    }

    /**
     * The promise to ask for a passphrase at a terminal is made in ONE place.
     *
     * The resolver owns the diagnostic that names the alternatives, and it
     * names the terminal only when the caller offered a reader to ask with. A
     * second source that repeats the promise makes it somewhere it may not be
     * kept: a caller that cannot ask anybody would be sending the operator to
     * a terminal that changes nothing.
     */
    @Test
    fun `the promise to ask at a terminal is made only by the resolver`() {
        val owner = requireNotNull(mainSources().firstOrNull { it.text.contains(RESOLVER_DECLARATION) }) {
            "the resolver was not found among the main sources, so this rule proves nothing"
        }

        val offenders = mainSources().filter { it.name != owner.name && it.text.contains(TERMINAL_PROMISE) }

        assertTrue(
            offenders.isEmpty(),
            "these sources promise to ask at a terminal, and the promise is the resolver's to make: " +
                offenders.joinToString { it.name },
        )
    }

    /**
     * A run does not ask anybody for the passphrase.
     *
     * A run that waited for a human to type the passphrase would wait forever
     * in the place runs usually happen, and a run triggered by a scheduler has
     * nobody to ask. The run therefore hands the resolver no reader at all,
     * and the diagnostic it gets names the environment variable and nothing
     * else.
     */
    @Test
    fun `the runtime resolves the passphrase without asking anybody`() {
        val runtime = requireNotNull(mainSources().firstOrNull { it.text.contains(RUNTIME_SEAM_DECLARATION) }) {
            "the source that composes the credential seam was not found, so this rule proves nothing"
        }

        val calls = RESOLVER_CALL.findAll(runtime.text).map { it.groupValues[1].trim() }.toList()

        assertTrue(
            calls.isNotEmpty(),
            "the runtime does not resolve the passphrase, so this rule proves nothing",
        )
        assertTrue(
            calls.all { it.isEmpty() },
            "the runtime hands the resolver something to ask with, so a run asks at a terminal: " +
                "a run that waits for a human waits forever",
        )
    }

    /**
     * The signal names the sources of the product declare.
     *
     * The declaration is spread over several files, and this rule reads all of
     * them and takes the union: a name that appears in any main source counts
     * as declared. The over-approximation is deliberate — this rule is about
     * catching a name that NO source declares, not about being the authority
     * of the vocabulary.
     */
    private fun declaredSignalNames(): Set<String> {
        val root = repositoryRoot().resolve("pipelinek")
        if (!Files.isDirectory(root)) {
            return emptySet()
        }
        return Files.walk(root).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".kt") }
                .filter { it.toString().contains("${File.separator}src${File.separator}main${File.separator}") }
                .map { Files.readString(it) }
                .flatMap { text -> SIGNAL_NAME.findAll(text).map { match -> match.value } }
                .toSet()
        }
    }

    /**
     * A source of the product, by name and text.
     */
    private data class Source(val name: String, val text: String)

    /**
     * The main sources of the product.
     *
     * The main source set and not the tests: a test may QUOTE a promise to
     * assert on it, and a quoted promise is not a promise made.
     */
    private fun mainSources(): List<Source> {
        val root = repositoryRoot().resolve("pipelinek")
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        return Files.walk(root).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".kt") }
                .filter { it.toString().contains("${File.separator}src${File.separator}main${File.separator}") }
                .map { Source(it.fileName.toString(), Files.readString(it)) }
                .toList()
        }
    }

    /**
     * The signal names a text names, in both of the spellings a consumer reads.
     *
     * The product spells an instrument with dots and the Prometheus exposition
     * spells it with underscores, so a document may carry either.
     */
    private fun signalNamesIn(text: String): List<String> =
        SIGNAL_NAME.findAll(text).map { it.value }.toList() +
            EXPOSITION_NAME.findAll(text).map { it.value }.toList()

    /**
     * Whether a name is a signal this build declares.
     *
     * The exposition is not spelled the way the product spells it: the exporter
     * turns every dot into an underscore, appends the unit and writes a
     * histogram as three series. So an exposition name is accepted when
     * removing those suffixes leaves a name the product declares.
     */
    private fun isDeclaredSignal(name: String, declared: Set<String>): Boolean {
        if (name.isEmpty()) {
            return false
        }
        if (name in declared) {
            return true
        }
        if (name.replaceFirst("_", ".") in declared) {
            return true
        }
        return EXPOSITION_SUFFIXES.any { suffix ->
            name.endsWith(suffix) && isDeclaredSignal(name.dropLast(suffix.length), declared)
        }
    }

    /**
     * The files of the observability example, when this repository has them.
     *
     * The example is optional, and nothing here asks for it: the rule reads it
     * when it is there and finds nothing when it is not.
     */
    private fun observabilityExampleFiles(): List<Path> {
        val root = repositoryRoot().resolve(EXAMPLES_DIRECTORY).resolve(OBSERVABILITY_DIRECTORY)
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        return Files.walk(root).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() in EXAMPLE_DOCUMENTS }
                .sorted()
                .toList()
        }
    }

    /**
     * The expressions a dashboard runs, in the order the panels declare them.
     */
    private fun dashboardExpressions(text: String): List<String> {
        val panels = Json.parseToJsonElement(text).jsonObject["panels"]?.jsonArray ?: return emptyList()
        return panels.flatMap { panel ->
            panel.jsonObject["targets"]?.jsonArray.orEmpty().mapNotNull { target ->
                target.jsonObject["expr"]?.jsonPrimitive?.contentOrNull
            }
        }
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
     * The section of the README that states the telemetry contract.
     */
    private fun telemetrySection(): String =
        readme().substringAfter(TELEMETRY_HEADING).substringBefore(PLUGIN_HEADING)

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

        /** How the resolver declares itself, which is how its owner is found. */
        const val RESOLVER_DECLARATION = "object PassphraseResolver"

        /** How the runtime's credential seam declares itself. */
        const val RUNTIME_SEAM_DECLARATION = "fun composeCredentialSeam"

        /**
         * The promise to ask for a passphrase at a terminal, as the product
         * spells it. The phrase is the promise, and the promise has one owner.
         */
        const val TERMINAL_PROMISE = "interactively in a TTY"

        /** A call to the passphrase resolver, with its arguments. */
        val RESOLVER_CALL = Regex("PassphraseResolver\\.resolve\\(([^)]*)\\)")

        /** The directory the example plugins live in, relative to the repository root. */
        const val EXAMPLES_DIRECTORY = "examples"

        /** The directory the observability example lives in, relative to the examples directory. */
        const val OBSERVABILITY_DIRECTORY = "observability"

        /** The file the example declares its metric queries in. */
        const val QUERIES_FILE = "queries/metrics.promql"

        /** The file the example declares its dashboard in. */
        const val DASHBOARD_FILE = "dashboards/pipelinek.json"

        /** The documents of the example that name signals. */
        val EXAMPLE_DOCUMENTS = setOf("metrics.promql", "traces.traceql", "pipelinek.json", "collector.yaml")

        /** A signal name, as the product spells it. */
        val SIGNAL_NAME = Regex("pipelinek\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

        /** A signal name, as the Prometheus exposition spells it. */
        val EXPOSITION_NAME = Regex("pipelinek_[A-Za-z0-9_]+")

        /** The suffixes the Prometheus exposition adds to a signal name. */
        val EXPOSITION_SUFFIXES = listOf("_milliseconds", "_bucket", "_sum", "_count", "_total")

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

        /**
         * The subject of a claim about the run's OWN network.
         *
         * The word "plugin" is deliberately absent: the network of a plugin
         * really IS behind a capability, and a sentence about a plugin is not
         * a sentence about the run.
         */
        val RUN_SUBJECT = Regex(
            "\\b(run|execution|pipeline|stage|script|shell|step|process|workspace|sandbox)\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * A claim that there is no network to reach.
         *
         * The claim is what the rule forbids; the subject decides WHOSE claim
         * it is. Both are needed: "a plugin that does not work without network
         * access is refused" is TRUE, and a rule that read the claim alone
         * would accuse it.
         */
        val NETWORK_DENIAL = Regex(
            "no network|without (a |any )?network|no socket|offline|" +
                "(cannot|can not|may not|never) reach (the |any )?network",
            RegexOption.IGNORE_CASE,
        )

        /** The truth about the network, as the README states it. */
        val NETWORK_BEHIND_A_CAPABILITY = Regex(
            "network[^.]*behind (a )?capabilit|behind (a )?capabilit[^.]*network",
            RegexOption.IGNORE_CASE,
        )

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
