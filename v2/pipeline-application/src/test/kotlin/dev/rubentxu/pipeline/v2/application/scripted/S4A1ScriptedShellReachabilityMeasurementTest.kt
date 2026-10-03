package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallKind
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * S4-A1 MEASUREMENT — can a scripted `sh(...)` run at all, and is the privileged
 * runtime reachable?
 *
 * ## Why this suite exists, and what it corrects
 *
 * `S4A1ScriptedShellPathPrivilegeCanaryTest` proves by reading that
 * `ScriptedFrontendRunner` builds `JournaledScriptedOperationRuntime` with a
 * lambda calling `ShExecution.invokeShell` directly, skipping the registry
 * preparation step where `core.sh`'s declared `SHELL_OPERATIONS_CAPABILITY` is
 * admitted. It was reported as a production-reachable capability bypass.
 *
 * Reading proves WIRING, not REACHABILITY. Measuring reachability changed the
 * severity, and the correction belongs on the face of the evidence rather than
 * quietly folded into a later commit. The measured chain is:
 *
 * ```
 * real user source:  sh(script = "printf hi")
 *   → KotlinScriptedSourceMapper   classifies it as ScriptedCallKind.Shell
 *   → ScriptedSourceLowering        returns `null` for Shell — NOT rewritten
 *   → generated Kotlin still contains a bare `sh(...)`
 *   → Kotlin host compile            ERROR: Unresolved reference 'sh'
 * ```
 *
 * The failure is at COMPILE TIME, before any execution. So:
 *
 * 1. A scripted pipeline containing `sh(...)` does not build. The most common
 *    step in the DSL is unusable in the scripted runtime.
 * 2. The privileged runtime is BUILT but UNREACHABLE. The capability bypass is
 *    LATENT, not live — a landmine, not an exploit.
 * 3. It is one small fix away from going live: give the compiled path a
 *    call-site provider and the raw `sh` binds to `ScriptedScope.sh`, which
 *    routes straight into the unadmitted privileged runtime.
 *
 * Point 3 is the argument for the fix being spine unification rather than a
 * call-site patch. Patching the call site without unifying arms the landmine.
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

    @Test
    fun `MEASURED - a scripted sh call is classified Shell, is never rewritten, and the generated source does not compile`() {
        val generated = lower("""sh(script = "printf hi")""")

        assertEquals(
            1,
            generated.mappedCalls.count { it.kind == ScriptedCallKind.Shell },
            "the mapper should classify the single unqualified sh(...) as ScriptedCallKind.Shell",
        )
        assertEquals(
            0,
            generated.mappedCalls.count { it.kind is ScriptedCallKind.ShellReturnStdout },
            "CHARACTERIZED: the runtime-returning arm ShellReturnStdout is unreachable, because the " +
                "eager Shell arm matches on the callee name alone and is tested first. " +
                "Fails when the mapper disambiguates by argument form (S4-A2).",
        )

        // The load-bearing step: the lowering declines to rewrite Shell at all.
        assertTrue(
            generated.source.contains("""sh(script = "printf hi")"""),
            "CHARACTERIZED: ScriptedCallKind.Shell yields `null` from the lowering and is therefore " +
                "NOT rewritten, so the bare sh(...) survives into the generated Kotlin. " +
                "GENERATED SOURCE WAS:\n${generated.source}",
        )
        assertTrue(
            !generated.source.contains("steps.sh"),
            "control: no façade call is generated for sh, so RuntimeScriptedStepFacade.sh cannot be " +
                "reached from a compiled entry point",
        )

        // ...and the bare call therefore has no receiver to bind to.
        val diagnostics = diagnosticsOf(compileOrDiagnostics(generated))
        assertTrue(
            diagnostics.contains("Unresolved reference 'sh'"),
            "MEASURED: the user's script does not build. Expected the Kotlin host to reject the bare " +
                "sh(...) left in the generated source, but got: $diagnostics\n\n" +
                "Fails when the Shell arm gains a façade rewrite (S4-A2).",
        )
    }

    @Test
    fun `CONTROL - a scripted source without sh compiles, so the rejection above is specific to sh and not a harness artifact`() {
        // If this control ever failed, the rejection in the test above would prove
        // nothing: the harness, not `sh`, would be at fault.
        val generated = lower("val unix = isUnix()")
        val result = compileOrDiagnostics(generated)

        assertTrue(
            result is ScriptCompilationResult.Success,
            "CONTROL: a sh-free scripted source must still compile through the same harness, otherwise " +
                "the compile rejection measured above is a harness artifact rather than a defect. " +
                "Got: ${diagnosticsOf(result)}",
        )
        assertTrue(
            generated.mappedCalls.count { it.kind == ScriptedCallKind.IsUnix } == 1,
            "control: the sh-free fixture really did map a facade-bound call, proving the mapper and " +
                "lowering pipeline is live for the kinds it does handle",
        )
    }
}
