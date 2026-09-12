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

    private val expectedIds = setOf(
        "core.milestone", "core.deleteDir", "core.cleanWs",
        "core.load", "core.pwd", "core.waitUntil", "core.archiveArtifacts",
    )

    private fun read(path: java.nio.file.Path): String = Files.readString(path)
    private fun codeOnly(source: String): String = Regex("//[^\\n]*").replace(source, "")
        .let { Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(it, "") }
    private fun legacyIds(): Set<String> {
        val block = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)").find(codeOnly(read(decoder)))?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        return Regex("\\\"(core\\.[a-zA-Z.]+)\\\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    @Test fun `core file writeFile remains registered and structurally registry owned`() {
        assertTrue(read(registry).contains("CoreWriteFileStep.registerInto(this)"))
        assertTrue(read(writeFileStep).contains("PluginStepId(\"core.file.writeFile\")"))
        assertFalse("core.file.writeFile" in legacyIds())
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

    @Test fun `transitional snapshot converges to 7 IDs 7 metadata rows 7 dispatchers S2-A5-G5 convergence`() {
        assertEquals(expectedIds, legacyIds())
        val metadataKeys = Regex("\\\"(core\\.[a-zA-Z.]+)\\\"\\s+to\\s+StepMetadata\\(")
            .findAll(codeOnly(read(metadata))).map { it.groupValues[1] }.toSet()
        // S2-A5/G4 window: core.isUnix routing flipped (IDs) but its legacy metadata row and
        // dispatcher were physically present until S2-A5/G5 (LEGACY_UNREACHABLE -> REMOVED).
        assertEquals(expectedIds, metadataKeys)
        val durable = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable")
        val actualDispatchers = Files.list(durable).use { paths -> paths.map { it.fileName.toString() }
            .filter { it.startsWith("Canonical") && it.endsWith("NodeDispatcher.kt") && it != "CanonicalNodeDispatcher.kt" }
            .toList().toSet() }
        assertEquals(setOf(
            "CanonicalMilestoneNodeDispatcher.kt", "CanonicalDeleteDirNodeDispatcher.kt",
            "CanonicalCleanWsNodeDispatcher.kt", "CanonicalLoadNodeDispatcher.kt", "CanonicalPwdNodeDispatcher.kt",
            "CanonicalWaitUntilNodeDispatcher.kt",
            "CanonicalArchiveArtifactsNodeDispatcher.kt",
        ), actualDispatchers)
        assertEquals(7, legacyIds().size)
        assertEquals(7, metadataKeys.size)
        assertEquals(7, actualDispatchers.size)
    }
}
