package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Permanent G4 assertion for the transitional REGISTRY_PRIMARY state. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreSleepRegistryPrimaryFitnessTest {
    private val key = PluginStepId("core.sleep")

    @Test fun `registry is primary and legacy counters retain only the ten residual keys`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(key))
        assertFalse("core.sleep" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertEquals(StructuralStepFamily.Registry, StructuralFamilyResolver.classify(key, registry))
        assertEquals(setOf(
            "core.file.writeFile", "core.emit.event", "core.milestone", "core.deleteDir", "core.cleanWs",
            "core.load", "core.pwd", "core.isUnix", "core.waitUntil", "core.archiveArtifacts",
        ), CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertEquals(11, CanonicalCoreStepMetadata.pluginIds.size)
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
        val input = CoreSleepStep.definition.contract.inputCodec.encode(CoreSleepInput(0))
        val prep = dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation.prepare(
            CoreStepRegistryFactory.registry(), key, input, emptySet())
        val ready = prep as dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation.Ready
        val prepared = ready.prepared as dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
        val result = dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary.coexecute(prepared,
            testContext("zero"))
        assertEquals(StepOutcome.Success, result.outcome)
    }

    @Test fun `registry seam preserves parent cancellation`() = runBlocking {
        val job = launch {
            CoreSleepStep.definition.handler.execute(CoreSleepInput(Long.MAX_VALUE),
                dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(dev.rubentxu.pipeline.v2.domain.RunId("g4"), 0,
                    object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                        override fun available() = emptySet<dev.rubentxu.pipeline.v2.domain.step.StepCapability>()
                        override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T = error("none")
                    }))
        }
        delay(30); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    private fun testContext(label: String) = dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext(
        dev.rubentxu.pipeline.v2.application.durable.OpId("g4-$label", 0, 0), "g4-$label", "g4", 0, 0,
        dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions.EMPTY,
        java.nio.file.Files.createTempDirectory("g4-$label"), dev.rubentxu.pipeline.v2.events.InMemoryEventStore())
}
