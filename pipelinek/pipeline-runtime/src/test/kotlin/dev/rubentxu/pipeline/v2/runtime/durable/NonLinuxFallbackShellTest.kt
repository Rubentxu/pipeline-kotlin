package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.IdGenerator
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShellCapturedStdout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What the fallback route publishes, on the platform the fallback route is for.
 *
 * **The guarantee.** A shell step that runs on the generic process substrate publishes the SAME
 * three facts the durable substrate publishes: it runs in the workspace the caller declared, the
 * value it publishes is the stdout ALONE with the historical normalisation, and the observable
 * transcript carries the stderr without carrying the value. A value that cannot be published —
 * because the channel could not be read, or because it is over the limit — is a REFUSAL, never
 * an empty value.
 *
 * **The observable capacity it serves.** The same step means the same thing on every supported
 * host. A step that reads a file it wrote, a step whose value is a program's output and not the
 * program's output plus its diagnostics, and a step whose value is too big to publish are all
 * facts a consumer can rely on without knowing which platform ran it.
 *
 * **Why this instrument and not another.** The fallback was `private` and reached only when the
 * durable substrate refused the host, so on a Linux host NOTHING measured it: its defects were
 * invisible on the platform where most of the work happens. The route is now reachable from this
 * module's own lane, so the cheapest instrument that can fail — one call to the route — is
 * available on every host. No macOS runner and no end-to-end scenario are needed: the substrate
 * under test is the generic process runtime, which runs anywhere.
 *
 * **What is faked, and what is not.** The child is a REAL `bash` and the script is REAL. The
 * clock and the id generator are fixed because neither is what these rows are about. Nothing
 * about the workspace, the channels or the limit is simulated.
 */
class NonLinuxFallbackShellTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the child starts in the effective workspace`() {
        val workspace = Files.createDirectories(temp.resolve("workspace"))

        val run = fallback(script = "pwd", workspace = workspace)

        val value = assertInstanceOf(
            ShellInvocationResult.Stdout::class.java,
            run.result,
            "the step did not publish a value",
        ).value
        // The path is compared TRIMMED because the claim of this row is the working directory,
        // not the bytes: the normalisation of the value is the claim of another row.
        assertEquals(
            workspace.toRealPath().toString(),
            value.trim(),
            "the child did not start in the effective workspace",
        )
    }

    @Test
    fun `the captured value is the stdout alone, with the historical normalisation`() {
        val run = fallback(
            script = "printf 'the-value\\n'; printf 'the-diagnostic\\n' >&2",
            workspace = workspace(),
        )

        val value = assertInstanceOf(
            ShellInvocationResult.Stdout::class.java,
            run.result,
            "the step did not publish a value",
        ).value
        assertEquals(
            "the-value",
            value,
            "the captured value is not the stdout alone with the historical normalisation",
        )
    }

    @Test
    fun `the observable transcript carries the stderr and not the value`() {
        val run = fallback(
            script = "printf 'the-value\\n'; printf 'the-diagnostic\\n' >&2",
            workspace = workspace(),
        )

        val content = run.transcripts.single().content
        assertTrue(
            content.contains("the-diagnostic"),
            "the observable transcript lost the stderr",
        )
        assertFalse(
            content.contains("the-value"),
            "the observable transcript carries the value, which is the typed value's own copy",
        )
    }

    @Test
    fun `a value over the limit is refused instead of published`() {
        val run = fallback(
            script = "printf 'x%.0s' {1..200}",
            workspace = workspace(),
            limitBytes = LIMIT,
        )

        val failed = assertInstanceOf(
            ShellInvocationResult.Failed::class.java,
            run.result,
            "a value over the limit was published instead of refused",
        )
        val durable = requireNotNull(failed.durableFailure) {
            "the refusal carries no durable record, so the reason is lost"
        }
        assertFalse(durable.retryable, "the refusal of an over-limit value is retryable")
        val cause = requireNotNull(durable.details["cause"]) {
            "the refusal names no cause, so the limit it applied is unknown"
        }
        assertTrue(cause.contains("limit"), "the refusal does not name the limit it applied: $cause")
        assertTrue(cause.contains("200"), "the refusal does not name the size it refused: $cause")
    }

    @Test
    fun `the transcript does not carry the secret the registry knows`() {
        val secret = "s3cr3t-value"
        val registry = SecretPatternRegistry().apply {
            addSecret(SecretHandle.plain(secret))
        }

        val run = fallback(
            script = "printf 'before %s after\n' '$secret' >&2",
            workspace = workspace(),
            registry = registry,
        )

        val content = run.transcripts.single().content
        assertTrue(content.contains("before"), "the observable transcript lost its text")
        assertFalse(content.contains(secret), "the observable transcript carries the secret")
    }

    @Test
    fun `a program that printed nothing publishes an empty value`() {
        val run = fallback(script = "true", workspace = workspace())

        val value = assertInstanceOf(
            ShellInvocationResult.Stdout::class.java,
            run.result,
            "the step did not publish a value",
        ).value
        // The absence and the emptiness are two facts at the CHANNEL: nobody wrote it, and the
        // program printed nothing. The consumer cannot tell them apart — the published projection
        // turns both into an empty value — so this row certifies the half that IS distinguishable:
        // the step published a VALUE, not a failure. A step that refused an empty value would
        // answer "the program printed nothing" with "the value could not be read", and those are
        // not the same claim.
        assertEquals("", value, "the value of a program that printed nothing is not empty")
    }

    @Test
    fun `a non-zero exit is a failure that carries the code`() {
        val run = fallback(script = "exit 3", workspace = workspace())

        val failed = assertInstanceOf(
            ShellInvocationResult.Failed::class.java,
            run.result,
            "a script that failed was published as a success",
        )
        assertEquals(3, failed.exitCode, "the failure does not carry the code the script exited with")
    }

    /**
     * The limit the rows use. It is small on purpose: the refusal is what is under test, and a
     * row that wrote the product's real limit's worth of bytes would cost more than it proves.
     */
    private fun workspace(): Path = Files.createDirectories(temp.resolve("workspace"))

    private fun fallback(
        script: String,
        workspace: Path?,
        limitBytes: Long = ShellCapturedStdout.MAX_CAPTURED_STDOUT_BYTES,
        registry: SecretPatternRegistry? = null,
    ): FallbackRun = runBlocking {
        val sink = RecordingSink()
        val result = ShExecution.executeNonLinuxInvocation(
            command = ShellCommand(script = script, returnMode = ShellReturnMode.STDOUT),
            env = emptyMap(),
            eventSink = sink,
            stepIndex = 0,
            runId = RUN_ID,
            opId = OP_ID,
            controlDirRoot = temp,
            clock = FixedClock,
            idGenerator = IdGenerator { "event-1" },
            timeoutMs = null,
            workspaceRoot = workspace,
            limitBytes = limitBytes,
            secretPatternRegistry = registry,
        )
        FallbackRun(result = result, transcripts = sink.events.filterIsInstance<EchoOutputCaptured>())
    }

    /** What one fallback execution published. */
    private data class FallbackRun(
        val result: ShellInvocationResult,
        val transcripts: List<EchoOutputCaptured>,
    )

    /** The sink that records what is published. In memory: the claim is about what is published. */
    private class RecordingSink : EventSink {
        val events: MutableList<DomainEvent> = mutableListOf()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val OP_ID = "op-1"
        const val LIMIT = 64L

        val FixedClock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-11-06T00:00:00Z")
        }
    }
}
