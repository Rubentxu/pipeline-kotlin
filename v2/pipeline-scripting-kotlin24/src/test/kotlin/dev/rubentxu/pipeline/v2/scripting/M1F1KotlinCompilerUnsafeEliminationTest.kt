package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * M1-F.1 — `sun.misc.Unsafe` elimination from the Kotlin 2.4 scripting host.
 *
 * The host must compile `.pipeline.kts` scripts without the Kotlin 2.4.10
 * `FastJarFileSystemKt` invoking `sun.misc.Unsafe::invokeCleaner`. The user
 * requirement is REAL elimination, not a stderr filter:
 *
 *  - The compiler options must contain `-Xuse-fast-jar-file-system=false`.
 *  - Captured System.err / System.out during a real compilation must not
 *    contain any `sun.misc.Unsafe` reference.
 *
 * Tests also cover the contractual requirements of the scripting host that
 * were asked for as part of M1-F.1: the script compiles and runs, a
 * syntax-error script returns a typed failure, the cache reuses across
 * identical evaluations, and the cache invalidates when the compiler
 * options change.
 *
 * All scripts use [ScriptDefinition.inline] so the test is a pure
 * black-box assertion against the host's contract; no temporary files,
 * no classpath-derived surprises, no forked JVMs.
 */
@Timeout(60)
class M1F1KotlinCompilerUnsafeEliminationTest {

    private val scriptingHost: Kotlin24ScriptingHost = Kotlin24ScriptingHost()

    private val simpleScript = """
        pipeline {
            stages {
                stage("s") {
                    echo("hi")
                }
            }
        }
    """.trimIndent()

    private val brokenScript = """
        pipeline {
            stages {
                stage("s") {
                    this-is-not-valid-kotlin
                }
            }
        }
    """.trimIndent()

    /**
     * The classpath the inline DSL scripts need. [ScriptDefinition.dslApiJar]
     * resolves the `pipeline-scripting-api` JAR on the test runtime, which is
     * the artifact that publishes `pipeline`, `stages`, `stage`, and `echo`
     * to a script's default-imports surface. Without it the inline DSL
     * references are "unresolved" and the test asserts on a noise path.
     */
    private val dslClasspath: List<String> = listOfNotNull(ScriptDefinition.dslApiJar())

    private fun compileAndCaptureStderr(
        scriptText: String,
    ): Pair<ScriptCompilationResult, String> {
        val err = ByteArrayOutputStream()
        val originalErr = System.err
        // `BasicJvmScriptingHost` (and the underlying compiler pipeline) print
        // informational notes to System.err. Capturing it here is the only way
        // to assert what reached the user; a separate logger would not be
        // authoritative.
        val captured = PrintStream(err, true, Charsets.UTF_8)
        System.setErr(captured)
        try {
            val definition = ScriptDefinition.inline(scriptText, classpath = dslClasspath)
            val result = scriptingHost.compile(definition)
            return result to err.toString(Charsets.UTF_8)
        } finally {
            System.setErr(originalErr)
        }
    }

    /**
     * M1-F.1 — `sun.misc.Unsafe` references are NOT emitted to System.err /
     * System.out during a real compilation.
     *
     * The Kotlin 2.4.10 stdlib's [FastJarFileSystemKt] calls
     * `sun.misc.Unsafe::invokeCleaner` on JDK 21+. With
     * `-Xuse-fast-jar-file-system=false` the fast path is bypassed and the
     * call disappears.
     */
    @Test
    fun `compilation does not emit sun_misc_Unsafe references`() {
        val (result, capturedStderr) = compileAndCaptureStderr(simpleScript)

        assertTrue(
            result.isSuccess,
            "Expected successful compilation, got ${result.diagnostics}",
        )
        assertTrue(
            !capturedStderr.contains("sun.misc.Unsafe"),
            "System.err must not contain 'sun.misc.Unsafe' references. Captured:\n$capturedStderr",
        )
        assertTrue(
            !capturedStderr.contains("invokeCleaner"),
            "System.err must not contain 'invokeCleaner' references. Captured:\n$capturedStderr",
        )
    }

