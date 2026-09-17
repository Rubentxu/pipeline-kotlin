package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.application.EchoInput
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
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
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * LB-02 / G3-A3.4 + A3.5 characterization.
 *
 * Proves the durable projection law:
 *
 *   CommonExecutionBoundary.execute() returns CommonExecutionResult(outcome, encodedOutput?)
 *   The durable coordinator persists encodedOutput into RerunOperation.output
 *   IF AND ONLY IF it is non-null. Legacy paths produce encodedOutput = null and
 *   therefore no OperationOutput is written. Registry-routed Steps with a typed O
 *   produce an OperationOutput populated from the encoded value.
 *
 * Also proves:
 *   - The coordinator knows nothing about typed O (it never calls a codec).
 *   - Legacy fresh path is backwards-behaviour-preserving: encodedOutput == null
 *     and OperationOutput is not populated.
 *   - A3.6 atomicity: the boundary does not retain execution output across calls;
 *     re-invoking the same prepared execution reproduces the same carrier (no hidden
 *     state, no ThreadLocal, no per-key cache).
 */
class A3DurableProjectionCharacterizationTest {

    // CORE-LOAD-REJECTED (2026-09-17): The A3-1 test "legacy fresh produces encodedOutput
    // null and no OperationOutput is written" depended on CanonicalCoreStepCommand.Load as
    // a legacy command vehicle. With `Load` removed (REJECTED) and `LEGACY_PLUGIN_IDS =
    // emptySet()`, there is no legacy subtype to instantiate, so the durable projection
    // law for the legacy family can no longer be asserted by direct boundary probing.
    // The law is now structurally satisfied: `LegacyExecutionAdapter` routes ONLY
    // `PreparedLegacyExecution`; with no legacy command subtypes, the adapter's input
    // space is empty at the source level. The architectural assertion lives in
    // `Lfc2ZeroLegacyResidualFitnessTest` (legacy dispatcher family = 0 cases).

    @Test
    fun `A3-2 registry fresh with typed output produces OperationOutput populated from encoded value`() = runBlocking {
        val store = InMemoryEventStore()
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursor = InMemoryReplayCursorStore(clock)
        val registry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }
        val coordinator = CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            journal,
            cursor,
            clock,
            DefaultEffectReplayPolicy(),
            store,
            credentialScopePort = CredentialScopePort { _, _ ->
                CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("n/a"))
            },
            stepRegistry = registry,
        )
        val runId = RunId("a3-registry-fresh")
        val outcome = coordinator.run(echoPipeline("a3-registry"), runId)
        assertEquals(RunOutcome.Success, outcome)
        val ops = journal.listForRun(runId.value)
        assertEquals(1, ops.size)
        val op = ops.single()
        assertNotNull(op.output, "registry-routed Steps with typed output must populate the journal row")
        // The encoded String "a3-registry\n" is wrapped as JsonPrimitive (String) under OperationOutput.result.
        assertEquals("\"a3-registry\\n\"", op.output!!.result.toString())
    }

    @Test
    fun `A3-6 boundary does not retain execution output across calls`() = runBlocking {
        // Atomicity law: invoking the boundary twice with different inputs yields
        // independent carriers; re-invoking the first reproduces the original carrier.
        // No hidden state, no ThreadLocal, no per-key cache.
        val registry = InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }
        val ctx = CanonicalRuntimeContext(
            opId = OpId("a3-6", 0, 0),
            runId = "a3-6",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions.EMPTY,
            controlDirRoot = java.nio.file.Files.createTempDirectory("a3-6-"),
            eventSink = InMemoryEventStore(),
        )
        val boundary = RegistryExecutionBoundary.adapt()

        val firstPrepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("first")),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        ).let {
            require(it is ExecutionPreparation.Ready) { "first: $it" }
            it.prepared as PreparedRegistryExecution
        }
        val firstResult = boundary.execute(firstPrepared, ctx)
        assertEquals(EncodedStepValue("first\n"), firstResult.encodedOutput)

        val secondPrepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("second")),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        ).let {
            require(it is ExecutionPreparation.Ready) { "second: $it" }
            it.prepared as PreparedRegistryExecution
        }
        val secondResult = boundary.execute(secondPrepared, ctx)
        assertEquals(EncodedStepValue("second\n"), secondResult.encodedOutput)

        // Re-invoke the first: must reproduce the original carrier exactly.
        val firstAgain = boundary.execute(firstPrepared, ctx)
        assertEquals(firstResult.encodedOutput, firstAgain.encodedOutput, "re-running first must reproduce first; boundary has no state")
        assertEquals(StepOutcome.Success, firstAgain.outcome)
    }

    private fun echoPipeline(text: String): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("a3-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("build/echo"),
                            pluginStepId = PluginStepId("core.echo"),
                            payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"$text"}"""),
                        ),
                    ),
                ),
            ),
        ),
    )
}
