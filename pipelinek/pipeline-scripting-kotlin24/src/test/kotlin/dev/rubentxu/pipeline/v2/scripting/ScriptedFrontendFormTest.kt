package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.scripting.ScriptedFrontendForm.Form
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which FORM a `.pipeline.kts` source takes is read from its STRUCTURE, not
 * from its text.
 *
 * The form used to be decided by a match over the script's text: the source
 * was the generator-level form iff it held a runtime-returning call AND its
 * text did not contain `pipeline {`. That put the decision in the hands of
 * whoever wrote the file, because a comment, a string or a different spacing
 * is enough to change the letters without changing the program. The same
 * structure then ran in two different forms depending on how it was spelled.
 *
 * The decision now reads what the compiler already parsed: the source is the
 * eager form iff it CALLS the DSL's entry point. These rows hold that line,
 * and the mutation that broke it — deciding by the regex again — turns the
 * first one red and leaves the second green.
 */
class ScriptedFrontendFormTest {
    @Test
    fun `the form follows the structure, not the text`() {
        // A generator-level program: a runtime-returning call and nothing
        // that declares the eager DSL.
        assertEquals(Form.GENERATOR_LEVEL, form(GENERATOR))
        // The SAME program with a comment that mentions the DSL entry point.
        // Prose is not a call, so the form does not move.
        assertEquals(Form.GENERATOR_LEVEL, form(GENERATOR_WITH_COMMENT))
        // The same program with a string that mentions it. A string is a
        // value, not a call.
        assertEquals(Form.GENERATOR_LEVEL, form(GENERATOR_WITH_STRING))
        // The eager DSL, declared by a call.
        assertEquals(Form.EAGER_DSL, form(EAGER))
        // The same call with another spacing between the name and the
        // lambda. Same program, same form.
        assertEquals(Form.EAGER_DSL, form(EAGER_WITH_SPACING))
        // The same call with an argument. A call with arguments is still the
        // call, and the text matcher could not see it.
        assertEquals(Form.EAGER_DSL, form(EAGER_WITH_ARGUMENT))
    }

    @Test
    fun `a source without runtime-returning calls is the eager form`() {
        // The DSL is declared, and the only mapped call returns nothing: there
        // is no generator-level body to run, so the eager form is the one.
        assertEquals(Form.EAGER_DSL, form(EAGER_WITHOUT_RUNTIME_CALL))
        // No call at all, so nothing could make this a generator-level body.
        assertEquals(Form.EAGER_DSL, form(WITHOUT_CALLS))
    }

    private fun form(text: String): Form =
        ScriptedFrontendForm.select(ScriptedSource(ScriptedSourceId("form-under-trial"), text))

    private companion object {
        /** A generator-level body: one runtime-returning call, no DSL. */
        const val GENERATOR = "val path = pwd()"

        /** The same body, with a comment that spells the DSL out. */
        val GENERATOR_WITH_COMMENT = """
            // the eager DSL entry point looks like this: pipeline { stages { stage { ... } } }
            val path = pwd()
        """.trimIndent()

        /** The same body, with a string that spells the DSL out. */
        val GENERATOR_WITH_STRING = """
            val label = "pipeline { stages { stage { ... } } }"
            val path = pwd()
        """.trimIndent()

        /** The eager DSL, declared by a call. */
        val EAGER = """
            pipeline { stages { stage("readfile") { pwd() } } }
        """.trimIndent()

        /** The same call, with a comment between the name and the lambda. */
        val EAGER_WITH_SPACING = """
            pipeline /* the same call */ { stages { stage("readfile") { pwd() } } }
        """.trimIndent()

        /** The same call, with an argument. */
        val EAGER_WITH_ARGUMENT = """
            pipeline(runtimeConfig) { stages { stage("readfile") { pwd() } } }
        """.trimIndent()

        /** The eager DSL, with a mapped call that returns nothing. */
        val EAGER_WITHOUT_RUNTIME_CALL = """
            pipeline { stages { stage("echo") { sh("echo hi") } } }
        """.trimIndent()

        /** No call at all. */
        const val WITHOUT_CALLS = "val answer = 42"
    }
}
