package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryIdentityTable
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryVocabularyRole
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The published attribute vocabulary and the attributes the product emits are
 * the same set.
 *
 * ## The guarantee
 *
 * A key the published vocabulary names is a key the export carries, and a key
 * the export carries is a key the published vocabulary names. The consumer
 * reads the vocabulary as the description of what arrives: a key that is
 * named and never emitted is a promise nobody keeps, and a key that is
 * emitted and never named is dropped on the way out, in silence.
 *
 * The two sets drifted apart in both directions before this rule existed,
 * and both directions are silent by nature: a query over a key that never
 * arrives returns nothing, and an attribute that the redaction drops leaves
 * no trace at all. The rule that existed compared the README with the
 * vocabulary — two DECLARATIONS — so it could not see that neither of them
 * said the truth about what the export carries.
 *
 * ## Where the emitted set comes from
 *
 * It is derived from the product, not from a list written beside this rule:
 *
 *  - the HISTORICAL projection names its keys as literals in the three
 *    `TelemetryAttribute` constructors, which is the only shape it uses;
 *  - the LIVE path names its keys through the constants of
 *    `LiveSpanAttributes`, which is the only place where it names one.
 *
 * Both derivations read the sources of every module of the build, because
 * the guarantee is about the PRODUCT: a producer in a module that did not
 * exist when this rule was written still has to be counted.
 *
 * The live derivation also checks that every key the live vocabulary
 * declares HAS a producer outside its own declaration: a constant nobody
 * reads is a key the product does not emit, and the derivation would
 * otherwise count it as emitted.
 *
 * ## WHY THE COMMENTS ARE NOT READ
 *
 * The derivation reads CODE. A key that appears only inside a comment is an
 * example, not a producer, and an example is exactly what a vocabulary
 * accumulates until the example is the only thing left of it.
 */
class PublishedAttributeVocabularyTest {

    @Test
    fun `the published attribute vocabulary is exactly the set of attribute keys the product emits`() {
        val sources = mainSources()
        assertTrue(
            sources.isNotEmpty(),
            "no main source was found, so this rule proves nothing",
        )

        val emitted = emittedAttributeKeys(sources)
        val published = TelemetryIdentityTable.Default.allowedAttributeKeys()

        assertEquals(
            published,
            emitted,
            buildString {
                append("the published vocabulary and the attributes the product emits are two ")
                append("different sets. A key the vocabulary names and nothing emits is a promise ")
                append("nobody keeps; a key the product emits and the vocabulary does not name is ")
                append("dropped on the way out, in silence.\n")
                append("  published but not emitted: ${(published - emitted).sorted()}\n")
                append("  emitted but not published: ${(emitted - published).sorted()}\n")
                append("  published: ${published.sorted()}\n")
                append("  emitted: ${emitted.sorted()}")
            },
        )
    }

    @Test
    fun `an outcome is a status and not an attribute`() {
        val attributes = TelemetryIdentityTable.Default.attributeVocabulary
        val outcomeAttributes = attributes
            .filter { it.role == TelemetryVocabularyRole.OUTCOME }
            .map { it.key }

        assertEquals(
            emptyList<String>(),
            outcomeAttributes,
            "an attribute carries an outcome: how a span ended is the span's status, which the " +
                "exporter already carries, and a second copy of the same fact is a second place " +
                "for the two to disagree",
        )

        val outcomeDimensions = TelemetryIdentityTable.Default.dimensionVocabulary
            .filter { it.role == TelemetryVocabularyRole.OUTCOME }
            .map { it.key }

        assertEquals(
            listOf("status"),
            outcomeDimensions,
            "the outcome travels in the dimension that says what came out of the measurement, and " +
                "the role is not left without a key that carries it",
        )
    }

    /**
     * The attribute keys the product emits, derived from its sources.
     */
    private fun emittedAttributeKeys(sources: List<SourceFile>): Set<String> {
        val keys = linkedSetOf<String>()

        for (source in sources) {
            for (match in LITERAL_ATTRIBUTE.findAll(source.code)) {
                keys += match.groupValues[1]
            }
        }

        keys += liveAttributeKeys(sources)
        return keys
    }

    /**
     * The attribute keys the live path names, and the proof that each of
     * them has a producer.
     *
     * The live path names a key in two shapes: the constant that holds it,
     * and the builder that writes the key and the shape of its value. A
     * producer is either a reference to the constant or a call to the
     * builder, and a builder produces the constant its body writes.
     */
    private fun liveAttributeKeys(sources: List<SourceFile>): Set<String> {
        val declaration = sources.firstOrNull { it.code.contains(LIVE_VOCABULARY) }
        assertTrue(
            declaration != null,
            "the live vocabulary was not found, so the live half of the derivation proves nothing",
        )

        val code = objectBody(declaration!!.code)
        val declared = LIVE_CONSTANT.findAll(code)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val builders = LIVE_FUNCTION.findAll(code).toList()
        val builderNames = builders.map { it.groupValues[1] }.toSet()

        val outside = sources
            .filter { it !== declaration }
            .flatMap { source -> LIVE_REFERENCE.findAll(source.code).map { it.groupValues[1] } }
            .toSet()

        assertTrue(
            outside.all { it in declared.keys || it in builderNames },
            "the live path reads a key the live vocabulary does not declare: " +
                "${outside - declared.keys - builderNames}",
        )

        // A builder produces the constant its body writes, and a constant
        // referenced by name outside the declaration is named by a producer.
        val constantUse = Regex("\\b(${declared.keys.joinToString("|") { Regex.escape(it) }})\\b")
        val produced = linkedSetOf<String>()

        for ((index, builder) in builders.withIndex()) {
            if (builder.groupValues[1] !in outside) continue
            val bodyEnd = builders.getOrNull(index + 1)?.range?.first ?: code.length
            val body = code.substring(builder.range.last + 1, bodyEnd)
            produced += constantUse.findAll(body).map { it.groupValues[1] }
        }

        produced += declared.keys.filter { it in outside }

        assertEquals(
            declared.keys,
            produced,
            "the live vocabulary declares a key that nothing outside its own declaration names: " +
                "${declared.keys - produced}. A key nobody writes is a key the product does not " +
                "emit, and the derivation would count it as if it were",
        )

        return declared.values.toSet()
    }

