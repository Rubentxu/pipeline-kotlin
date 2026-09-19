package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * SH-VAR-SCOPE.S2 negative-fixture regression test.
 *
 * Locks the trap form `${'$'}VAR` into test coverage. The fixture
 * `v2/pipeline-application/src/test/resources/broken/99-trap-form-dollar-dollar-quote.pipeline.kts`
 * uses the trap form in `sh("echo trap=\${'$'}USER")`. The Kotlin compiler
 * accepts the source (it sees `${'$'}` as a string template evaluating to `$`
 * and `USER` as adjacent text), but bash receives `${'$'}USER` and rejects it
 * with `sustitución errónea`. We assert that:
 *   1. Compile succeeds (no Kotlin-side failure).
 *   2. Execution exits non-zero.
 *   3. stderr contains the bash `sustitución errónea` diagnostic.
 *
 * If this test ever flips to green (compile fail or bash success), the
 * trap form has either been silently fixed or re-introduced; either way
 * the doc comment on `v2/compatibility/14-credentials-bindings.pipeline.kts:7`
 * needs review.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TrapFormNegativeFixtureTest {

    private fun trapFixture(): java.nio.file.Path {
        val userDir = java.io.File(System.getProperty("user.dir"))
        val candidate = generateSequence(userDir) { it.parentFile }
            .map { java.io.File(it, "v2/pipeline-application/src/test/resources/broken") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate broken/ test resources via directory walk")
        val fixture = candidate.resolve("99-trap-form-dollar-dollar-quote.pipeline.kts")
        assertTrue(fixture.isFile) { "Trap fixture not found: $fixture" }
        return fixture.toPath()
    }

    @Test
    fun `run exits non-zero and bash rejects the trap form`() {
        val appBin = AppBinSupport.discover()
        val fixture = trapFixture()

        val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        // 1. Exit code must be non-zero (bash rejected the trap form).
        assertTrue(exitCode != 0,
            "trap form must cause non-zero exit; got $exitCode. stderr: $stderr")

        // 2. The bash bad-substitution diagnostic must appear in stdout events
        //    (specifically as EchoOutputCaptured.content). Spanish locale
        //    renders this as `sustitución errónea`; English as `bad substitution`.
        val trapText = stdout.contains("sustitución errónea") ||
            stdout.contains("bad substitution")
        assertTrue(trapText,
            "stdout events must contain 'sustitución errónea' or 'bad substitution' (bash rejecting the trap form), got: $stdout")

        // 3. Compile must have SUCCEEDED.  If the trap form ever becomes a
        //    Kotlin compile error, this assertion flips and we know the
        //    escaper or compile path changed.  Either way, the trap form is
        //    no longer "Kotlin accepts, bash rejects" — it is "Kotlin rejects",
        //    which is also fine but means the doc-comment fix is no longer
        //    load-bearing.
        val stdoutStartsWithArray = stdout.startsWith("[")
        assertTrue(stdoutStartsWithArray, "stdout must be a JSON array, got: ${stdout.take(100)}")
        // We expect at least one CompilationFinished in the events.  A
        // Kotlin compile error would emit CompilationFinished with
        // severity=ERROR diagnostics, but in this fixture there must be
        // none (the source compiles cleanly).  We assert by absence of
        // CompilationFinished with non-empty ERROR diagnostics — keep this
        // loose: any single ERROR diagnostic would flip the test.
        val events = dev.rubentxu.pipeline.v2.events.JsonEventLog.decode(stdout)
        val compileFinished = events.filterIsInstance<dev.rubentxu.pipeline.v2.events.CompilationFinished>().firstOrNull()
        assertNotNull(compileFinished, "must emit CompilationFinished event")
        val errors = compileFinished!!.diagnostics.filter {
            it.severity == dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity.ERROR
        }
        assertTrue(errors.isEmpty(),
            "trap form fixture must compile cleanly (Kotlin accepts it); ERROR diagnostics would mean the trap is now a Kotlin error. diagnostics=${compileFinished.diagnostics}")
    }
}
