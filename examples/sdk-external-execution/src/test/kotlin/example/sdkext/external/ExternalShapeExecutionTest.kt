package example.sdkext.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * W6.2b: the two plugin shapes that had never been executed end-to-end.
 *
 * Until this class, the only external plugin exercised against the installed distribution was an
 * ATOMIC Step ([ExternalExecutionTest]). A body-bearing Step and a directive are the shapes where
 * "the JAR was admitted" and "the thing the plugin contributed actually ran" are genuinely
 * different claims, so asserting plugin presence proves nothing about them. The S6 audit already
 * recorded §4/§5 as verified "by absence of a forbidden pattern, not by execution".
 *
 * HF2 (forked real distribution). Both pipes are drained from the instant the child starts, for the
 * reason the Step test documents: `pipelinek` prints a whole event log on one line and would
 * otherwise fill the pipe buffer and block. Assertions are on DISCRETE observations only — an exit
 * code and classified event kinds — never on timing, ordering or output size.
 *
 * Every claim below is paired with the isolation negative. That pairing is the actual evidence: a
 * positive that cannot fail without the JAR proves only that the pipeline ran.
 */
class ExternalShapeExecutionTest {

    private val binary: File = requiredPath("pipelinek.bin")
    private val blockPluginJar: File = requiredPath("example.block.plugin.jar")
    private val blockScript: File = requiredPath("external.block.script")
    private val directivePluginJar: File = requiredPath("example.directive.plugin.jar")
    private val directiveScript: File = requiredPath("external.directive.script")

    /**
     * An external BODY-bearing Step must run its body, not merely be composed.
     *
     * The witness is the invocation COUNT of the body's own core step, read from the run's event
     * stream. `repeatBlock(3)` declares the body three times, so a run that reports three inner
     * `sh` steps cannot be produced by parsing alone: each count is written by the engine after the
     * body executed. Counting is preferred here over asserting on `sh` console text, because console
     * output is not part of the event contract and a stdout match would couple the test to
     * projection rather than to execution.
     *
     * The count is over `StepStarted` only. The first draft counted the bare `"/sh-0"` substring and
     * RED with `expected 3 but was 6`, which is the stream being honest: the step name appears once
     * in `StepStarted` and again in `StepFinished`, so three iterations are six occurrences. A
     * witness that counts both halves would have passed at six and kept passing if the engine ever
     * stopped reporting completions, which is exactly the semantic degradation being guarded.
     */
    @Test
    fun `an external block Step executes its body the declared number of times`(@TempDir dir: Path) {
        val result = runPipelinek(
            listOf(
                "run",
                "--db", dir.resolve("block.db").toString(),
                "--plugin-jar", blockPluginJar.absolutePath,
                blockScript.absolutePath,
            ),
        )

        assertEquals(
            0,
            result.exitCode,
            "the block fixture must succeed. stdout tail=${result.stdout.takeLast(600)} " +
                "stderr tail=${result.stderr.takeLast(600)}",
        )
        assertTrue(
            result.stdout.contains(STEP_STARTED),
            "the external block's own step must appear in the event stream: " +
                "stdout tail=${result.stdout.takeLast(800)}",
        )

        val innerBodySteps = countStepStarted(result.stdout, INNER_BODY_STEP)
        assertEquals(
            REPEAT_TIMES,
            innerBodySteps,
            "the block body must execute exactly $REPEAT_TIMES times. Each occurrence is a " +
                "StepStarted for the body's own core step, emitted only after the body ran. " +
                "stdout tail=${result.stdout.takeLast(800)}",
        )
    }

    /**
     * The isolation pair for the block Step: no JAR, no admission, no body.
     *
     * Without the plugin the fixture cannot resolve `example.block.repeatBlock` at all, so this
     * fails at COMPILATION rather than at admission. That is still fail-closed and still zero
     * effects, but it is a different mechanism than the directive's below, and asserting it
     * separately keeps the two from being confused for one another.
     */
    @Test
    fun `the block fixture is refused without the plugin and runs no body`(@TempDir dir: Path) {
        val result = runPipelinek(
            listOf(
                "run",
                "--db", dir.resolve("block-absent.db").toString(),
                blockScript.absolutePath,
            ),
        )

        assertEquals(
            1,
            result.exitCode,
            "without the JAR the fixture cannot compile and the run must fail. " +
                "stdout tail=${result.stdout.takeLast(600)} stderr tail=${result.stderr.takeLast(600)}",
        )
        assertFalse(
            result.stdout.contains(STEP_STARTED),
            "no step may start when the plugin is absent. stdout tail=${result.stdout.takeLast(800)}",
        )
    }

