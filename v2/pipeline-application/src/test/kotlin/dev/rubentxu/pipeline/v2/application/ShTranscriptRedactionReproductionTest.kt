package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.ShExecution
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * R4 / WU-RC2 — the fourth defect, isolated: **a real `sh` transcript is lost whenever a
 * [SecretPatternRegistry] is in the composition.**
 *
 * ## Why this class exists and why it is `@Disabled`
 *
 * `ConsoleReadServiceTest` is GREEN and drives a real `sh` process through the real
 * `ShExecution.invokeShell`, then reads the transcript back out of the Output Plane. That
 * looks like proof that the Output Plane is populated by a real run. It is not: that test
 * composes `ShExecution` **without** a `secretPatternRegistry`.
 *
 * The CLI does compose one. With a registry present, `ShExecution` builds a
 * `transcriptRedactor`, `DurableShellExecutor` therefore reports `pumpedThroughJvm = true`
 * even with no channel sinks, and a `StreamingRedactor` sits between the child's pipe and the
 * sink. That is the only material difference between the green test and the broken product —
 * and it is the difference between "the transcript lands" and "the transcript is never written".
 *
 * ## The measured symptom in the product (not in this test)
 *
 * ```text
 * $ time pipelinek run --db /tmp/j.db --control-root /tmp/c --isolated one-sh-step.pipeline.kts
 * real 0m15.9s      # against a 0.21s `pipelinek version` baseline
 * user 0m25.1s      # the CPU burn is the tell: something is spinning, not waiting
 *
 * $ find /tmp/c/output-plane -maxdepth 2
 * /tmp/c/output-plane/streams      # created, EMPTY; no `frames` directory at all
 * ```
 *
 * So: the run takes ~16x the JVM baseline, burns 25s of CPU, and produces no frames. The
 * ~10s of that which is wall-clock is `DurableShellExecutor.executeCore`'s
 * `transcriptPumps.forEach { it.join(10_000) }` timing out on a pump that never reaches EOF.
 *
 * ## What is `@Disabled` and why
 *
 * The RED row is `REDACTOR-2`. It is a characterised defect, not a passing test, and this
 * repository does not merge a red gate. It is `@Disabled` with the reason inline and a link to
 * the receipt, per the migration rule in `AGENTS.md` ("borrar una UAT antes de la migracion
 * es prohibido: debe quedar deshabilitada con enlace"). The GREEN row is `REDACTOR-1`: it is
 * the control that proves the discriminator is the registry and not something else, and it
 * stays enabled forever as the regression anchor for the fix.
 *
 * When the defect is closed, `REDACTOR-2` is deleted and its assertion moves into
 * `ConsoleReadServiceTest` as an ordinary row — that transition is the certification, and it
 * MUST be explicit rather than a silent rewrite of what is expected.
 *
 * Receipt: `docs/v2/07-uat/R4_WU_RC2_FRAME_INTEGRITY_RECEIPT.md` § "The fourth defect".
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
@DisplayName("R4 / WU-RC2 — real sh transcript vs secret-redaction composition (fourth defect)")
class ShTranscriptRedactionReproductionTest {

    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun resetProvider() {
        OutputPlaneProvider.forgetAll()
    }

    /**
     * Drive a real `sh` through the real [ShExecution.invokeShell] with the given
     * [registry] (`null` = the composition `ConsoleReadServiceTest` uses), and return the
     * transcript the Output Plane ended up holding.
     */
    private fun runRealShAndReadBack(registry: SecretPatternRegistry?): String {
        val controlDirRoot = Files.createDirectories(root.resolve("control-${registry == null}"))
        val workspaceRoot = Files.createDirectories(root.resolve("ws-${registry == null}"))
        val runId = "run-redaction-${registry == null}"

        runBlocking {
            ShExecution.invokeShell(
                command = ShellCommand(script = "printf 'transcript-line\\n'", returnMode = ShellReturnMode.NONE),
                opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0),
                runId = runId,
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions(
                    workspaceRoot = workspaceRoot,
                    captureStdout = false,
                    timeoutMs = 60_000,
                    env = emptyMap(),
                    sandbox = SandboxConfig.NONE,
                ),
                controlDirRoot = controlDirRoot,
                eventSink = InMemoryEventStore(),
                secretPatternRegistry = registry,
            )
        }

        return MainConsoleCli.readWholeStream(
            controlDirRoot,
            runId,
            OpId(runId = runId, stageIndex = 0, stepIndex = 0).format(),
        ).toString(Charsets.UTF_8)
    }

    /**
     * GREEN, forever — the control row.
     *
     * Proves the harness itself is sound: a real `sh`, through the real engine, lands in the
     * Output Plane and reads back. Without this row, `REDACTOR-2` would be ambiguous between
     * "the redactor broke it" and "this harness cannot observe the transcript at all".
     */
    @Test
    fun `REDACTOR-1 a real sh transcript lands when no registry is composed (control row)`() {
        val transcript = runRealShAndReadBack(registry = null)

        assertEquals(
            "transcript-line\n", transcript,
            "the control row must observe the real transcript; if it does not, this harness " +
                "cannot observe the Output Plane at all and REDACTOR-2 proves nothing.",
        )
    }

    /**
     * RED — the characterised defect.
     *
     * Identical to `REDACTOR-1` except that a [SecretPatternRegistry] is composed, which is
     * what the CLI does on every run. With a registry present the transcript is lost.
     */
    @Test
    @org.junit.jupiter.api.Disabled(
        "RED — characterised defect. A real sh transcript is never persisted when a " +
            "SecretPatternRegistry is composed, which the CLI does on EVERY run (Main.kt wires " +
            "the registry object unconditionally; only the canary secrets are conditional). " +
            "Measured with this harness: with an EMPTY registry the stream is never even " +
            "declared (`console-refused: unknown-stream ...`); with a registered secret exactly " +
            "ONE byte of a 16-byte transcript lands. Product symptom: ~16s wall against a 0.21s " +
            "JVM baseline, no output-plane/frames directory, so console --frames and " +
            "console --follow have nothing to read. " +
            "See docs/v2/07-uat/R4_WU_RC2_FRAME_INTEGRITY_RECEIPT.md § 'The fourth defect'. " +
            "Delete this @Disabled and promote its assertion once the redactor composition is fixed.",
    )
    fun `REDACTOR-2 a real sh transcript is lost when a registry is composed`() {
        // An EMPTY registry, on purpose. `Main.kt` wires the registry object into every run
        // regardless of whether any secret was registered (canaries are added conditionally at
        // Main.kt:253, but the object is always passed), so the empty registry IS the plain
        // `pipelinek run` composition. If the defect reproduces here, it is the redactor's
        // PRESENCE that loses the transcript, not the presence of secrets — which is a
        // different and worse finding than "redaction is broken for secret-bearing runs".
        val registry = SecretPatternRegistry()

        val transcript = runRealShAndReadBack(registry = registry)

        assertTrue(
            transcript.contains("transcript-line"),
            "a real sh transcript must survive redaction composition; got '$transcript'. " +
                "The product symptom is the same: ~16s wall, no output-plane/frames directory.",
        )
    }

    /**
     * RED — the same defect with a secret actually registered.
     *
     * Kept as a separate row because "empty registry loses it" and "populated registry loses
     * it" are different claims, and only the first is what a credential-free `pipelinek run`
     * experiences. If the fix lands for the empty case and this one still fails, the fix was
     * for the plumbing and not for the redaction.
     */
    @Test
    @org.junit.jupiter.api.Disabled(
        "RED — same defect as REDACTOR-2 with a secret actually registered; one byte of a " +
            "16-byte transcript lands. See " +
            "docs/v2/07-uat/R4_WU_RC2_FRAME_INTEGRITY_RECEIPT.md § 'The fourth defect'.",
    )
    fun `REDACTOR-3 a real sh transcript is lost when a registry with a secret is composed`() {
        val registry = SecretPatternRegistry()
        registry.addSecret(SecretHandle.plain("a-sufficiently-long-registered-secret"))

        val transcript = runRealShAndReadBack(registry = registry)

        assertTrue(
            transcript.contains("transcript-line"),
            "a real sh transcript must survive redaction composition; got '$transcript'.",
        )
    }
}