    /**
     * The body of the live vocabulary, and nothing else.
     *
     * The object is closed by the brace that matches its own, so a
     * constructor of the object cannot borrow the body of the next
     * declaration in the file.
     */
    private fun objectBody(code: String): String {
        val start = code.indexOf(LIVE_VOCABULARY)
        if (start < 0) error("the live vocabulary was not found in the code")

        var depth = 0
        var index = start
        while (index < code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(start, index)
                }
            }
            index++
        }
        error("the live vocabulary is not closed")
    }

    /**
     * The main sources of every module of the build.
     *
     * The walk starts at the root of the build, so a module that appears
     * later is covered without touching this rule: the guarantee is about the
     * product, and the product is every module of the build.
     */
    private fun mainSources(): List<SourceFile> {
        val root = gradleRoot()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".kt") }
                .filter { it.toString().contains(MAIN_SOURCES) }
                .filter { !it.toString().contains("${File.separator}build${File.separator}") }
                .sorted()
                .map { SourceFile(it, Files.readString(it)) }
                .collect(Collectors.toList())
        }
    }

    /**
     * The root of the build, found by walking up from the working
     * directory, so the rule keeps working whether it runs from the module
     * directory or from the root of the repository.
     */
    private fun gradleRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate
            }
            candidate = candidate.parent
        }
        error("the Gradle root was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    /** One source file, its text, and its text without the comments. */
    private data class SourceFile(val path: Path, val text: String) {
        val code: String = stripComments(text)
    }

    private companion object {

        /** The path of the main sources of a module, inside the build root. */
        val MAIN_SOURCES = "${File.separator}src${File.separator}main${File.separator}kotlin${File.separator}"

        /** The declaration of the live vocabulary. */
        const val LIVE_VOCABULARY = "object LiveSpanAttributes"

        /**
         * An attribute constructed with a literal key.
         *
         * The pattern stops at the closing parenthesis of the group: the
         * quote that closes the literal is not part of what has to be
         * matched, and a raw string in this language cannot end with a
         * quote.
         */
        val LITERAL_ATTRIBUTE = Regex("""TelemetryAttribute\.(?:Text|Count|Flag)\(\s*"([^"]+)""")

        /** A constant of the live vocabulary, and the key it names. */
        val LIVE_CONSTANT = Regex("""const val ([A-Z_]+): String = "([^"]+)""")

        /** A builder of the live vocabulary. */
        val LIVE_FUNCTION = Regex("""fun ([a-z][A-Za-z0-9]*)\s*\(""")

        /** A reference to a member of the live vocabulary. */
        val LIVE_REFERENCE = Regex("""LiveSpanAttributes\.([A-Za-z_][A-Za-z0-9_]*)""")

        /**
         * The text of a source without its comments.
         *
         * The walk knows about the three kinds of literal this language
         * has — a string, a character and a raw string — so a sequence that
         * looks like the start of a comment inside one of them is not taken
         * for a comment.
         */
        fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0

            while (index < text.length) {
                val c = text[index]

                when {
                    text.startsWith("\"\"\"", index) -> {
                        out.append("\"\"\"")
                        index += 3
                        val end = text.indexOf("\"\"\"", index)
                        if (end < 0) {
                            out.append(text, index, text.length)
                            index = text.length
                        } else {
                            out.append(text, index, end + 3)
                            index = end + 3
                        }
                    }

                    c == '"' -> {
                        val end = endOfString(text, index, '"')
                        out.append(text, index, end)
                        index = end
                    }

                    c == '\'' -> {
                        val end = endOfString(text, index, '\'')
                        out.append(text, index, end)
                        index = end
                    }

                    text.startsWith("//", index) -> {
                        val end = text.indexOf('\n', index)
                        index = if (end < 0) text.length else end
                    }

                    text.startsWith("/*", index) -> {
                        val end = text.indexOf("*/", index + 2)
                        index = if (end < 0) text.length else end + 2
                    }

                    else -> {
                        out.append(c)
                        index++
                    }
                }
            }

            return out.toString()
        }

        /**
         * The end of a literal that opens at [start], including its closing
         * character.
         */
        private fun endOfString(text: String, start: Int, quote: Char): Int {
            var index = start + 1
            while (index < text.length) {
                val c = text[index]
                if (c == '\\') {
                    index += 2
                    continue
                }
                if (c == quote) return index + 1
                index++
            }
            return text.length
        }
    }
}
