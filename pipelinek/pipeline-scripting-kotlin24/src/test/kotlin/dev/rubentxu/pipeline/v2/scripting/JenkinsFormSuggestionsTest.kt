package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The recognition of a form of Jenkins, measured on the source.
 *
 * ## The guarantee
 *
 * The suggestion depends on the TOKEN the compiler pointed at and on nothing
 * else: the text of the diagnostic is not read, because the compiler may
 * reword it, and the code it publishes carries no id. A token that is not one
 * of the declared shapes produces no suggestion, so a diagnostic about
 * something that is not a form of Jenkins keeps the words it came with.
 *
 * ## Why each row is here
 *
 * - The string of single quotes is the shape every translated Jenkinsfile
 *   meets first, and the string of double quotes is the case that must NOT be
 *   named: it is the string of this product already.
 * - The named argument of a colon is the second shape, and the colon of an
 *   object expression is the case that looks like it and is not: `object` is
 *   not an argument, and a suggestion that named it would send the author to
 *   write nonsense.
 * - The property of the environment is the third shape, and the suggestion
 *   names the property the author wrote.
 * - The dollar and the escaped quote are the two escapes that do not travel
 *   from a string of Groovy to a string of Kotlin as they are.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JenkinsFormSuggestionsTest {

    @Test
    fun `the string of single quotes is named as the string of double quotes`() {
        val source = "sh('make')"

        assertEquals(
            "In PipelineK: a string is written between double quotes: \"make\".",
            suggestionAt(source, source.indexOf('\'')),
            "the diagnostic that lands on the quote does not say what to write",
        )
    }

    @Test
    fun `the string of double quotes is not a form of Jenkins`() {
        val source = "sh(\"make\")"

        assertNull(
            suggestionAt(source, source.indexOf('"')),
            "the string of this product was named as a form of Jenkins",
        )
    }

    @Test
    fun `the named argument of a colon is named as the one of an equals`() {
        val source = "timeout(time: 30)"

        assertEquals(
            "In PipelineK: the named argument \"time\" is written with \"=\", not with \":\".",
            suggestionAt(source, source.indexOf(':')),
            "the diagnostic that lands on the colon does not say what to write",
        )
    }

    @Test
    fun `the colon of an object expression is not a named argument`() {
        val withSpace = "foo(object : Bar())"
        val withoutSpace = "foo(object: Bar())"

        assertNull(
            suggestionAt(withSpace, withSpace.indexOf(':')),
            "the colon of an object expression was named as a named argument",
        )
        assertNull(
            suggestionAt(withoutSpace, withoutSpace.indexOf(':')),
            "the word that introduces an object expression was named as an argument",
        )
    }

    @Test
    fun `the property of the environment is named as the call that reads it`() {
        val source = "sh(\"echo \${env.BRANCH_NAME}\")"

        assertEquals(
            "In PipelineK: the variable of the environment is read with env(\"BRANCH_NAME\").",
            suggestionAt(source, source.indexOf("env")),
            "the diagnostic that lands on the env does not say what to write",
        )
    }

    @Test
    fun `the dollar of the string of Jenkins is escaped on the way`() {
        val source = "sh('\$HOME')"

        assertEquals(
            "In PipelineK: a string is written between double quotes: \"\\\$HOME\".",
            suggestionAt(source, source.indexOf('\'')),
            "the dollar of the string would interpolate in the string of this product",
        )
    }

    @Test
    fun `the escaped quote of Jenkins is a quote on the way`() {
        val source = "sh('it\\'s')"

        assertEquals(
            "In PipelineK: a string is written between double quotes: \"it's\".",
            suggestionAt(source, source.indexOf('\'')),
            "the escaped quote of Groovy has no meaning in a string of Kotlin",
        )
    }

    @Test
    fun `a token that is no form of Jenkins has no suggestion`() {
        val source = "sh(\"make\")"

        assertNull(suggestionAt(source, 0), "the name of the step was named as a form of Jenkins")
        assertNull(suggestionAt(source, 5), "the word inside the string was named as a form of Jenkins")
    }

    /** The suggestion for the token that starts at [index] of the [source]. */
    private fun suggestionAt(source: String, index: Int): String? =
        JenkinsFormSuggestions.suggestionFor(source, 1, index + 1)
}
