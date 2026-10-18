package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.support.CliContractTestKit
import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.FileBasedWaitUntilControlJournal
import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
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
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
@Tag("installed-contract")
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
     *
     * ## Why the control journal is bound, and why its row is asserted (measured 2026-10-18)
     *
     * `BodyExecutionEngine` selects between two waitUntil branches: `WaitUntilEngine.execute`
     * when a journal is bound, and `executeWaitUntilInline` when it is null. Production binds
     * it in `CompositionRoot`, so the inline branch is unreachable from `src/main`. Left
     * unbound, this fitness certified that branch — and a fitness has more authority than an
     * ordinary test, because a failure here is read as a structural violation of the open
     * registry seam.
     *
     * Binding alone would be an unverified claim, so the row also asserts the artefact only
     * the production engine leaves behind: a `.attempts.json` control row. Both branches run
     * the body and both report success, so `RunOutcome.Success` cannot tell them apart.
     */
    @Test
    fun `waitUntil succeeds through the registry path`(@TempDir tmp: Path) = runBlocking {
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val runId = RunId("waitUntil-registry-path")
        val journal = InMemoryOperationJournal(clock)
        val controlRoot = tmp.resolve("control")

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            waitUntilControlJournal = FileBasedWaitUntilControlJournal(controlRoot),
            stepRegistry = CoreStepRegistryFactory.registry(),
        ).run(waitUntilPipeline(), runId)

        assertEquals(
            RunOutcome.Success,
            outcome,
            "waitUntil dispatched through the registry must succeed end-to-end",
        )

        // WHICH engine ran is part of the claim, and RunOutcome.Success above cannot tell
        // the two apart: both run the body and both succeed. Only the engine that drives a
        // durable control row leaves one behind. Without this assertion, dropping the journal
        // above would return this fitness to certifying the inline path with the row still
        // green — a fitness that survives an untested configuration change.
        val controlDir = controlRoot.resolve("wait-until-control")
        val controlRows: List<Path> =
            if (!Files.isDirectory(controlDir)) {
                // An absent control directory is the observable of the wrong branch, not an
                // exception: `Files.walk` throws `NoSuchFileException` on a missing root,
                // which would replace this row's diagnosis with a stack trace.
                emptyList()
            } else {
                Files.walk(controlDir).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .filter { it.fileName.toString().endsWith(".attempts.json") }
                        .sorted()
                        .toList()
                }
            }
        assertEquals(
            1,
            controlRows.size,
            "the production waitUntil engine drives exactly one durable control row; found " +
                "${controlRows.map { it.fileName }} under $controlDir. An empty set means the " +
                "run crossed `executeWaitUntilInline` (journal null), not `WaitUntilEngine`, " +
                "so this fitness would certify a path `src/main` cannot reach.",
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

        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json",
            "--db", dbDir.absolutePath,
            "--control-root", ctrlDir.absolutePath,
            fixture.absolutePath,),
            // HARNESS FIDELITY 4 (hermetic). Measured 2026-10-18: without this the
            // installed CLI exits 3 before the pipeline starts --
            //   "Passphrase required: set PIPELINE_STORE_PASSPHRASE or run
            //    interactively in a TTY"
            // -- so this row's verdict depended on the OPERATOR's ambient environment
            // (an exported passphrase, or a TTY), not on the code under test. On a
            // machine with neither it failed on a real binary that emits the events
            // correctly; with the variable set the same binary exits 0 and emits
            // 2x WaitUntilPolled + 1x WaitUntilCompleted.
            //
            // The claim under test is "the installed CLI emits the waitUntil events
            // through the registry path", which has nothing to do with the credential
            // store. The passphrase is incidental start-up infrastructure, so it is
            // supplied explicitly rather than inherited: this makes the row hermetic in
            // BOTH directions, because an operator who happens to have one exported no
            // longer changes the verdict either.
            //
            // This is a test literal, not a credential. `Subprocess.run` merges into the
            // inherited environment (`putAll`), so PATH and the rest survive.
            environment = mapOf("PIPELINE_STORE_PASSPHRASE" to "lfc2-waituntil-fitness-not-a-secret"),
        ).requireExited()
        val exitCode = cliRun.exitCode
        val stdout = cliRun.stdout

        assertEquals(
            0, exitCode,
            "CLI must exit 0 on successful waitUntil. stderr: ${cliRun.stderr}",
        )

        // PK-NEXT R0.3: contract is decoded via the testkit. Line 1
        // is the LaunchReceipt, line 2 is the JSON event document. The
        // "find '['" / "find ']'" substring approach is wrong against
        // the new contract — the testkit decodes both channels
        // explicitly and is the single source of truth.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = cliRun.stdout,
            stderr = cliRun.stderr,
            format = ObservationFormat.JSON,
        )
        val events = contract.eventDocument.events

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
