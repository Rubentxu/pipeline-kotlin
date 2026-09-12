package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * S2-A2 / G5 irreversible proof:
 * LEGACY_REMOVED(core.sleep) = membership absent ∧ command absent ∧ decoder absent
 * ∧ dispatcher absent ∧ metadata absent.
 */
class S3SleepLegacyRemovedFitnessTest {
    private val root = ScannerSupport.v2Root()
    private val decoder = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val metadata = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val dispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val sleepDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalSleepNodeDispatcher.kt")
    private val registry = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt")
    private val sleepStep = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepStep.kt")


    private fun read(path: java.nio.file.Path): String = Files.readString(path)
    private fun codeOnly(source: String): String = Regex("//[^\\n]*").replace(source, "")
        .let { Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(it, "") }

    @Test fun `core sleep remains registered and structurally registry owned`() {
        assertTrue(read(registry).contains("CoreSleepStep.registerInto(this)"))
        assertTrue(read(sleepStep).contains("PluginStepId(\"core.sleep\")"))
        assertFalse("core.sleep" in LegacyResidualSnapshot.liveLegacyIds(root))
    }

    @Test fun `legacy command decoder and dispatcher forms are absent`() {
        val decoderSource = codeOnly(read(decoder))
        assertFalse(decoderSource.contains("data class Sleep"))
        assertFalse(decoderSource.contains("SLEEP_PLUGIN_ID"))
        assertFalse(Regex("\\\"core\\.sleep\\\"\\s*->").containsMatchIn(decoderSource))
        assertFalse(Files.exists(sleepDispatcher))
        val dispatcherSource = codeOnly(read(dispatcher))
        assertFalse(dispatcherSource.contains("sleepDispatcher"))
        assertFalse(dispatcherSource.contains("CanonicalCoreStepCommand.Sleep"))
        assertFalse(dispatcherSource.contains("sleepContext()"))
    }

    @Test fun `legacy metadata has no core sleep row`() {
        val source = codeOnly(read(metadata))
        assertFalse(Regex("\\\"core\\.sleep\\\"\\s+to\\s+StepMetadata\\(").containsMatchIn(source))
    }

    @Test fun `three residual legacy authorities converge to exact six step snapshots`() {
        // Single shared authority: ONE place to flip 6 -> 5 at the next G4/G5.
        // TRANSITIONAL (deleteDir G4 window): restore assertConverged at deleteDir G5.
        LegacyResidualSnapshot.assertCurrentState(root)
    }
}
