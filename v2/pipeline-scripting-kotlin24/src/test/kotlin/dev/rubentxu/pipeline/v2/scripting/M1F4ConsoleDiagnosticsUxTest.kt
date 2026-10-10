package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Paths

/**
 * M1-F.4 — console diagnostics UX.
 *
 * A healthy `pipelinek run` must NOT surface internal implementation
 * noise on stderr:
 *  - "FastJarFileSystem" — the Kotlin compiler's INFO note about the
 *    fast-jar-file-system option M1-F.1 disabled. The user explicitly
 *    listed this as one of the substrings that must NOT appear.
 *  - "stream not opened" — the legacy "[ShExecution] could not seal"
 *    message M1-F.3 removed.
 *  - "using legacy compiler" — a Kotlin-internal note that surfaces
 *    when the K1 fallback path is taken.
 *
 * These are not failures and not warnings the user can act on; they
 * are diagnostic artifacts of the implementation. Real failures
 * (compilation errors, step failures, fatal errors) remain visible —
 * only the noise is removed.
 */
@Timeout(60)
class M1F4ConsoleDiagnosticsUxTest {

    private val scriptingHost: Kotlin24ScriptingHost = Kotlin24ScriptingHost()

    private val dslClasspath: List<String> = listOfNotNull(ScriptDefinition.dslApiJar())

    private val noiseSubstrings = listOf(
        "FastJarFileSystem",
        "stream not opened",
        "using legacy compiler",
    )

    private fun compileAndCaptureStderr(scriptText: String): Pair<ScriptCompilationResult, String> {
        val err = ByteArrayOutputStream()
        val originalErr = System.err
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
     * M1-F.4 — a healthy compile of `examples/03-shell.pipeline.kts`
     * produces stderr that contains NO internal-implementation noise.
     *
     * Real compilation failures (the script does not compile, a type
     * is unresolved, etc.) still surface as ERROR diagnostics — those
     * are user-facing and the assertion does not constrain them. The
     * test only checks the absence of the three substrings the user
     * listed as the noise they wanted gone.
     */
    @Test
    fun `healthy run produces no implementation-noise on stderr`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/hello.pipeline.kts")!!.toURI()
        )
        val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)

        val err = ByteArrayOutputStream()
        val originalErr = System.err
        val captured = PrintStream(err, true, Charsets.UTF_8)
        System.setErr(captured)
        try {
            val result = scriptingHost.compile(definition)
            assertTrue(
                result.isSuccess,
                "the bundled example must compile cleanly; got: ${result.diagnostics}",
            )
            assertNotNull(result.value, "a successful compile must return a script instance")
        } finally {
            System.setErr(originalErr)
        }

        val stderr = err.toString(Charsets.UTF_8)
        for (noise in noiseSubstrings) {
            assertFalse(
                stderr.contains(noise),
                "stderr must not contain '$noise'; captured stderr was:\n$stderr",
            )
        }
    }

    /**
     * M1-F.4 — a real compilation failure (syntax error) still
     * surfaces in the diagnostics AND the user can act on it.
     *
     * The assertion is on the diagnostics, not on stderr: stderr
     * discipline (compile diagnostics on stderr, never on stdout) is
     * the CLI's responsibility, not the scripting host's. What the
     * scripting host guarantees is that a compile failure returns a
     * typed [ScriptCompilationResult.Failure] with at least one
     * ERROR diagnostic carrying the user's message verbatim.
     */
    @Test
    fun `real compile failure remains visible as a typed diagnostic`() {
        val broken = """
            pipeline {
                stages {
                    stage("s") {
                        this-is-not-valid-kotlin
                    }
                }
            }
        """.trimIndent()

        val (result, _) = compileAndCaptureStderr(broken)

        assertFalse(result.isSuccess, "broken script must NOT report success")
        val errorDiagnostics = result.diagnostics.filter {
            it.severity == ScriptDiagnosticSeverity.ERROR ||
                it.severity == ScriptDiagnosticSeverity.FATAL
        }
        assertTrue(
            errorDiagnostics.isNotEmpty(),
            "a compile failure must carry at least one ERROR/FATAL diagnostic; got: ${result.diagnostics}",
        )
    }

    /**
     * M1-F.4 — a clean compile of an inline script also produces no
     * implementation-noise on stderr. This is the in-memory shape of
     * the previous test; running it for both shapes is what proves
     * the noise suppression is at the host level, not at the file
     * reader level.
     */
    @Test
    fun `inline-script compile also produces no implementation-noise on stderr`() {
        val inline = """
            pipeline {
                stages {
                    stage("hello") {
                        echo("hi")
                    }
                }
            }
        """.trimIndent()

        val (_, stderr) = compileAndCaptureStderr(inline)
        for (noise in noiseSubstrings) {
            assertFalse(
                stderr.contains(noise),
                "inline-script stderr must not contain '$noise'; captured:\n$stderr",
            )
        }
    }
}