    /**
     * An external directive must be ADMITTED and its stage body must run.
     *
     * Two discrete observations, because they are separate claims. `DirectiveAdmitted` witnesses
     * that the plugin contributed the key and the composition accepted it. `EchoOutputCaptured`
     * witnesses that the stage body then executed. A directive that was admitted but whose body
     * never ran, or a body that ran under a directive that was skipped, both fail here.
     */
    @Test
    fun `an external directive is admitted and its stage body runs`(@TempDir dir: Path) {
        val result = runPipelinek(
            listOf(
                "run",
                "--db", dir.resolve("dir.db").toString(),
                "--plugin-jar", directivePluginJar.absolutePath,
                directiveScript.absolutePath,
            ),
        )

        assertEquals(
            0,
            result.exitCode,
            "the directive fixture must succeed. stdout tail=${result.stdout.takeLast(600)} " +
                "stderr tail=${result.stderr.takeLast(600)}",
        )
        assertTrue(
            result.stdout.contains(DIRECTIVE_ADMITTED),
            "the plugin's directive key must be admitted. stdout tail=${result.stdout.takeLast(800)}",
        )
        assertTrue(
            result.stdout.contains(ECHO_CAPTURED),
            "the stage body must execute under the admitted directive. " +
                "stdout tail=${result.stdout.takeLast(800)}",
        )
        assertFalse(
            result.stdout.contains(DIRECTIVE_DENIED),
            "an admitted run must not also report the directive as denied.",
        )
    }

    /**
     * The isolation pair for the directive, and the stronger half of its evidence.
     *
     * The script still COMPILES without the plugin — the directive key is just a string at
     * composition time — so this exercises admission rather than the compiler. The ordering is the
     * claim worth pinning: `DirectiveDenied` arrives with NO `StageStarted` and NO captured output,
     * which is the difference between "refused before effects" and "refused after doing the work".
     * A count of zero is asserted rather than a log line, per the W6.3 rule that negatives verify
     * real effects.
     */
    @Test
    fun `the directive is denied before any stage effect without the plugin`(@TempDir dir: Path) {
        val result = runPipelinek(
            listOf(
                "run",
                "--db", dir.resolve("dir-absent.db").toString(),
                directiveScript.absolutePath,
            ),
        )

        assertEquals(
            1,
            result.exitCode,
            "an unresolvable directive must fail the run. " +
                "stdout tail=${result.stdout.takeLast(600)} stderr tail=${result.stderr.takeLast(600)}",
        )
        assertTrue(
            result.stdout.contains(DIRECTIVE_DENIED),
            "the denial must be reported as a DirectiveDenied event naming the key. " +
                "stdout tail=${result.stdout.takeLast(800)}",
        )
        assertEquals(
            0,
            countOccurrences(result.stdout, ECHO_CAPTURED),
            "the stage body must not run at all when its directive is denied: a denial AFTER the " +
                "body would have produced captured output. stdout tail=${result.stdout.takeLast(800)}",
        )
        assertEquals(
            0,
            countOccurrences(result.stdout, "\"kind\":\"$STEP_STARTED\""),
            "no step may start under a denied directive. stdout tail=${result.stdout.takeLast(800)}",
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * Counts [stepName] occurrences that belong to a `StepStarted` event and to no other kind.
     *
     * The run emits one NDJSON array on stdout, so the event kind precedes the step name within a
     * record. Scanning record by record is what makes this robust: a substring count cannot tell an
     * event that STARTED a step from one that merely FINISHED it, and the two are different claims.
     */
    private fun countStepStarted(stdout: String, stepName: String): Int {
        val started = "\"kind\":\"$STEP_STARTED\""
        var count = 0
        for (record in stdout.split("},{")) {
            if (record.contains(started) && record.contains(stepName)) count++
        }
        return count
    }

    private fun requiredPath(property: String): File {
        val value = System.getProperty(property)
            ?: throw IllegalStateException(
                "system property '$property' is missing: this test would otherwise read a default " +
                    "path and could pass against a stale artifact. The build script must set it.",
            )
        return File(value)
    }

    /** Runs the binary and ALWAYS reaps it; see the reason in [ExternalExecutionTest]. */
    private fun runPipelinek(args: List<String>): Result {
        val process = ProcessBuilder(listOf(binary.absolutePath) + args).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outDrainer = drain(process.inputStream, stdout)
        val errDrainer = drain(process.errorStream, stderr)

        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError(
                "the product did not exit within ${PROCESS_TIMEOUT_SECONDS}s; it was killed so the " +
                    "next run does not inherit a live child. stdout tail=${stdout.takeLast(400)}",
            )
        }
        outDrainer.join(DRAIN_TIMEOUT_MILLIS)
        errDrainer.join(DRAIN_TIMEOUT_MILLIS)

        return Result(process.exitValue(), stdout.toString(), stderr.toString())
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { into.append(it).append('\n') }
            }
        }.apply { isDaemon = true; start() }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 120L
        const val DRAIN_TIMEOUT_MILLIS = 5_000L

        /** `repeatBlock(3)` in the fixture; the body's core step must appear exactly this often. */
        const val REPEAT_TIMES = 3
        const val INNER_BODY_STEP = "/sh-0"

        const val STEP_STARTED = "StepStarted"
        const val DIRECTIVE_ADMITTED = "DirectiveAdmitted"
        const val DIRECTIVE_DENIED = "DirectiveDenied"
        const val ECHO_CAPTURED = "EchoOutputCaptured"
    }
}
