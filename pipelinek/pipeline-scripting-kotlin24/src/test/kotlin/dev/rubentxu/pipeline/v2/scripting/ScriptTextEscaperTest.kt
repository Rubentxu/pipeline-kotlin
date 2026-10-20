package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The protection a bound credential gets from the script compiler.
 *
 * A pipeline that binds a credential writes its name into the script text —
 * `withCredentials(...) { sh("curl -u $USER:$PASS ...") }` — and the compiler
 * reads a `$NAME` inside a string as a template, not as text. Left alone, the
 * script stops compiling with an unresolved reference, and the pipeline fails
 * before it runs a single step. The escaper rewrites those references so the
 * *shell* expands them, at run time, against the value the binding resolved.
 *
 * The rewrite is the delicate part, and that is what these cases pin: it has to
 * fire inside a double-quoted string, and nowhere else. A reference that is not
 * a bound credential, a reference inside a template block, one inside a comment
 * and one inside a character literal must all survive untouched, or the escaper
 * would rewrite text the author meant for the compiler.
 *
 * The strings below are written with `${'$'}` because this file is itself
 * Kotlin: a bare `$` in a raw string is a template here too, and the subject
 * of the test would be swallowed by the very thing it describes.
 */
class ScriptTextEscaperTest {

    private val credentials = setOf("USERNAME", "PASSWORD")

    @Test
    fun `a bound credential reaches the shell as a literal`() {
        val script = """sh("echo ${'$'}USERNAME")"""

        val escaped = ScriptTextEscaper.escape(script, credentials)

        assertEquals("""sh("echo ${'$'}{'$'}USERNAME")""", escaped)
    }

    @Test
    fun `a name that is not bound is left to the compiler`() {
        // The escaper decides with the list of bound names, so a `$` that names
        // something else stays a template. A version that escaped every `$NAME`
        // would break the scripts that use templates on purpose.
        val script = """val label = "${'$'}buildVersion""""

        assertEquals(script, ScriptTextEscaper.escape(script, credentials))
    }

    @Test
    fun `a reference inside a template block is left alone`() {
        // Inside `${...}` the identifier is already a template the author wrote;
        // the escaper must not change what the compiler sees.
        val script = """sh("echo ${'$'}{user.name}")"""

        assertEquals(script, ScriptTextEscaper.escape(script, credentials))
    }

    @Test
    fun `a reference inside a comment is left alone`() {
        val line = """// see ${'$'}USERNAME for the account"""
        val block = """/* ${'$'}USERNAME is bound above */"""

        assertEquals(line, ScriptTextEscaper.escape(line, credentials))
        assertEquals(block, ScriptTextEscaper.escape(block, credentials))
    }

    @Test
    fun `a character literal does not swallow what follows it`() {
        // The scanner tracks character literals so it can tell them from strings.
        // The case that decides whether it does it properly is the text AFTER the
        // literal: a scanner that opened a literal and never closed it would treat
        // the rest of the script as one, and the credential below would be left
        // unescaped because it would look like it was inside a literal.
        val script = """val dollar = '${'$'}' ; sh("echo ${'$'}USERNAME")"""

        val escaped = ScriptTextEscaper.escape(script, credentials)

        assertEquals("""val dollar = '${'$'}' ; sh("echo ${'$'}{'$'}USERNAME")""", escaped)
    }

    @Test
    fun `an apostrophe inside the string does not end the string`() {
        // The scanner has to know where a string ends, and an apostrophe in the
        // middle of a sentence is the case that decides it: if the apostrophe
        // opened a character literal, everything after it would look like a
        // comment or a literal and the credential would be left unescaped.
        val script = """sh("don't echo ${'$'}USERNAME")"""

        val escaped = ScriptTextEscaper.escape(script, credentials)

        assertEquals("""sh("don't echo ${'$'}{'$'}USERNAME")""", escaped)
    }

    @Test
    fun `a dollar that names nothing is left alone`() {
        val lone = """sh("cost: ${'$'} 5")"""
        val doubled = """sh("cost: ${'$'}${'$'}")"""

        assertEquals(lone, ScriptTextEscaper.escape(lone, credentials))
        assertEquals(doubled, ScriptTextEscaper.escape(doubled, credentials))
    }

    @Test
    fun `a reference after a template block is still a reference`() {
        // The scanner counts braces so it can tell a template block from the text
        // around it. The count only means something if it comes back down: a
        // reference written after a block that closed is ordinary text again, and
        // a scanner that only ever counted upwards would leave it unescaped.
        val script = """sh("echo ${'$'}{user.name} and ${'$'}USERNAME")"""

        val escaped = ScriptTextEscaper.escape(script, credentials)

        assertEquals("""sh("echo ${'$'}{user.name} and ${'$'}{'$'}USERNAME")""", escaped)
    }

    @Test
    fun `escaping twice changes nothing the second time`() {
        // The escaper runs on text that may already carry an escaped reference,
        // and its own output is such text. If the second pass rewrote anything,
        // a pipeline re-read from a file that was rewritten once would drift.
        val script = """sh("echo ${'$'}USERNAME and ${'$'}{user.name}")"""

        val once = ScriptTextEscaper.escape(script, credentials)

        assertEquals(once, ScriptTextEscaper.escape(once, credentials))
    }
}
