package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StepBodyScope
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The diagnostic that names a declaration of the stage written inside a body.
 *
 * ## The guarantee under trial
 *
 * That a declaration of the stage —its configuration, its gates and the blocks
 * it opens— written inside a body that carries steps produces a diagnostic that
 * says WHICH declaration it was, WHERE it was written and WHAT to write
 * instead.
 *
 * ## What the instrument is
 *
 * The compiler. The advice is derived from what the compiler REFUSED and from
 * the position it refused at: a call the compiler resolved produces no
 * diagnostic, so nothing is said about a text, about a comment, about a
 * function with the same name or about a plugin's step.
 *
 * ## The three things a row checks
 *
 * - the FILE the diagnostic names is the script that was compiled;
 * - the POSITION is the one of the name the author wrote;
 * - the MESSAGE names the declaration and the action.
 *
 * ## What this file does NOT freeze
 *
 * The shape of the DSL, the shape of the diagnostics and the wording of the
 * compiler. It freezes the FACT that the advice is there and that it names the
 * declaration.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class StageDirectiveDiagnosticTest {

    @TempDir
    lateinit var dir: Path

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    private fun compile(name: String, text: String): ScriptCompilationResult {
        val script = dir.resolve(name)
        Files.writeString(script, text)
        val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)
        return host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
    }

    /**
     * One declaration of the stage, and the call that writes it.
     *
     * @param name the name the author writes, which is what the advice names.
     * @param call the call, with the arguments the declaration takes.
     */
    private class Declaration(val name: String, val call: String)

    /**
     * One form the language retired, and the call that writes it.
     *
     * @param name the name the author writes, which is what the advice names.
     * @param call the call, as the retired form was written.
     */
    private class Retired(val name: String, val call: String)

    /**
     * The declarations of the stage, each with a call that WOULD compile at the
     * stage level.
     *
     * The list is the surface the DSL declares and a body does not, and every
     * entry is written with the arguments its declaration takes: the compiler
     * resolves the call against the stage's member and refuses it because the
     * member is not accessible from a body.
     */
    private val declarations: List<Declaration> = listOf(
        Declaration("directives", "directives { }"),
        Declaration("environment", "environment { }"),
        Declaration("options", "options { }"),
        Declaration("parallel", "parallel { }"),
        Declaration("post", "post { }"),
        Declaration("script", "script { }"),
        Declaration("steps", "steps { }"),
        Declaration("toStageBuilder", "toStageBuilder()"),
        Declaration("whenEnvIs", "whenEnvIs(\"A\", \"B\")"),
        Declaration("whenEnvPresent", "whenEnvPresent(\"A\")"),
        Declaration(
            "whenGate",
            "whenGate(dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate.AlwaysTrue)",
        ),
    )

    /**
     * Every declaration of the stage is named by the advice.
     *
     * The row compiles a script that writes the declaration inside a body, and
     * it checks the three things the advice owes: the file, the position and
     * the action.
     */
    @TestFactory
    fun `the advice names the declaration of the stage written inside a body`(): List<DynamicTest> =
        declarations.map { declaration ->
            DynamicTest.dynamicTest(declaration.name) {
                val script = scriptWithBody(declaration.call)
                val result = compile(
                    "stage-directive-${declaration.name}.pipeline.kts",
                    script,
                )

                val diagnostic = requireNotNull(
                    result.diagnostics.singleOrNull {
                        it.severity == ScriptDiagnosticSeverity.ERROR
                    },
                ) { "the declaration written inside a body is not refused: ${result.diagnostics}" }
                val (line, column) = positionOf(script, declaration.call)
                assertEquals(
                    line,
                    diagnostic.line,
                    "the diagnostic does not point at the line the declaration was written in",
                )
                assertEquals(
                    column,
                    diagnostic.column,
                    "the diagnostic does not point at the name the author wrote",
                )
                assertTrue(
                    diagnostic.message.contains("In PipelineK: `${declaration.name}` belongs to the STAGE"),
                    "the advice does not name the declaration that was written: ${diagnostic.message}",
                )
                assertTrue(
                    diagnostic.message.contains("outside every block that carries steps"),
                    "the advice does not say what to write instead: ${diagnostic.message}",
                )
                assertTrue(
                    diagnostic.path.endsWith("stage-directive-${declaration.name}.pipeline.kts"),
                    "the diagnostic does not name the file that was compiled: ${diagnostic.path}",
                )
            }
        }

    /**
     * The advice is given in EVERY capsule that carries steps.
     *
     * The body of a stage, the body of a script and the body of a plugin's
     * block are three places where the same declaration can be written, and
     * all three refuse it in the same way.
     */
    @TestFactory
    fun `the advice is given in every capsule that carries steps`(): List<DynamicTest> {
        val capsules = listOf(
            Capsule("steps", "steps { %s }", ""),
            Capsule("script", "script { %s }", ""),
            Capsule(
                "registryBlock",
                "registryBlock(PluginStepId(\"example.block\"), EncodedStepValue(\"{}\")) { %s }",
                PLUGIN_IMPORTS,
            ),
        )
        val written = listOf(
            Declaration("options", "options { }"),
            Declaration("post", "post { }"),
        )

        return capsules.flatMap { capsule ->
            written.map { declaration ->
                DynamicTest.dynamicTest("${capsule.name} holds ${declaration.name}") {
                    val script = scriptWithCapsule(
                        capsule.template.format(declaration.call),
                        capsule.imports,
                    )
                    val result = compile(
                        "stage-directive-${capsule.name}-${declaration.name}.pipeline.kts",
                        script,
                    )

                    val diagnostic = requireNotNull(
                        result.diagnostics.singleOrNull {
                            it.severity == ScriptDiagnosticSeverity.ERROR
                        },
                    ) { "the declaration written inside the capsule is not refused: ${result.diagnostics}" }
                    assertTrue(
                        diagnostic.message.contains("In PipelineK: `${declaration.name}` belongs to the STAGE"),
                        "the advice does not name the declaration that was written: ${diagnostic.message}",
                    )
                }
            }
        }
    }

    /**
     * A form the language RETIRED is named by the advice.
     *
     * The compiler says "unresolved reference" and stops there: the author is
     * left with a name that never existed and no way of knowing that the
     * requirement of the stage is a directive of the plan.
     */
    @TestFactory
    fun `the advice names a form the language retired`(): List<DynamicTest> =
        listOf(
            Retired("agent", "agent { }"),
            Retired("node", "node(\"label\") { }"),
        ).map { retired ->
            DynamicTest.dynamicTest(retired.name) {
                val script = scriptWithBody(retired.call)
                val result = compile("stage-retired-${retired.name}.pipeline.kts", script)

                val diagnostic = requireNotNull(
                    result.diagnostics.singleOrNull {
                        it.severity == ScriptDiagnosticSeverity.ERROR
                    },
                ) { "the retired form is not refused: ${result.diagnostics}" }
                assertTrue(
                    diagnostic.message.contains("In PipelineK: `${retired.name}` is not a declaration anymore"),
                    "the advice does not name the form that was written: ${diagnostic.message}",
                )
            }
        }

    /**
     * The same form, written at the stage level, compiles without a warning.
     *
     * The advice is about a place, and the same declaration in the right place
     * is not a mistake: a diagnostic there would send the author to change a
     * script that is already right.
     */
    @Test
    fun `the same form at the stage level compiles without a warning`() {
        val result = compile("stage-directive-valid.pipeline.kts", VALID_AT_STAGE)

        assertTrue(
            result.diagnostics.isEmpty(),
            "the forms of the stage produce a diagnostic at the stage level: ${result.diagnostics}",
        )
    }

    /**
     * What the compiler RESOLVED produces no diagnostic and no advice.
     *
     * These are the four things that carry a name the stage also declares, or
     * a name of their own, without being a misplaced declaration: a text, a
     * comment, a function with the same name and a plugin's step.
     */
    @Test
    fun `a text, a comment, a function with the same name and a plugin step are not advice`() {
        val result = compile("stage-directive-resolved.pipeline.kts", RESOLVED)

        assertTrue(
            result.diagnostics.isEmpty(),
            "what the compiler resolved produces a diagnostic: ${result.diagnostics}",
        )
    }

    /**
     * A reference to a value is not a call, and it is not advised.
     *
     * The advice of a call would send an author who is already in the stage to
     * the stage, and the compiler's own message already says what is wrong
     * with the reference.
     */
    @Test
    fun `a reference to a value is not a call and receives no advice`() {
        val result = compile("stage-directive-value.pipeline.kts", VALUE_REFERENCE)

        val diagnostic = requireNotNull(
            result.diagnostics.singleOrNull {
                it.severity == ScriptDiagnosticSeverity.ERROR
            },
        ) { "the reference to a value is not refused: ${result.diagnostics}" }
        assertFalse(
            diagnostic.message.contains("In PipelineK:"),
            "a reference to a value receives the advice of a call: ${diagnostic.message}",
        )
    }

    /**
     * The advice covers EXACTLY the declarations the stage declares.
     *
     * The table of the advice is compared with the surface of the DSL, read
     * from the classes: a declaration the stage gains and the table does not
     * declare is a declaration whose author gets no advice, and it is caught
     * here rather than in a report.
     */
    @Test
    fun `the advice covers exactly the declarations the stage declares`() {
        val ofTheStage = memberNames(StageScope::class.java) - memberNames(StepBodyScope::class.java)

        assertEquals(
            ofTheStage + "environment",
            StageDirectiveAdvice.DECLARATIONS_OF_THE_STAGE,
            "the advice does not cover the declarations the stage declares, or it covers " +
                "declarations the stage does not declare",
        )
    }

    /**
     * A retired form is not a declaration of the stage.
     *
     * The advice of a retired form says that the form is gone, and that advice
     * would be a lie the day the stage declares it again.
     */
    @Test
    fun `a retired form is not a declaration of the stage`() {
        val ofTheStage = memberNames(StageScope::class.java)

        assertTrue(
            StageDirectiveAdvice.RETIRED.keys.none { it in ofTheStage },
            "the advice of a retired form is given to a declaration the stage declares: " +
                "${StageDirectiveAdvice.RETIRED.keys.intersect(ofTheStage)}",
        )
    }

    /**
     * The PUBLIC methods of a type, without the ones the compiler adds.
     *
     * The synthetic `$default` bridges carry a value by default, and they are
     * not declarations anybody writes: they are excluded, and so are the
     * members of `Any`.
     */
    private fun publicMethods(type: Class<*>): List<Method> =
        type.methods
            .filter { !it.isSynthetic && !it.name.contains('$') && it.declaringClass != Any::class.java }
            .toList()

    /**
     * The NAMES of the public methods of a type.
     *
     * The name of a member that takes a value class carries the suffix the
     * compiler adds (`git-XXXX`); the name an author writes is the one before
     * the suffix.
     */
    private fun memberNames(type: Class<*>): Set<String> =
        publicMethods(type).map { it.name.substringBefore('-') }.toSet()

    /**
     * The line and the column of the LAST occurrence of [call] in the [script].
     *
     * The last one is the call and not the capsule that opens it: a capsule
     * and the declaration it holds can be written with the same words.
     */
    private fun positionOf(script: String, call: String): Pair<Int, Int> {
        val index = script.lastIndexOf(call)
        assertTrue(index >= 0, "the script does not carry the call it was built with")
        val line = script.substring(0, index).count { it == '\n' } + 1
        val column = index - script.lastIndexOf('\n', index)
        return line to column
    }

    /**
     * A script that opens [capsule] in the body of a stage.
     *
     * The capsule is written where the stage writes it, and the declaration
     * the row is about is written INSIDE it: a body that carries steps is what
     * refuses the declaration, and the capsule is what carries it.
     */
    private fun scriptWithCapsule(capsule: String, imports: String = ""): String {
        val preamble = if (imports.isEmpty()) "" else "$imports\n"
        return "$preamble" + """
            pipeline {
                stages {
                    stage("s") {
                        $capsule
                    }
                }
            }
        """.trimIndent()
    }

    /**
     * A script that writes [inside] inside the body of the steps of a stage.
     */
    private fun scriptWithBody(inside: String, imports: String = ""): String {
        val preamble = if (imports.isEmpty()) "" else "$imports\n"
        return "$preamble" + """
            pipeline {
                stages {
                    stage("s") {
                        steps {
                            $inside
                        }
                    }
                }
            }
        """.trimIndent()
    }

    /**
     * One capsule that carries steps.
     *
     * @param name the name of the capsule, for the report of the run.
     * @param template the capsule, with the place of the call inside it.
     * @param imports the imports the capsule needs, when it needs any.
     */
    private class Capsule(val name: String, val template: String, val imports: String)

    private companion object {

        /**
         * The imports the capsule of a plugin's block needs.
         */
        const val PLUGIN_IMPORTS = "import dev.rubentxu.pipeline.v2.domain.PluginStepId\n" +
            "import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue"

        /**
         * The forms of the stage, written where they belong.
         */
        val VALID_AT_STAGE = """
            pipeline {
                stages {
                    stage("s") {
                        whenEnvIs("A", "B")
                        whenEnvPresent("C")
                        whenGate(dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate.AlwaysTrue)
                        options { }
                        post { }
                        environment { }
                        directives { }
                        sh("echo hello")
                    }
                }
            }
        """.trimIndent()

        /**
         * What the compiler RESOLVES, and what therefore earns no advice.
         */
        val RESOLVED = """
            import dev.rubentxu.pipeline.v2.domain.PluginStepId
            import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue

            fun options() { }

            pipeline {
                stages {
                    stage("s") {
                        steps {
                            options()
                            echo("options { }")
                            // options { } is not written here
                            registryStep(PluginStepId("example.step"), EncodedStepValue("{}"))
                            sh("echo hello")
                        }
                    }
                }
            }
        """.trimIndent()

        /**
         * A reference to a value, at the stage level.
         */
        val VALUE_REFERENCE = """
            pipeline {
                stages {
                    stage("s") {
                        val x = options
                        sh("echo hello")
                    }
                }
            }
        """.trimIndent()
    }
}
