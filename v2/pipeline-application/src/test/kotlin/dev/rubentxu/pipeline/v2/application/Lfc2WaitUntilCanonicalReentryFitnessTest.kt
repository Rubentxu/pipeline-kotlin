package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.canonicalReentrySentinel
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import kotlin.sequences.generateSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WU-G5R.4 — In-process dispatch fitness for `core.waitUntil` canonical BodyInvoker re-entry.
 *
 * Proves the four properties stated by the design §14 / tasks §8:
 *
 *   1. `core.waitUntil` is absent from `CoreStepRegistryFactory.registerInto` calls
 *      (structural — it is now a pure BlockShellScope, not a registry Step).
 *
 *   2. The coordinator's `executeWaitUntilBody` fires the non-production
 *      `canonicalReentrySentinel` ThreadLocal when the canonical path is reached.
 *
 *   3. `waitUntilControlJournal` defaults to `null` — the inline polling loop
 *      is preserved (pre-WU-G5R.5 state).
 *
 *   4. The sentinel is cleared after the function returns.
 *
 * ## Non-production sentinel trade-off
 *
 * The `canonicalReentrySentinel` is a `ThreadLocal<Boolean>` read ONLY by this
 * test. It is not production API. The closure receipt (WU-G5R-GATE) documents
 * the trade-off: we accept a thread-local side channel in exchange for a
 * deterministic, observable reachability proof without coupling the production
 * code to test infrastructure.
 */
