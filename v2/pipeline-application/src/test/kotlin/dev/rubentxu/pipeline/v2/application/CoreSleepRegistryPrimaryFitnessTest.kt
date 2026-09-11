package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Permanent G4 assertion for the transitional REGISTRY_PRIMARY state. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreSleepRegistryPrimaryFitnessTest {
    private val key = PluginStepId("core.sleep")

    @Test fun `registry is primary and legacy counters retain only the nine residual keys`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(key))
        assertFalse("core.sleep" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertEquals(StructuralStepFamily.Registry, StructuralFamilyResolver.classify(key, registry))
        // S2-A3/G4: core.file.writeFile flipped to registry; 10 -> 9 residual legacy keys.
        // S2-A4/G4: core.emit.event flipped to registry; 9 -> 8 residual legacy keys.
        assertEquals(setOf(
            "core.milestone", "core.deleteDir", "core.cleanWs",
            "core.load", "core.pwd", "core.isUnix", "core.waitUntil", "core.archiveArtifacts",
        ), CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
    }

    @Disabled("Historical G4 snapshot: G5 removes the core.sleep legacy metadata row, converging to 10/10/10.")
    @Test fun `G4 transitional snapshot retained eleven legacy metadata rows`() {
        assertEquals(11, CanonicalCoreStepMetadata.pluginIds.size)
    }

    @Test fun `production registry contains exactly the registered core steps (echo sh error sleep writeFile emitEvent + isUnix S2-A5 G1 candidate)`() {
        assertEquals(
            setOf("core.echo", "core.sh", "core.error", "core.sleep", "core.file.writeFile", "core.emit.event", "core.isUnix"),
            CoreStepRegistryFactory.registry().keys().map { it.value }.toSet(),
        )
    }

    @Test fun `registry descriptor is now effective metadata authority`() {
        val descriptor = CoreSleepStep.definition.contract.descriptor
        assertEquals(listOf(Effect.READ_ONLY), descriptor.effects)
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
        assertEquals(RecoveryPolicy.None, descriptor.recoveryPolicy)
        assertEquals(RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry()).resolve(key),
            StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED, RecoveryPolicy.None))
    }

    @Test fun `registry seam executes zero successfully`() = runBlocking {
        val result = RegistryExecutionBoundary.coexecute(prepare(CoreSleepInput(0)), testContext("zero"))
        assertEquals(StepOutcome.Success, result.outcome)
    }

    @Test fun `registry seam preserves parent cancellation`() = runBlocking {
        val job = launch {
            RegistryExecutionBoundary.coexecute(prepare(CoreSleepInput(Long.MAX_VALUE)), testContext("cancel"))
        }
        delay(30); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    private fun prepare(input: CoreSleepInput): PreparedRegistryExecution {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = key,
            encodedInput = CoreSleepStep.definition.contract.inputCodec.encode(input),
            availableCapabilities = emptySet(),
        )
        val ready = preparation as ExecutionPreparation.Ready
        return ready.prepared as PreparedRegistryExecution
    }

    private fun testContext(label: String) = dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext(
        dev.rubentxu.pipeline.v2.application.durable.OpId("g4-$label", 0, 0), "g4-$label", "g4", 0, 0,
        dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions.EMPTY,
        java.nio.file.Files.createTempDirectory("g4-$label"), dev.rubentxu.pipeline.v2.events.InMemoryEventStore())
}
