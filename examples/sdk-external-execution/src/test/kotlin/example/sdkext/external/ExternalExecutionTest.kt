package example.sdkext.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ENTREGA B: the installed product runs an externally built plugin, observed from OUTSIDE it.
 *
 * ## Harness fidelity
 *
 * HF2 (forked real distribution). This class crosses the productive authority — the installed
 * `pipelinek` binary in its own process — and asserts on discrete observations only: an exit code,
 * and the presence of named event kinds in the run's own output. Nothing here reads a duration, a
 * size or an ordering.
 *
 * ## What it does NOT do
 *
 * It does not reimplement the engine, does not inject a coordinator, and does not compile the
 * plugin in-process. It builds nothing itself: [buildExternalPlugin] already produced the JAR from
 * the plugin's own Gradle build against the published SDK, and `:pipeline-application:installDist`
 * already produced the binary this build reads. The class orchestrates those two artifacts and
 * reads the result. A harness that re-ran either would be testing its own copy.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ExternalExecutionTest {

    private val binary: File = requiredPath("pipelinek.bin")
    private val pluginJar: File = requiredPath("example.plugin.jar")
    private val script: File = requiredPath("external.script")

    /**
     * One real run of the installed binary against the fixture, using ONLY the external plugin.
     *
     * `--plugin-jar` is the argument the CLI actually accepts (verified in `CliParser`: `"--plugin-jar"`),
     * not an assumed spelling. The fixture `import example.uppercase.uppercaseObserved` resolves only
     * if that JAR is on the script classpath, so a run that reaches a plugin event could not have
     * come from the bundled plugins.
     */
    @Test
    fun `the installed distribution executes the external plugin's contribution`(@TempDir dir: Path) {
        assertTrue(binary.isFile) { "installed binary not found at $binary; run :pipeline-application:installDist" }
        assertTrue(pluginJar.isFile) { "external plugin JAR not found at $pluginJar; run the plugin build first" }
        assertTrue(script.isFile) { "fixture script not found at $script" }

        val db = dir.resolve("db.sqlite")
        val result = runPipelinek(
            listOf(
                "run",
                "--db", db.toString(),
                "--plugin-jar", pluginJar.absolutePath,
                script.absolutePath,
            ),
        )

        // 1. The pipeline reached its expected outcome. stderr is where the CLI reports it.
        assertEquals(
            0,
            result.exitCode,
            "the run must exit 0; stdout tail=${result.stdout.takeLast(600)} " +
                "stderr tail=${result.stderr.takeLast(600)}",
        )
        assertTrue(
            result.stderr.contains(SUCCESS_LINE),
            "the product must report the expected outcome on stderr; stderr tail=${result.stderr.takeLast(600)}",
        )

        // 2. The plugin's contribution actually EXECUTED. `uppercaseObserved` emits the plugin's own
        //    event kind from inside its handler, through a capability the host must supply. The kind
        //    appearing in the run's own stream therefore witnesses both facts at once: the JAR was
        //    admitted and composed, and its handler ran with the capability it declared. A file that
        //    merely compiled would produce neither.
        assertTrue(
            result.stdout.contains(PLUGIN_EVENT_KIND),
            "the plugin's own event kind must appear in the run's output, which is emitted only by " +
                "its handler executing: $PLUGIN_EVENT_KIND. stdout tail=${result.stdout.takeLast(800)}",
        )
        assertTrue(
            result.stdout.contains(PLUGIN_EVENT_ENVELOPE),
            "the run must record a `$PLUGIN_EVENT_ENVELOPE` envelope for the plugin's contribution; " +
                "a plugin kind appearing without the envelope would mean the event was declared but " +
                "not stored. stdout tail=${result.stdout.takeLast(800)}",
        )

        // 3. The payload is the handler's OWN bytes, produced from the fixture's input. `"v1:5:5"` is
        //    the plugin's codec applied to `"hello" -> "HELLO"` (5 in, 5 out). Asserting the kind
        //    alone would be satisfied by an event recorded from anywhere; the payload can only exist
        //    if the handler ran with exactly this input, which is the difference between "the plugin
        //    was composed" and "the plugin executed".
        assertTrue(
            result.stdout.contains(PLUGIN_EVENT_PAYLOAD),
            "the plugin's event payload must be its own bytes for the fixture input: " +
                "expected $PLUGIN_EVENT_PAYLOAD in stdout. stdout tail=${result.stdout.takeLast(800)}",
        )
    }

    private fun requiredPath(property: String): File {
        val value = System.getProperty(property)
            ?: throw IllegalStateException(
                "system property '$property' is missing: this test would otherwise read a default " +
                    "path and could pass against a stale artifact. The build script must set it.",
            )
        return File(value)
    }

    /**
     * Runs the binary and ALWAYS reaps it.
     *
     * Both pipes are drained from the instant the child starts: `pipelinek` prints an entire event
     * log on one line, so a child can exceed the pipe buffer and block. Waiting before draining
     * would deadlock. The deadline belongs to the process, not to JUnit, so a hang fails here with
     * the child reaped rather than leaving a JVM behind for the next run to measure.
     */
    private fun runPipelinek(args: List<String>): Result {
        val process = ProcessBuilder(listOf(binary.absolutePath) + args).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outDrainer = drain(process.inputStream, stdout)
        val errDrainer = drain(process.errorStream, stderr)
        try {
            val finished = process.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                return Result(-1, stdout.toString(), stderr.toString() + "\n<TIMED OUT after ${DEADLINE_SECONDS}s>")
            }
            return Result(process.exitValue(), stdout.toString(), stderr.toString())
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            outDrainer.join(30_000)
            errDrainer.join(30_000)
        }
    }

    private fun drain(stream: java.io.InputStream, sink: StringBuilder) = thread(isDaemon = true) {
        stream.bufferedReader(StandardCharsets.UTF_8).use { sink.append(it.readText()) }
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        /** Verbatim product line printed by `Main.kt` on success. Copied, never invented. */
        const val SUCCESS_LINE = "Pipeline finished with SUCCESS"

        /** The event kind the plugin's own `EventDefinition` declares. */
        const val PLUGIN_EVENT_KIND = "example.uppercase.applied"

        /** The durable envelope the runtime records when the handler emits through the seam. */
        const val PLUGIN_EVENT_ENVELOPE = "PluginEventEmitted"

        /**
         * The plugin's own payload bytes for the fixture input `"hello"`.
         *
         * `UppercaseAppliedCodec` encodes `UppercaseApplied(inputLength=5, outputLength=5)` as
         * `"v1:5:5"`. It is quoted here as a raw JSON fragment so it must match the run's output
         * verbatim; a change to the fixture input or to the codec turns this RED on purpose.
         */
        const val PLUGIN_EVENT_PAYLOAD = "\"payload\":\"v1:5:5\""

        /** The subprocess's own contract; the class `@Timeout` is only the outer watchdog. */
        const val DEADLINE_SECONDS = 300L
    }
}
