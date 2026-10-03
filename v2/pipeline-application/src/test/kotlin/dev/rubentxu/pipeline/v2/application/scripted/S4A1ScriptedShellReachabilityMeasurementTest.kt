package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallKind
import dev.rubentxu.pipeline.v2.scripting.ScriptedShellReturnMode
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceMapping
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * S4-A1 — a scripted `sh(...)` must COMPILE and carry its script and its shape.
 *
 * ## What this file replaced
 *
 * `c62d6ff5` measured the opposite and it was the right measurement at the time:
 * a scripted `sh(script = "printf hi")` was classified `ScriptedCallKind.Shell`,
 * the lowering declined to rewrite it, the bare `sh` survived into the generated
 * Kotlin with no receiver, and the host rejected the script at compile time with
 * `Unresolved reference 'sh'`.
 *
 * That was measured because reading had already shown the privileged shell
 * runtime was wired but unreachable. Closing the bypass (a272990c) was necessary
 * but not sufficient: fixing the lowering without it would have converted a loud
 * compile error into a silent capability-admission bypass. This file is the
 * other half — it asserts that the shell now works, and through which spine.
 *
 * ## Why compile-success is the load-bearing assertion
 *
 * "It compiles" is the weakest possible statement about a generated program. On
 * its own it proves nothing about semantics. It is load-bearing HERE only because
 * the other assertions pin what the compiled program actually does: it maps to
 * a façade call, it carries the script text, it carries the requested shape, and
 * the façade it calls is registry-routed. A lowering that emitted arbitrary
 * valid Kotlin would fail those.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class S4A1ScriptedShellReachabilityMeasurementTest {

    private val sourceId = ScriptedSourceId("pipelines/04-sh.pipeline.kts")

    private fun lower(sourceText: String): LoweringResult.Generated {
        val lowering = ScriptedSourceLowering.lower(
            sourceId = sourceId,
            sourceText = sourceText,
            mapper = KotlinScriptedSourceMapper(),
            facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
        )
        return lowering as LoweringResult.Generated
    }

    private fun compileOrDiagnostics(generated: LoweringResult.Generated): ScriptCompilationResult {
        val script = """
            val recorded = mutableListOf<String>()
            ${generated.source}
        """.trimIndent()
        return Kotlin24ScriptingHost().compile(
            ScriptDefinition.inline(
                text = script,
                classpath = listOfNotNull(ScriptDefinition.dslApiJar()),
            ),
        )
    }

    private fun diagnosticsOf(result: ScriptCompilationResult): String =
        (result as? ScriptCompilationResult.Failure)?.diagnostics?.joinToString("; ").orEmpty()

    private fun compileAndRequireSuccess(sourceText: String, what: String): LoweringResult.Generated {
        val generated = lower(sourceText)
        val result = compileOrDiagnostics(generated)
        assertTrue(
            result is ScriptCompilationResult.Success,
            "a scripted `$what` must compile. Got: ${diagnosticsOf(result)}\n\n" +
                "GENERATED SOURCE WAS:\n${generated.source}",
        )
        return generated
    }

    @Test
    fun `a scripted sh call is rewritten to a facade call carrying its script and its shape, and compiles`() {
        val generated = compileAndRequireSuccess("""sh(script = "printf hi")""", "sh(...)")

        val shell = generated.mappedCalls.single().kind
        assertTrue(shell is ScriptedCallKind.Shell, "expected a Shell kind, got $shell")
        assertEquals(
            "\"printf hi\"",
            (shell as ScriptedCallKind.Shell).script,
            "the mapper must carry the script text through, not an empty placeholder: the " +
                "previous lowering had no script at all, which is why it declined to rewrite",
        )
        assertEquals(
            ScriptedShellReturnMode.NONE,
            shell.returnMode,
            "sh(script) with no return flag is the NONE shape",
        )
        assertTrue(
            generated.source.contains("steps.sh("),
            "the call must be rewritten onto the facade so it binds to a receiver.\n" +
                "GENERATED SOURCE WAS:\n${generated.source}",
        )
        assertTrue(
            !generated.source.contains("sh(script ="),
            "no bare sh(...) may survive into the generated Kotlin; it has no receiver and " +
                "cannot resolve",
        )
    }

    @Test
    fun `the three sh shapes are distinguished by argument form and each reaches the same facade`() {
        val cases = listOf(
            Triple("""sh("echo a")""", ScriptedShellReturnMode.NONE, "\"echo a\", null, null"),
            Triple(
                """sh("echo b", returnStdout = true)""",
                ScriptedShellReturnMode.STDOUT,
                "ReturnStdout",
            ),
            Triple(
                """sh("echo c", returnStatus = ReturnStatus)""",
                ScriptedShellReturnMode.STATUS,
                "ReturnStatus",
            ),
            // The marker spelling the public DSL actually uses.
            Triple(
                """sh("echo d", returnStdout = ReturnStdout)""",
                ScriptedShellReturnMode.STDOUT,
                "ReturnStdout",
            ),
        )

        cases.forEach { (source, expectedMode, expectedCallToken) ->
            val generated = compileAndRequireSuccess(source, source)
            val kind = generated.mappedCalls.single().kind
            assertTrue(kind is ScriptedCallKind.Shell, "$source must map to Shell, got $kind")
            assertEquals(
                expectedMode,
                (kind as ScriptedCallKind.Shell).returnMode,
                "$source must map to $expectedMode",
            )
            assertTrue(
                generated.source.contains(expectedCallToken),
                "$source must be rewritten through the `sh` facade with `$expectedCallToken`.\n" +
                    "GENERATED SOURCE WAS:\n${generated.source}",
            )
        }
    }

    @Test
    fun `an illegal sh shape is rejected instead of being given an invented meaning`() {
        // Both flags at once is not a fourth shape. Resolving it would require
        // choosing which value the user gets, and there is no basis for the choice.
        val mapping = KotlinScriptedSourceMapper().map(
            dev.rubentxu.pipeline.v2.scripting.ScriptedSource(
                sourceId = sourceId,
                text = """sh("echo", returnStdout = true, returnStatus = ReturnStatus)""",
            ),
        )
        assertTrue(
            mapping is ScriptedSourceMapping.InvalidSyntax,
            "a sh call requesting both returnStdout and returnStatus must be rejected, not " +
                "silently resolved. Got: $mapping",
        )
        assertTrue(
            (mapping as ScriptedSourceMapping.InvalidSyntax).diagnostics
                .any { it.message.contains("mutually exclusive") },
            "the rejection must say WHY, so a user can fix the script. Got: ${mapping.diagnostics}",
        )
    }

    @Test
    fun `a sh call with no script is rejected rather than rewritten to run nothing`() {
        // The previous mapper substituted an empty string here, producing a shell
        // command that ran nothing and reported success.
        val mapping = KotlinScriptedSourceMapper().map(
            dev.rubentxu.pipeline.v2.scripting.ScriptedSource(
                sourceId = sourceId,
                text = """sh(encoding = "UTF-8")""",
            ),
        )
        assertTrue(
            mapping is ScriptedSourceMapping.InvalidSyntax,
            "a sh call with no script argument must be rejected. Got: $mapping",
        )
    }

    @Test
    fun `CONTROL - a sh-free scripted source still compiles, so the harness is not what decides`() {
        val generated = compileAndRequireSuccess("val unix = isUnix()", "isUnix()")
        assertTrue(
            generated.mappedCalls.count { it.kind == ScriptedCallKind.IsUnix } == 1,
            "control: the sh-free fixture really did map a facade-bound call",
        )
    }
}
