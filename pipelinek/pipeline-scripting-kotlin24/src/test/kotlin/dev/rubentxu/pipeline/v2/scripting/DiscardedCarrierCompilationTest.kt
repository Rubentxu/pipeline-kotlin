package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * A `.pipeline.kts` that discards the carrier of a pure builder is refused by the COMPILER.
 *
 * ## The guarantee
 *
 * `scmGit(...)` builds a checkout and emits nothing: the checkout happens only when the carrier
 * reaches `checkout(...)`. A `scmGit(...)` in statement position gives the author a checkout they
 * silently did not get. The definition of a `.pipeline.kts` turns Kotlin's return-value checker
 * on and raises its diagnostics to errors, so `validate --compile-only` refuses that script with
 * the line and the column of the call, before anything runs.
 *
 * ## Why each row is here
 *
 * - The discard INSIDE a `stage { }` is the case an author writes. Its diagnostic is the one of a
 *   lambda body (`RETURN_VALUE_NOT_USED_COERCION`), and it is the one that stays a warning when
 *   only the plain diagnostic is raised.
 * - The discard inside a helper FUNCTION is the other diagnostic (`RETURN_VALUE_NOT_USED`).
 *   Removing either `-Xwarning-level` option from the definition turns one of these two rows red.
 * - The control row consumes the same carrier. Without it, a definition that refused every script
 *   would pass the first two rows.
 * - The `pwd()` row pins what the rule does NOT refuse: a scripted runtime call already emits its
 *   step, so discarding its result loses nothing, and a Jenkinsfile that calls `pwd()` in statement
 *   position still compiles.
 *
 * The compile-only path is the instrument: it is the compilation every run performs, without
 * running the author's body. The script classpath is the one the runner hands the host (the
 * domain jar and the DSL jar, found on this JVM's classpath the same way), and the cache is
 * disabled so each row compiles.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DiscardedCarrierCompilationTest {

    private val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    private fun compileOnly(script: String): ScriptCompilationResult =
        host.compileOnly(ScriptDefinition.inline(script, classpath = scriptClasspath))

    private fun errorsOf(script: String): List<ScriptingDiagnostic> =
        compileOnly(script).diagnostics.filter { it.severity == ScriptDiagnosticSeverity.ERROR }

    private fun assertRefusedAt(script: String, line: Int, column: Int) {
        val result = compileOnly(script)
        val errors = result.diagnostics.filter { it.severity == ScriptDiagnosticSeverity.ERROR }

        assertTrue(
            !result.isSuccess,
            "a script that discards a carrier compiled: ${result.diagnostics}",
        )
        assertEquals(
            listOf(line to column),
            errors.map { it.line to it.column },
            "the compiler did not refuse the discard, and only the discard, at its call: ${result.diagnostics}",
        )
    }

    @Test
    fun `a carrier discarded inside a stage is a compile error at the call`() {
        assertRefusedAt(
            """
            pipeline {
                stages {
                    stage("build") {
                        scmGit(url = "https://example.invalid/r.git")
                        sh("make")
                    }
                }
            }
            """.trimIndent(),
            line = 4,
            column = 13,
        )
    }

    @Test
    fun `a carrier discarded inside a helper function is a compile error at the call`() {
        assertRefusedAt(
            """
            fun StageScope.fetchSources() {
                scmGit(url = "https://example.invalid/r.git")
            }

            pipeline {
                stages {
                    stage("build") {
                        fetchSources()
                    }
                }
            }
            """.trimIndent(),
            line = 2,
            column = 5,
        )
    }

    @Test
    fun `a carrier that reaches checkout compiles`() {
        val script = """
            pipeline {
                stages {
                    stage("build") {
                        checkout(scmGit(url = "https://example.invalid/r.git"))
                        sh("make")
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList<ScriptingDiagnostic>(), errorsOf(script))
    }

    @Test
    fun `a scripted runtime call in statement position compiles`() {
        val script = """
            pipeline {
                stages {
                    stage("where") {
                        pwd()
                        isUnix()
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList<ScriptingDiagnostic>(), errorsOf(script))
    }
}
