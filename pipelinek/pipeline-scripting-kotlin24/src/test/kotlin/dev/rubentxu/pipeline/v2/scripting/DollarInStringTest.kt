package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.step.JenkinsDivergences
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The dollar of a string, as the COMPILER reads it and as the catalogue publishes it.
 *
 * ## The guarantee
 *
 * That a string of several lines reads a `$NAME` as a TEMPLATE of Kotlin, the
 * same way a string between double quotes does, and that the catalogue says
 * so — and says how a dollar that has to reach the shell is written in each of
 * the two.
 *
 * ## Why this instrument COMPILES a script
 *
 * Because the fact is about what the compiler does with the source, and no
 * other instrument can answer it: whether a dollar opens a template is decided
 * by the Kotlin compiler, and a text that talks about it is a text about a
 * rule the text does not apply.
 *
 * ## Why the published text is read HERE
 *
 * Because the published text has to answer the same question the compiler
 * answers, and the question is about the SAME two spellings: the row that
 * reads the published reason asks for the spellings it names, and the rows
 * above compile those spellings, so a published spelling that does not
 * compile is a published lie.
 *
 * ## What this does not freeze
 *
 * The words the published text uses: the rows ask for the SPELLINGS it names
 * and for the truth about the interpolation, and not for a phrasing.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class DollarInStringTest {

    @TempDir
    lateinit var dir: Path

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    /**
     * A bare dollar in a string of several lines is a TEMPLATE of Kotlin.
     *
     * The string of several lines is a raw string, and a raw string reads a
     * dollar like any other string does. The script does not compile, and the
     * compiler names the reference the dollar opened.
     */
    @Test
    fun `a bare dollar in a string of several lines is a template of Kotlin`() {
        val result = compile("bare.pipeline.kts", scriptWith(bareDollarArgument))

        assertFalse(
            result.isSuccess,
            "the bare dollar of a string of several lines compiled, and it is a template: ${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.any { it.message.contains("Unresolved reference") && it.message.contains("HOME") },
            "the compiler did not name the reference the dollar opened: ${result.diagnostics}",
        )
    }

    /**
     * The literal dollar of a string of several lines reaches the shell.
     *
     * The spelling is the one Kotlin uses for a dollar that is not a template:
     * a dollar literal, in braces, next to the name.
     */
    @Test
    fun `the literal dollar of a string of several lines reaches the shell`() {
        val result = compile("literal.pipeline.kts", scriptWith(literalDollarArgument))

        assertTrue(result.isSuccess, "the script with the literal dollar did not compile: ${result.diagnostics}")
        assertEquals(
            "echo \$HOME",
            commandOf(result),
            "the command does not carry the dollar to the shell",
        )
    }

    /**
     * The escaped dollar of a string between double quotes reaches the shell.
     *
     * A string between double quotes processes its escapes, so the dollar is
     * escaped there.
     */
    @Test
    fun `the escaped dollar of a string between double quotes reaches the shell`() {
        val result = compile("escaped.pipeline.kts", scriptWith(escapedDollarArgument))

        assertTrue(result.isSuccess, "the script with the escaped dollar did not compile: ${result.diagnostics}")
        assertEquals(
            "echo \$HOME",
            commandOf(result),
            "the command does not carry the dollar to the shell",
        )
    }

    /**
     * The published divergence names the two spellings that reach the shell.
     *
     * The catalogue publishes the difference between the two products, and a
     * reader who comes from Jenkins needs the spelling of THIS product: the
     * one of a string between double quotes and the one of a string of several
     * lines, which are not the same because a raw string processes no escape.
     */
    @Test
    fun `the published divergence names the two spellings that reach the shell`() {
        val reason = reasonOf(DIVERGENCE_NAME)

        assertTrue(
            reason.contains(escapedDollar),
            "the published divergence does not name the spelling of a string between double quotes: $reason",
        )
        assertTrue(
            reason.contains(literalDollar),
            "the published divergence does not name the spelling of a string of several lines: $reason",
        )
        assertFalse(
            reason.contains(DENIAL),
            "the published divergence still says that a string of several lines does not interpolate: $reason",
        )
    }

    /**
     * The script that carries the argument of a `sh`, compiled.
     */
    private fun compile(name: String, text: String): ScriptCompilationResult {
        val script = dir.resolve(name)
        Files.writeString(script, text)
        val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)
        return host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
    }

    /**
     * The command the plan carries, which is the command the shell receives.
     */
    private fun commandOf(result: ScriptCompilationResult): String {
        val spec = result.value as PipelineSpec
        return spec.stages.single().steps.filterIsInstance<StepSpec.Shell>().single().command
    }

    /**
     * The reason the catalogue publishes for the divergence named [name].
     */
    private fun reasonOf(name: String): String =
        requireNotNull(JenkinsDivergences.global().firstOrNull { it.name == name }) {
            "the catalogue publishes no divergence named '$name'"
        }.reason

    /**
     * The script that runs the argument of a `sh`.
     *
     * The argument travels as a value and not as text of this file, because the
     * argument of a `sh` carries the quotes of the string it is: a text that
     * held them would have to escape them, and the escape would be the thing
     * under trial.
     */
    private fun scriptWith(argument: String): String = """
        pipeline {
            stages {
                stage("build") {
                    sh($argument)
                }
            }
        }
    """.trimIndent()

    private companion object {
        /** The name of the divergence that publishes the dollar of a string. */
        const val DIVERGENCE_NAME = "the dollar of a string"

        /**
         * The denial the published text carried: a string of several lines reads a
         * dollar as a template, so the denial is the opposite of the truth.
         */
        const val DENIAL = "does not interpolate at all"

        /**
         * The characters of a dollar that Kotlin reads as text and not as the start
         * of a template.
         *
         * They are built out of pieces because the text they spell IS a template:
         * a dollar literal, in braces, next to a dollar literal in quotes.
         */
        const val literalDollar: String = "\$" + "{'" + "\$" + "'}"

        /**
         * The characters of a dollar that a string between double quotes carries
         * escaped: the compiler processes that escape and the shell receives the
         * dollar.
         */
        const val escapedDollar: String = "\\" + "\$"

        /**
         * The argument of a `sh` whose command is a string of several lines with a
         * dollar that the compiler reads as a template.
         */
        const val bareDollarArgument: String = "\"\"\"" + "echo \$HOME" + "\"\"\""

        /**
         * The argument of a `sh` whose command is a string of several lines with the
         * dollar written the way Kotlin spells a dollar that is not a template.
         */
        const val literalDollarArgument: String = "\"\"\"" + "echo " + literalDollar + "HOME" + "\"\"\""

        /**
         * The argument of a `sh` whose command is a string between double quotes with
         * the dollar escaped.
         */
        const val escapedDollarArgument: String = "\"" + "echo " + escapedDollar + "HOME" + "\""
    }
}
