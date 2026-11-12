package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * A `.pipeline.kts` compiles against the class it extends.
 *
 * ## The guarantee
 *
 * The compiler generates the class of every script as a subclass of the definition's template,
 * so the template's class has to be on the script's classpath. Without it most scripts still
 * compile, which is why its absence hides: the base class is only RESOLVED when the compiler has
 * to look at the script's own receiver, and a fully qualified name at the top level of the script
 * (`java.io.File(...)`) is the common way that happens. Then the script is refused with
 * `Unresolved reference 'dev.rubentxu.pipeline.v2.scripting.definition.PipelineScript'`.
 *
 * ## Why each row is here
 *
 * - The top-level row is the shape the failure had: six scenarios of the catalogue write a file
 *   with `java.io.File(...)` before `pipeline { }`.
 * - The stage row pins the same name inside a body, where the receiver of the lambda is tried
 *   first and the script's receiver after it.
 *
 * The classpath of the call is the one the runner hands the host (the domain jar and the DSL jar)
 * and nothing more: the host, which chose the template, is the one that has to add it.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ScriptBaseClassCompilationTest {

    private val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    private fun errorsOf(script: String): List<ScriptingDiagnostic> =
        host.compileOnly(ScriptDefinition.inline(script, classpath = scriptClasspath))
            .diagnostics
            .filter { it.severity == ScriptDiagnosticSeverity.ERROR }

    @Test
    fun `a fully qualified name at the top level of a script compiles`() {
        val script = """
            val marker = java.io.File("marker.txt").name

            pipeline {
                stages {
                    stage("build") {
                        echo(marker)
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList<ScriptingDiagnostic>(), errorsOf(script))
    }

    @Test
    fun `a fully qualified name inside a stage compiles`() {
        val script = """
            pipeline {
                stages {
                    stage("build") {
                        echo(java.io.File("marker.txt").name)
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList<ScriptingDiagnostic>(), errorsOf(script))
    }
}