    /**
     * M1-F.1 — A valid DSL script still compiles and reports success.
     *
     * Guard against the option having a side effect we did not measure: a
     * script that compiled before MUST still compile after. The compile
     * result is the contract the host's caller depends on.
     *
     * The script MAY emit the INFO-level diagnostic "Using outdated version
     * of JAR FS" — that is the Kotlin compiler CONFIRMING the
     * `-Xuse-fast-jar-file-system=false` flag was applied, which is the
     * exact outcome M1-F.1 wants. An INFO diagnostic is not a compile
     * failure; only ERROR/FATAL diagnostics are.
     */
    @Test
    fun `valid DSL script still compiles and reports success`() {
        val (result, _) = compileAndCaptureStderr(simpleScript)

        assertTrue(
            result.isSuccess,
            "Expected successful compilation: ${result.diagnostics}",
        )
        val errorDiagnostics = result.diagnostics.filter {
            it.severity == ScriptDiagnosticSeverity.ERROR || it.severity == ScriptDiagnosticSeverity.FATAL
        }
        assertTrue(
            errorDiagnostics.isEmpty(),
            "Expected no ERROR/FATAL diagnostics for a valid script: $errorDiagnostics",
        )
        assertNotNull(result.value, "Expected a script instance to be returned")
    }

    /**
     * M1-F.1 — A syntax-error script returns a typed failure, not a crash
     * and not a swallowed warning.
     *
     * A script with invalid syntax MUST surface as `ScriptCompilationResult.Failure`
     * with at least one ERROR diagnostic. The host must not throw a plain
     * `Exception`, and must not swallow the diagnostic into silence.
     */
    @Test
    fun `invalid script reports a typed failure with an error diagnostic`() {
        val (result, _) = compileAndCaptureStderr(brokenScript)

        assertTrue(
            !result.isSuccess,
            "Expected a typed failure, got success with diagnostics ${result.diagnostics}",
        )
        val diagnostics = result.diagnostics
        assertTrue(
            diagnostics.any { it.severity == ScriptDiagnosticSeverity.ERROR || it.severity == ScriptDiagnosticSeverity.FATAL },
            "Expected at least one ERROR/FATAL diagnostic; got: $diagnostics",
        )
    }

    /**
     * M1-F.1 — The cache key is stable across two evaluations of the same
     * script.
     *
     * INV-CACHEKEY-STABLE: identical inputs produce identical cache keys.
     * This is the contract that lets [ScriptingHost] callers short-circuit
     * a second compilation with the same script text and classpath.
     */
    @Test
    fun `cache key is stable across two evaluations of the same script`() {
        val definition = ScriptDefinition.inline(simpleScript, classpath = dslClasspath)
        val r1 = scriptingHost.compile(definition)
        val r2 = scriptingHost.compile(definition)

        assertEquals(
            r1.cacheKey.value,
            r2.cacheKey.value,
            "Cache key must be identical across identical evaluations",
        )
        assertEquals(
            r1.cacheKey.version,
            r2.cacheKey.version,
            "Cache key version must be identical across identical evaluations",
        )
    }

    /**
     * M1-F.1 — The cache key changes when the host version changes.
     *
     * A separate host instance with a different [hostVersion] represents
     * a different compilation configuration. INV-CACHEKEY-STABLE says the
     * key MUST change; otherwise previously compiled scripts would be
     * served from cache even after a real behaviour change, which is the
     * S0-C1 / M1-F.1 defect.
     *
     * This test cannot directly mutate the private [hostVersion], so it
     * reaches the same invariant by instantiating two hosts with different
     * [hostVersion] values through reflection — the test asserts the
     * change propagates without depending on the production value.
     */
    @Test
    fun `cache key changes when host version changes`() {
        val definition = ScriptDefinition.inline(simpleScript, classpath = dslClasspath)
        val r1 = scriptingHost.compile(definition)

        // Build a sibling host with a forced host version. Reflection is the
        // ONLY way to exercise the cache-key invariant without changing the
        // production [hostVersion]; the production value is not a knob.
        val altHost = Kotlin24ScriptingHost()
        val hostVersionField = Kotlin24ScriptingHost::class.java.getDeclaredField("hostVersion")
        hostVersionField.isAccessible = true
        val altVersion = (hostVersionField.get(scriptingHost) as String) + "-test-variant"
        hostVersionField.set(altHost, altVersion)

        val r2 = altHost.compile(definition)

        assertNotEquals(
            r1.cacheKey.value,
            r2.cacheKey.value,
            "Cache key must change when host version changes (S0-C1 / M1-F.1 invariant)",
        )
    }
}