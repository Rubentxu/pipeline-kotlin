package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** G3 frozen classification proof: every observed legacy/candidate differential is named. */
@Disabled("Historical G3 differential evidence; legacy core.sleep authority was removed at G5.")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreSleepLegacyRegistryDifferentialParityTest {
    private val key = PluginStepId("core.sleep")

    @Test
    fun `PARITY identity envelope metadata location and capabilities are frozen`() {
        val legacy = CanonicalCoreStepMetadata.metadata("core.sleep")
        val candidate = CoreSleepStep.definition.contract
        assertEquals(key, CoreSleepStep.KEY)
        for (seconds in listOf(0L, 1L, Long.MAX_VALUE)) {
            val encoded = candidate.inputCodec.encode(CoreSleepInput(seconds)).value
            assertEquals("""{"kind":"sleep","seconds":$seconds}""", encoded)
        }
        assertEquals(legacy.effects, candidate.descriptor.effects.toSet())
        assertEquals(legacy.replayPolicy, candidate.descriptor.replayPolicy)
        assertEquals(RecoveryPolicy.None, legacy.recoveryPolicy)
        assertEquals(RecoveryPolicy.None, candidate.descriptor.recoveryPolicy)
        assertEquals(ExecutionLocation.CONTROLLER, candidate.descriptor.executionLocation)
        assertTrue(candidate.requiredCapabilities.isEmpty(), "legacy semantic-none maps to registry emptySet")
    }

    @Test
    fun `PARITY zero succeeds and memoized replay skips a succeeded operation`() {
        assertEquals(CoreSleepInput(0), CoreSleepStep.definition.contract.inputCodec.decode(
            dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("""{"kind":"sleep","seconds":0}"""),
        ))
        assertEquals(
            ReplayDecision.SKIP,
            DefaultEffectReplayPolicy().decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true,
                dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED),
        )
    }

    @Test
    fun `APPROVED CONTRACT DELTA negative input rejects before handler effect`() {
        assertThrows(IllegalArgumentException::class.java) { CoreSleepInput(-1) }
        assertThrows(IllegalArgumentException::class.java) {
            CoreSleepStep.definition.contract.inputCodec.decode(
                dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("""{"kind":"sleep","seconds":-1}"""),
            )
        }
    }

    @Test
    fun `APPROVED FIX large duration is cancellable without millis overflow`() = runBlocking {
        val job = launch {
            CoreSleepStep.definition.handler.execute(CoreSleepInput(Long.MAX_VALUE), sleepTestContext())
        }
        delay(30)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `APPROVED FIX parent timeout remains parent owned`() = runBlocking {
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(30) {
                    CoreSleepStep.definition.handler.execute(CoreSleepInput(Long.MAX_VALUE), sleepTestContext())
                }
            }
        }
    }

    private fun sleepTestContext() = dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
        runId = dev.rubentxu.pipeline.v2.domain.RunId("g3-sleep"), stepIndex = 0,
        capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
            override fun available() = emptySet<dev.rubentxu.pipeline.v2.domain.step.StepCapability>()
            override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T = error("no capability")
        },
    )
}

/** G3 pre-flip structural readiness proof, deliberately separate from differential semantics. */
@Disabled("Historical G3 pre-flip evidence; superseded by CoreSleepRegistryPrimaryFitnessTest at G4.")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreSleepMigrationReadinessFitnessTest {
    @Test fun `candidate is registered but legacy membership wins routing`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CoreSleepStep.KEY))
        assertTrue("core.sleep" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertEquals(StructuralStepFamily.LegacyCore, StructuralFamilyResolver.classify(CoreSleepStep.KEY, registry))
    }

    @Test fun `legacy machinery and exact counters remain pre-flip`() {
        assertEquals(11, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertEquals(11, CanonicalCoreStepMetadata.pluginIds.size)
        assertTrue(CanonicalCoreStepMetadata.pluginIds.contains("core.sleep"))
        val root = v2Root()
        val durable = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable")
        assertTrue(Files.exists(durable.resolve("CanonicalSleepNodeDispatcher.kt")))
        val decoder = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
        assertTrue(Files.readString(decoder).contains("CanonicalCoreStepCommand.Sleep"))
    }

    @Test fun `generic boundary has no sleep-specific branch`() {
        val boundary = v2Root().resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt")
        assertFalse(Files.readString(boundary).contains("core.sleep"))
    }

    private fun v2Root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("pipeline-application")) }
}
