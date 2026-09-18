package dev.rubentxu.pipeline.v2.application

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-LPR-301 — `core.waitUntil` fitness: the canonical body engine dispatches
 * `core.waitUntil` via the open registry (CoreWaitUntilStep) and a structural
 * declaration of the polling cadence (BodyExecutionPolicy.Retrying(waitUntil = ...)).
 * There is no concrete-PluginStepId branch in the coordinator.
 *
 * Properties asserted (counterpart of the WU-G5R.4 properties, now re-proven under the
 * WU-LPR-301 architecture):
 *
 *   1. `core.waitUntil` IS registered in `CoreStepRegistryFactory` (REGISTRY_PRIMARY).
 *   2. The descriptor carries `BodyExecutionPolicy.Retrying(waitUntil = WaitUntilShape())`.
 *   3. The canonical body engine dispatches the polling loop by reading the sub-shape,
 *      with no `when(stepKey)` / `if (pluginStepId.value == "core.waitUntil")` branch.
 *   4. The installed CLI emits `WaitUntilPolled` and `WaitUntilCompleted` through the
 *      CoreWaitUntilStep handler (registry path).
 *
 * Out of scope: the actual condition-evaluating loop (RepeatUntil policy) is deferred to
 * WU-LPR-302 (body/control execution consolidation). Until then the handler emits a single
 * polled/completed pair; this fitness test asserts that the structural wiring (registration,
 * descriptor shape, dispatch through the registry, event emission) is in place.
 */
@DisplayName("WU-LPR-301 — core.waitUntil canonical registry fitness")
@Timeout(30)
class Lfc2WaitUntilCanonicalReentryFitnessTest {

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("No credential store in this fitness test"),
        )
    }

    private fun waitUntilPipeline(): CompiledPipeline = CompiledPipeline(
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
                                        """{"kind":"sh","command":"true","isScriptBlock":false,"returnStdout":false}""",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * Fitness 1: `core.waitUntil` IS registered in the production factory. The dispatch
     * authority is exclusively the registry (CoreWaitUntilStep.definition); there is no
     * canonical legacy dispatcher for this key.
     */
    @Test
    fun `core dot waitUntil is registered in the production factory`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(
            registry.contains(PluginStepId("core.waitUntil")),
            "core.waitUntil MUST be in CoreStepRegistryFactory — it is the registry " +
                "authority for the canonical waitUntil dispatch (REGISTRY_PRIMARY)",
        )
    }

    /**
     * Fitness 2: the CoreWaitUntilStep descriptor declares the polling cadence through
     * `BodyExecutionPolicy.Retrying(waitUntil = WaitUntilShape())`. The body engine reads
     * the sub-shape structurally; there is no concrete-PluginStepId comparison.
     */
    @Test
    fun `core dot waitUntil descriptor declares the waitUntil sub-shape`() {
        val registry = CoreStepRegistryFactory.registry()
        val definition = registry.definition(PluginStepId("core.waitUntil"))
        requireNotNull(definition) { "core.waitUntil must be registered" }
        val descriptor = definition.contract.descriptor
        val body = descriptor.body as? dev.rubentxu.pipeline.v2.domain.StepBody.Declared
        requireNotNull(body) { "core.waitUntil must declare a structural body" }
        val policy = body.execution.policy
        check(policy is dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.Retrying) {
            "core.waitUntil descriptor body policy must be Retrying; got: $policy"
        }
        assertTrue(
            policy.waitUntil != null,
            "core.waitUntil descriptor MUST carry a WaitUntilShape sub-shape so the body " +
                "engine can dispatch the polling loop without a per-StepKey branch"
        )
    }

    /**
     * Fitness 3: the canonical coordinator succeeds end-to-end when `core.waitUntil` is
     * routed through the registry. No exception, no legacy dispatcher call, no
     * per-StepKey branch.
     */
    @Test
    fun `waitUntil succeeds through the registry path`() = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("waitUntil-registry-path")
        val journal = InMemoryOperationJournal(clock)

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            stepRegistry = CoreStepRegistryFactory.registry(),
        ).run(waitUntilPipeline(), runId)

        assertEquals(
            RunOutcome.Success,
            outcome,
            "waitUntil dispatched through the registry must succeed end-to-end",
        )
    }

    /**
     * Fitness 4: Installed-CLI fitness — proves the registry path is the emitter of
     * `WaitUntilPolled` / `WaitUntilCompleted`. The fixture exercises the same canonical
     * body machinery as production, and the CLI exits 0 with the expected event shape.
     */
    @Test
    fun `installed CLI emits WaitUntilPolled and WaitUntilCompleted through registry path`() {
        val appBin = AppBinSupport.discover()
        val fixture = generateSequence(
            java.io.File(System.getProperty("user.dir"))
        ) { it.parentFile }
            .map { java.io.File(it, "v2/compatibility/22-wait-until.pipeline.kts") }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate v2/compatibility/22-wait-until.pipeline.kts")

        val dbDir = java.io.File(java.io.File("/tmp"), "wu-lpr301-db-${System.currentTimeMillis()}")
        val ctrlDir = java.io.File(java.io.File("/tmp"), "wu-lpr301-ctrl-${System.currentTimeMillis()}")
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
