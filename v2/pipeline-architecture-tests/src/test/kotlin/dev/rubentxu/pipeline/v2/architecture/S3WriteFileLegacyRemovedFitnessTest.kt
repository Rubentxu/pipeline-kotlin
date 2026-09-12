package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * S2-A3 / G5 irreversible proof:
 * LEGACY_REMOVED(core.file.writeFile) = membership absent ∧ command absent ∧ decoder absent
 * ∧ dispatcher absent ∧ metadata absent.
 */
class S3WriteFileLegacyRemovedFitnessTest {
    private val root = ScannerSupport.v2Root()
    private val decoder = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val metadata = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val dispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val writeFileDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWriteFileNodeDispatcher.kt")
    private val registry = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt")
    private val writeFileStep = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWriteFileStep.kt")


    private fun read(path: java.nio.file.Path): String = Files.readString(path)
    private fun codeOnly(source: String): String = Regex("//[^\\n]*").replace(source, "")
        .let { Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(it, "") }

    @Test fun `core file writeFile remains registered and structurally registry owned`() {
        assertTrue(read(registry).contains("CoreWriteFileStep.registerInto(this)"))
        assertTrue(read(writeFileStep).contains("PluginStepId(\"core.file.writeFile\")"))
        assertFalse("core.file.writeFile" in LegacyResidualSnapshot.liveLegacyIds(root))
    }

    @Test fun `legacy command decoder and dispatcher forms are absent`() {
        val decoderSource = codeOnly(read(decoder))
        assertFalse(decoderSource.contains("data class WriteFile"))
        assertFalse(decoderSource.contains("WRITE_FILE_PLUGIN_ID"))
        assertFalse(Regex("\\\"core\\.file\\.writeFile\\\"\\s*->").containsMatchIn(decoderSource))
        assertFalse(Files.exists(writeFileDispatcher))
        val dispatcherSource = codeOnly(read(dispatcher))
        assertFalse(dispatcherSource.contains("writeFileDispatcher"))
        assertFalse(dispatcherSource.contains("CanonicalCoreStepCommand.WriteFile"))
        assertFalse(dispatcherSource.contains("writeFileContext()"))
    }

    @Test fun `legacy metadata has no core file writeFile row`() {
        val source = codeOnly(read(metadata))
        assertFalse(Regex("\\\"core\\.file\\.writeFile\\\"\\s+to\\s+StepMetadata\\(").containsMatchIn(source))
    }

    @Test fun `three residual legacy authorities converge to exact six step snapshots`() {
        // Single shared authority: ONE place to flip 6 -> 5 at the next G4/G5.
        // TRANSITIONAL (deleteDir G4 window): restore assertConverged at deleteDir G5.
        LegacyResidualSnapshot.assertCurrentState(root)
    }
}
