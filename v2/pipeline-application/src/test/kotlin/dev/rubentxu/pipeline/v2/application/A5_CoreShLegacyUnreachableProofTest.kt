package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.PreparedExecution
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.application.durable.buildDefaultExecutionBoundary
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * LB-02 / A5.4 + A5.5 — `core.sh` LEGACY_UNREACHABLE and recovery provenance proof.
 *
 * Mirrors the Echo `LegacyEchoUnreachableProofTest` structural proof, extended for Sh
 * with recovery:
 *
 *  1. `core.sh` is NOT in the closed legacy authority.
 *  2. with the production registry it classifies [StructuralStepFamily.Registry].
 *  3. the production registry contains `core.sh` as an open Step.
 *  4. the structural switch never classifies `core.sh` as `LegacyCore` → the legacy Sh
 *     decoder/dispatcher are unreachable on every registry-wired surface.
 *  5. recovery metadata provenance: `RegistryStepMetadataResolver` follows the registry
 *     `StepDescriptor.recoveryPolicy`, NOT `CanonicalCoreStepMetadata` (proven by
 *     divergence: a registered descriptor with `None` resolves to `None`, not the legacy
 *     row's `ExternalSubprocess`).
 *  6. running recovery on the registry family reconciles a RUNNING shell through the
 *     existing reconciler with ZERO fresh handler invocation and ZERO relaunch.
 */
@Timeout(60)
class A5_CoreShLegacyUnreachableProofTest {

    private fun productionRegistry() = CoreStepRegistryFactory.registry()

    // ---- A5.5.1 structural LEGACY_UNREACHABLE ---------------------------------

    @Test
    fun `core sh is NOT in the closed legacy authority LEGACY_PLUGIN_IDS`() {
        assertTrue(
            "core.sh" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.sh must remain outside LEGACY_PLUGIN_IDS; regression would resurrect legacy Sh decode",
        )
    }

    @Test
    fun `core sh classifies as Registry with the production registry`() {
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(CoreShellStep.KEY, productionRegistry()),
        )
    }

    @Test
    fun `production registry contains core sh as an open step`() {
        assertTrue(productionRegistry().contains(CoreShellStep.KEY))
        assertEquals("core.sh", CoreShellStep.KEY.value)
    }

    @Test
    fun `structural switch never classifies core sh as LegacyCore with the production registry`() {
        // Every remaining legacy id classifies LegacyCore; core.sh must not.
        for (legacyId in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS) {
            assertEquals(
                StructuralStepFamily.LegacyCore,
                StructuralFamilyResolver.classify(PluginStepId(legacyId), productionRegistry()),
                "legacy id '$legacyId' must classify LegacyCore (closed world)",
            )
        }
        assertNotEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CoreShellStep.KEY, productionRegistry()),
            "core.sh must never classify LegacyCore with a registry (legacy Sh path unreachable)",
        )
    }

    // ---- A5.4.1 metadata provenance (by divergence) --------------------------

    private fun customShDefinition(recovery: RecoveryPolicy): StepDefinition<String, String> {
        val stringCodec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }
        return object : StepDefinition<String, String> {
            override val contract: StepContract<String, String> = StepContract(
                key = CoreShellStep.KEY,
                descriptor = StepDescriptor(
                    stepId = "core.sh",
                    name = "sh",
                    configRef = "",
                    executionLocation = ExecutionLocation.AGENT,
                    effects = listOf(Effect.EXECUTES_SUBPROCESS),
                    replayPolicy = ReplayPolicy.RERUN,
                    recoveryPolicy = recovery,
                ),
                inputCodec = stringCodec,
                outputCodec = stringCodec,
            )
            override val handler: StepHandler<String, String> = StepHandler { input, _ -> input }
        }
    }

    @Test
    fun `metadata provenance follows registry descriptor not CanonicalCoreStepMetadata`() {
        // The legacy row for core.sh still says ExternalSubprocess. Register a core.sh whose
        // descriptor says None. If the composite resolver consulted the legacy row it would
        // return ExternalSubprocess; following the registry descriptor it returns None.
        assertEquals(
            RecoveryPolicy.ExternalSubprocess,
            CanonicalCoreStepMetadata.metadata("core.sh").recoveryPolicy,
            "precondition: legacy row still declares ExternalSubprocess",
        )
        val registry = InMemoryStepRegistry()
        registry.register(customShDefinition(RecoveryPolicy.None))
        val resolved = RegistryStepMetadataResolver.composite(registry).resolve(CoreShellStep.KEY)
        assertNotNull(resolved, "registry-resolved core.sh metadata must be present (never legacy fallback)")
        val metadata = resolved!!
        assertEquals(
            RecoveryPolicy.None,
            metadata.recoveryPolicy,
            "recovery policy during registry Sh resolution MUST come from the registered descriptor, not the legacy row",
        )
    }

    // ---- A5.4.3 + A5.4.4 running recovery on the registry family --------------

    private class NoRelaunchRecorder : CommonExecutionBoundary {
        var freshExecutions: Int = 0
            private set
        private val delegate = buildDefaultExecutionBoundary(
            dispatcher = CanonicalNodeDispatcher(),
            invocationExecutor = null,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        override suspend fun execute(
            prepared: PreparedExecution,
            context: dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext,
        ): CommonExecutionResult {
            if (prepared is PreparedRegistryExecution) freshExecutions++
            return delegate.execute(prepared, context)
        }
    }

    private fun singleSh(command: String): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("a5-recovery"),
        source = SourceDescriptor("A5Recovery.pipeline.kts", Digest("a5-recovery")),
        pluginLockDigest = Digest("a5-recovery-lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("build/sh"),
                            pluginStepId = CoreShellStep.KEY,
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"sh","command":"$command","isScriptBlock":false,"returnStdout":false}""",
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `running Sh recovery on registry reconciles RUNNING shell with zero fresh handler launch`(@TempDir tempDir: Path) = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val runId = RunId("a5-running-recovery")
        val operationId = "${runId.value}-s0-0"
        val relaunchMarker = tempDir.resolve("relaunched.txt")
        val command = "echo relaunched > '${relaunchMarker}'"
        val pipeline = singleSh(command)
        val payload = (pipeline.stages.single().body as StageBody.Steps).steps.single().payload.encoded
        val input = OperationInput(
            stepId = "core.sh",
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
            runId = runId.value,
            attempt = 1,
        )
        journal.append(
            RerunOperation(
                id = operationId,
                fingerprint = Fingerprint.compute(input, "core.sh", ReplayPolicy.RERUN, 1),
                input = input,
                output = null,
                status = OperationStatus.RUNNING,
                attempt = 1,
            ),
        )
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot.resolve(operationId))
        // Exit-0 result file: StepReconcilerL1 classifies the running external subprocess as Complete.
        Files.writeString(controlRoot.resolve(operationId).resolve("result.txt"), "0")

        val recorder = NoRelaunchRecorder()
        val coordinator = dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = controlRoot,
            commonExecutionBoundary = recorder,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

        val outcome = coordinator.run(pipeline, runId)

        assertEquals(RunOutcome.Success, outcome, "reconciled RUNNING shell must finish Success")
        assertEquals(
            0,
            recorder.freshExecutions,
            "recovery MUST NOT dispatch a fresh handler/process (decision passes through the reconciler)",
        )
        assertFalse(
            Files.exists(relaunchMarker),
            "A reconciled RUNNING shell MUST NOT be relaunched",
        )
        assertEquals(OperationStatus.SUCCEEDED, journal.get(operationId)?.status)
    }
}