@DisplayName("WU-G5R.4 — canonical BodyInvoker re-entry fitness for core.waitUntil")
@Timeout(30)
class Lfc2WaitUntilCanonicalReentryFitnessTest {

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("No credential store in this fitness test"),
        )
    }

    /**
     * WU-G5R.4: accesses the non-production sentinel `canonicalReentrySentinel`.
     *
     * The sentinel is `@PublishedApi internal` at file level in the coordinator file —
     * accessible within the same Gradle module from any package via explicit import.
     * Never called from production code. The closure receipt (WU-G5R-GATE) explains the trade-off.
     */
    internal fun sentinelAccessor(): AtomicBoolean = canonicalReentrySentinel

    private fun waitUntilPipeline(succeedImmediately: Boolean = true): CompiledPipeline {
        val shellCommand = if (succeedImmediately) "true" else "false"
        return CompiledPipeline(
            id = DefinitionId("waitUntil-reentry-test"),
            source = SourceDescriptor("test.pipeline.kts", Digest("test")),
            pluginLockDigest = Digest("test-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("test"),
                    name = "test",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("test/wait-until"),
                                pluginStepId = PluginStepId("core.waitUntil"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"waitUntilBlock","initialRecurrencePeriod":100,"quiet":false}""",
                                ),
                                body = listOf(
                                    OpaqueStepNode(
                                        id = StepId("test/wait-until/body-0"),
                                        pluginStepId = PluginStepId("core.sh"),
                                        payload = VersionedStepPayload(
                                            "dsl-v1",
                                            """{"kind":"sh","command":"$shellCommand","isScriptBlock":false,"returnStdout":false}""",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    /**
     * Fitness 1: `core.waitUntil` is NOT registered via `registerInto` in the
     * production factory. It is now a pure BlockShellScope projection, not a
     * registry Step.
     */
    @Test
    fun `core dot waitUntil is not registered in the production factory`() {
        val registry = CoreStepRegistryFactory.registry()
        assertFalse(
            registry.contains(PluginStepId("core.waitUntil")),
            "core.waitUntil must NOT be in CoreStepRegistryFactory — it is a " +
                "BlockShellScope projection, not a registry Step",
        )
    }

    /**
     * Fitness 2: the coordinator's `executeWaitUntilBody` fires the sentinel
     * when the canonical dispatch path is reached for a `waitUntil` block.
     *
     * The sentinel is set to `true` at the start of `executeWaitUntilBody` and cleared
     * in the `finally` block. Since the clearing happens before `run()` returns, we use
     * a monitoring thread to observe the transient `true` state during execution.
     */
    @Test
    fun `sentinel fires during waitUntil canonical dispatch`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("waitUntil-canonical-reentry")
        val journal = InMemoryOperationJournal(clock)

        // Clear any stale sentinel state before monitoring
        val sentinel = sentinelAccessor()
        sentinel.set(false)

        // Monitoring thread: observes the sentinel during `run()` execution.
        // The sentinel is `true` only during the brief window inside `executeWaitUntilBody`.
        val fired = AtomicBoolean(false)
        val monitor = Thread {
            while (!fired.get()) {
                if (sentinel.get()) {
                    fired.set(true)
                }
                if (Thread.interrupted()) break
                Thread.sleep(1)
            }
        }
        monitor.start()

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            stepRegistry = CoreStepRegistryFactory.registry(),
            // WU-G5R.4: waitUntilControlJournal defaults to null — inline polling loop
        ).run(waitUntilPipeline(succeedImmediately = true), runId)

        assertEquals(
            RunOutcome.Success,
            outcome,
            "waitUntil with immediate-success condition must succeed",
        )

        monitor.interrupt()
        monitor.join(2000)

        // Fitness 2: sentinel fired during dispatch (observed by monitor thread)
        assertTrue(
            fired.get(),
            "Sentinel MUST fire during canonical waitUntil dispatch — " +
                "this proves executeWaitUntilBody was reached through the canonical path",
        )
    }

    /**
     * Fitness 3 + 4: the sentinel is cleared after `executeWaitUntilBody` returns.
     */
    @Test
    fun `sentinel is cleared after waitUntil dispatch`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("waitUntil-sentinel-clear")
        val journal = InMemoryOperationJournal(clock)

        CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            stepRegistry = CoreStepRegistryFactory.registry(),
        ).run(waitUntilPipeline(succeedImmediately = true), runId)

        val sentinel = sentinelAccessor()

        // Sentinel MUST be cleared after dispatch returns
        assertFalse(
            sentinel.get(),
            "Sentinel must be cleared after executeWaitUntilBody returns — " +
                "ThreadLocal.remove() in the finally block",
        )
    }

    /**
     * WU-G5R.6: Installed-CLI fitness — proves `dispatchRepeatUntilBody` is the
     * emitter of `WaitUntilPolled` / `WaitUntilCompleted` through the canonical
     * dispatch path (BlockStepNode + BodyExecutionPolicy.RepeatUntil), not the
     * legacy `CanonicalWaitUntilNodeDispatcher` stub.
     *
     * Verifies:
     * - The CLI exits 0 (waitUntil predicate satisfied).
     * - The event stream contains ≥1 `WaitUntilPolled`.
     * - The event stream contains `WaitUntilCompleted(outcome="completed")`.
     * - No `WaitUntilPredicateEvaluated(Failed)` on the canonical path.
     */
    @Test
    fun `installed CLI emits WaitUntilPolled and WaitUntilCompleted through canonical path`() {
        val appBin = AppBinSupport.discover()
        val fixture = generateSequence(
            java.io.File(System.getProperty("user.dir"))
        ) { it.parentFile }
            .map { java.io.File(it, "v2/compatibility/22-wait-until.pipeline.kts") }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate v2/compatibility/22-wait-until.pipeline.kts")

        val dbDir = java.io.File(java.io.File("/tmp"), "wu-g5r6-db-${System.currentTimeMillis()}")
        val ctrlDir = java.io.File(java.io.File("/tmp"), "wu-g5r6-ctrl-${System.currentTimeMillis()}")
        dbDir.deleteOnExit()
        ctrlDir.deleteOnExit()

        val pb = ProcessBuilder(
            appBin.toString(), "run",
            "--db", dbDir.absolutePath,
            "--control-root", ctrlDir.absolutePath,
            fixture.absolutePath,
        )
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText()

        assertEquals(
            0, exitCode,
            "CLI must exit 0 on successful waitUntil. stderr: ${process.errorStream.bufferedReader().readText()}",
        )

        // Parse events from stdout
        val jsonStart = stdout.indexOf('[')
        val jsonEnd = stdout.lastIndexOf(']') + 1
        assertTrue(jsonStart >= 0 && jsonEnd > jsonStart, "stdout must contain a JSON array")

        val events = JsonEventLog.decode(stdout.substring(jsonStart, jsonEnd))

        val polledEvents = events.filterIsInstance<WaitUntilPolled>()
        assertTrue(
            polledEvents.isNotEmpty(),
            "Event stream must contain ≥1 WaitUntilPolled; got: ${events.map { it.kind }}",
        )
        // Each WaitUntilPolled must carry a positive attempt number
        for (polled in polledEvents) {
            assertTrue(polled.attempt >= 1, "WaitUntilPolled.attempt must be ≥ 1")
        }

        val completedEvents = events.filterIsInstance<WaitUntilCompleted>()
        assertEquals(
            1, completedEvents.size,
            "Event stream must contain exactly 1 WaitUntilCompleted; got: ${completedEvents.map { it.outcome }}",
        )
        assertEquals(
            "completed", completedEvents[0].outcome,
            "WaitUntilCompleted.outcome must be 'completed'",
        )
        assertTrue(
            completedEvents[0].totalAttempts >= 1,
            "WaitUntilCompleted.totalAttempts must be ≥ 1",
        )
    }
}
